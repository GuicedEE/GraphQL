package com.guicedee.vertx.graphql.gateway;

import com.guicedee.client.Environment;
import com.guicedee.client.IGuiceContext;
import com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider;
import graphql.language.*;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional {@link IGraphQLSchemaProvider} that stitches remote GraphQL schemas from services
 * registered in the service registry.
 *
 * <p>This provider is only active when:
 * <ul>
 *     <li>{@code com.guicedee.service.registry} module is on the module path</li>
 *     <li>{@code GRAPHQL_GATEWAY_ENABLED=true} environment variable is set</li>
 * </ul>
 *
 * <p>It discovers remote services that expose a {@code graphqlPath} metadata entry,
 * introspects their schemas, merges them, and registers proxy data fetchers that forward
 * queries to the originating service.</p>
 *
 * <h2>Environment Variables</h2>
 * <table>
 *     <tr><th>Variable</th><th>Default</th><th>Purpose</th></tr>
 *     <tr><td>GRAPHQL_GATEWAY_ENABLED</td><td>false</td><td>Enable remote schema stitching</td></tr>
 *     <tr><td>GRAPHQL_GATEWAY_ENVIRONMENT</td><td>(empty)</td><td>Only merge services allowed in this environment</td></tr>
 *     <tr><td>GRAPHQL_GATEWAY_TIMEOUT_MS</td><td>10000</td><td>Introspection timeout per service (ms)</td></tr>
 * </table>
 *
 * <h2>Service Registration</h2>
 * Services declare GraphQL availability via {@code @RegisteredService} metadata:
 * <pre>{@code
 * @RegisteredService(name = "order-service",
 *     graphqlPath = "/graphql",
 *     graphqlEnvironments = {"dev", "int"})
 * }</pre>
 */
public class RemoteGraphQLSchemaProvider implements IGraphQLSchemaProvider<RemoteGraphQLSchemaProvider>
{
    private static final Logger log = Logger.getLogger("RemoteGraphQLSchemaProvider");

    private static final String INTROSPECTION_QUERY = """
            {
              __schema {
                types {
                  name
                  kind
                  fields {
                    name
                    type { name kind ofType { name kind ofType { name kind ofType { name kind } } } }
                    args { name type { name kind ofType { name kind ofType { name kind } } } }
                  }
                  inputFields {
                    name
                    type { name kind ofType { name kind ofType { name kind } } }
                  }
                  enumValues { name }
                  interfaces { name }
                  possibleTypes { name }
                }
                queryType { name }
                mutationType { name }
                subscriptionType { name }
              }
            }
            """;

    private final Map<String, RemoteServiceSchema> remoteSchemas = new LinkedHashMap<>();

    @Override
    public Integer sortOrder()
    {
        // Run after local providers so local schemas take priority
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public TypeDefinitionRegistry getTypeDefinitions()
    {
        if (!isEnabled())
        {
            return new TypeDefinitionRegistry();
        }

        Map<String, String> graphqlUrls = getRemoteGraphQLUrls();
        if (graphqlUrls.isEmpty())
        {
            log.info("GraphQL Gateway: No remote services with graphqlPath found");
            return new TypeDefinitionRegistry();
        }

        TypeDefinitionRegistry merged = new TypeDefinitionRegistry();
        long timeoutMs = Long.parseLong(Environment.getProperty("GRAPHQL_GATEWAY_TIMEOUT_MS", "10000"));

        for (var entry : graphqlUrls.entrySet())
        {
            String serviceName = entry.getKey();
            String url = entry.getValue();
            try
            {
                log.info("GraphQL Gateway: Introspecting schema from '" + serviceName + "' at " + url);
                String sdl = introspectSchema(url, timeoutMs);
                if (sdl != null && !sdl.isBlank())
                {
                    TypeDefinitionRegistry remote = new SchemaParser().parse(sdl);
                    remoteSchemas.put(serviceName, new RemoteServiceSchema(serviceName, url, remote));
                    merged.merge(remote);
                    log.info("GraphQL Gateway: Merged schema from '" + serviceName + "'");
                }
            }
            catch (Throwable t)
            {
                log.log(Level.WARNING, "GraphQL Gateway: Failed to introspect '" + serviceName + "' at " + url, t);
            }
        }

        return merged;
    }

    @Override
    public RuntimeWiring.Builder configureWiring(RuntimeWiring.Builder builder)
    {
        if (!isEnabled() || remoteSchemas.isEmpty())
        {
            return builder;
        }

        for (var entry : remoteSchemas.entrySet())
        {
            RemoteServiceSchema schema = entry.getValue();
            TypeDefinitionRegistry registry = schema.registry();

            // Register proxy data fetchers for Query fields from this remote service
            registry.getType("Query").ifPresent(typeDef -> {
                if (typeDef instanceof ObjectTypeDefinition queryType)
                {
                    for (FieldDefinition field : queryType.getFieldDefinitions())
                    {
                        builder.type("Query", typeBuilder ->
                                typeBuilder.dataFetcher(field.getName(),
                                        createProxyDataFetcher(schema.url(), field.getName(), "query")));
                    }
                }
            });

            // Register proxy data fetchers for Mutation fields from this remote service
            registry.getType("Mutation").ifPresent(typeDef -> {
                if (typeDef instanceof ObjectTypeDefinition mutationType)
                {
                    for (FieldDefinition field : mutationType.getFieldDefinitions())
                    {
                        builder.type("Mutation", typeBuilder ->
                                typeBuilder.dataFetcher(field.getName(),
                                        createProxyDataFetcher(schema.url(), field.getName(), "mutation")));
                    }
                }
            });
        }

        return builder;
    }

    private DataFetcher<?> createProxyDataFetcher(String remoteUrl, String fieldName, String operationType)
    {
        return environment -> {
            Vertx vertx = IGuiceContext.get(Vertx.class);
            WebClient client = WebClient.create(vertx);

            String query = buildRemoteQuery(environment, fieldName, operationType);
            JsonObject variables = environment.getArguments() != null && !environment.getArguments().isEmpty()
                    ? JsonObject.mapFrom(environment.getArguments())
                    : new JsonObject();
            JsonObject requestBody = new JsonObject()
                    .put("query", query)
                    .put("variables", variables);

            CompletableFuture<Object> future = new CompletableFuture<>();
            long timeoutMs = Long.parseLong(Environment.getProperty("GRAPHQL_GATEWAY_TIMEOUT_MS", "10000"));

            client.postAbs(remoteUrl)
                    .putHeader("Content-Type", "application/json")
                    .sendJsonObject(requestBody)
                    .onSuccess(response -> {
                        try
                        {
                            JsonObject body = response.bodyAsJsonObject();
                            if (body.containsKey("errors") && !body.containsKey("data"))
                            {
                                future.completeExceptionally(new RuntimeException(
                                        "Remote GraphQL error from " + remoteUrl + ": " + body.getJsonArray("errors")));
                            }
                            else
                            {
                                JsonObject data = body.getJsonObject("data");
                                future.complete(data != null ? convertJsonValue(data.getValue(fieldName)) : null);
                            }
                        }
                        catch (Throwable t)
                        {
                            future.completeExceptionally(t);
                        }
                    })
                    .onFailure(future::completeExceptionally);

            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        };
    }

    private String buildRemoteQuery(DataFetchingEnvironment environment, String fieldName, String operationType)
    {
        var selectionSet = environment.getSelectionSet();
        if (selectionSet != null && !selectionSet.getFields().isEmpty())
        {
            StringBuilder fields = new StringBuilder();
            for (var field : selectionSet.getFields())
            {
                // Only include immediate fields (not nested paths with /)
                if (!field.getQualifiedName().contains("/"))
                {
                    fields.append(field.getName()).append(" ");
                }
            }
            if (!fields.isEmpty())
            {
                return operationType + " { " + fieldName + " { " + fields + "} }";
            }
        }
        return operationType + " { " + fieldName + " }";
    }

    private Object convertJsonValue(Object value)
    {
        if (value instanceof JsonObject jsonObj) return jsonObj.getMap();
        if (value instanceof JsonArray jsonArr) return jsonArr.getList();
        return value;
    }

    private String introspectSchema(String url, long timeoutMs)
    {
        Vertx vertx = IGuiceContext.get(Vertx.class);
        WebClient client = WebClient.create(vertx);

        CompletableFuture<String> future = new CompletableFuture<>();
        JsonObject requestBody = new JsonObject().put("query", INTROSPECTION_QUERY);

        client.postAbs(url)
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(requestBody)
                .onSuccess(response -> {
                    try
                    {
                        JsonObject body = response.bodyAsJsonObject();
                        if (body.containsKey("data"))
                        {
                            String sdl = convertIntrospectionToSDL(body.getJsonObject("data").getJsonObject("__schema"));
                            future.complete(sdl);
                        }
                        else
                        {
                            future.complete(null);
                        }
                    }
                    catch (Throwable t)
                    {
                        future.completeExceptionally(t);
                    }
                })
                .onFailure(future::completeExceptionally);

        try
        {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        }
        catch (Throwable t)
        {
            log.log(Level.WARNING, "Introspection failed for " + url, t);
            return null;
        }
    }

    private String convertIntrospectionToSDL(JsonObject schema)
    {
        StringBuilder sdl = new StringBuilder();
        Set<String> builtInTypes = Set.of("String", "Int", "Float", "Boolean", "ID",
                "__Schema", "__Type", "__Field", "__InputValue", "__EnumValue", "__Directive",
                "__DirectiveLocation", "__TypeKind");

        JsonArray types = schema.getJsonArray("types");
        if (types == null) return "";

        for (int i = 0; i < types.size(); i++)
        {
            JsonObject type = types.getJsonObject(i);
            String name = type.getString("name");
            String kind = type.getString("kind");
            if (builtInTypes.contains(name) || name.startsWith("__")) continue;

            switch (kind)
            {
                case "OBJECT" -> sdl.append(buildObjectType(type, name));
                case "INPUT_OBJECT" -> sdl.append(buildInputType(type, name));
                case "ENUM" -> sdl.append(buildEnumType(type, name));
                case "INTERFACE" -> sdl.append(buildInterfaceType(type, name));
                case "UNION" -> sdl.append(buildUnionType(type, name));
                case "SCALAR" -> sdl.append("scalar ").append(name).append("\n\n");
            }
        }

        String queryTypeName = schema.getJsonObject("queryType") != null ? schema.getJsonObject("queryType").getString("name") : null;
        String mutationTypeName = schema.getJsonObject("mutationType") != null ? schema.getJsonObject("mutationType").getString("name") : null;
        String subscriptionTypeName = schema.getJsonObject("subscriptionType") != null ? schema.getJsonObject("subscriptionType").getString("name") : null;

        if ((queryTypeName != null && !"Query".equals(queryTypeName)) ||
                (mutationTypeName != null && !"Mutation".equals(mutationTypeName)) ||
                (subscriptionTypeName != null && !"Subscription".equals(subscriptionTypeName)))
        {
            sdl.append("schema {\n");
            if (queryTypeName != null) sdl.append("  query: ").append(queryTypeName).append("\n");
            if (mutationTypeName != null) sdl.append("  mutation: ").append(mutationTypeName).append("\n");
            if (subscriptionTypeName != null) sdl.append("  subscription: ").append(subscriptionTypeName).append("\n");
            sdl.append("}\n\n");
        }

        return sdl.toString();
    }

    private String buildObjectType(JsonObject type, String name)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("type ").append(name);
        JsonArray interfaces = type.getJsonArray("interfaces");
        if (interfaces != null && !interfaces.isEmpty())
        {
            sb.append(" implements ");
            for (int j = 0; j < interfaces.size(); j++)
            {
                if (j > 0) sb.append(" & ");
                sb.append(interfaces.getJsonObject(j).getString("name"));
            }
        }
        sb.append(" {\n");
        appendFields(sb, type.getJsonArray("fields"));
        sb.append("}\n\n");
        return sb.toString();
    }

    private String buildInputType(JsonObject type, String name)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("input ").append(name).append(" {\n");
        JsonArray inputFields = type.getJsonArray("inputFields");
        if (inputFields != null)
        {
            for (int j = 0; j < inputFields.size(); j++)
            {
                JsonObject field = inputFields.getJsonObject(j);
                sb.append("  ").append(field.getString("name")).append(": ")
                        .append(typeRefToString(field.getJsonObject("type"))).append("\n");
            }
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private String buildEnumType(JsonObject type, String name)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("enum ").append(name).append(" {\n");
        JsonArray enumValues = type.getJsonArray("enumValues");
        if (enumValues != null)
        {
            for (int j = 0; j < enumValues.size(); j++)
            {
                sb.append("  ").append(enumValues.getJsonObject(j).getString("name")).append("\n");
            }
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private String buildInterfaceType(JsonObject type, String name)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("interface ").append(name).append(" {\n");
        appendFields(sb, type.getJsonArray("fields"));
        sb.append("}\n\n");
        return sb.toString();
    }

    private String buildUnionType(JsonObject type, String name)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("union ").append(name).append(" = ");
        JsonArray possibleTypes = type.getJsonArray("possibleTypes");
        if (possibleTypes != null)
        {
            for (int j = 0; j < possibleTypes.size(); j++)
            {
                if (j > 0) sb.append(" | ");
                sb.append(possibleTypes.getJsonObject(j).getString("name"));
            }
        }
        sb.append("\n\n");
        return sb.toString();
    }

    private void appendFields(StringBuilder sb, JsonArray fields)
    {
        if (fields == null) return;
        for (int j = 0; j < fields.size(); j++)
        {
            JsonObject field = fields.getJsonObject(j);
            sb.append("  ").append(field.getString("name"));
            JsonArray args = field.getJsonArray("args");
            if (args != null && !args.isEmpty())
            {
                sb.append("(");
                for (int k = 0; k < args.size(); k++)
                {
                    if (k > 0) sb.append(", ");
                    JsonObject arg = args.getJsonObject(k);
                    sb.append(arg.getString("name")).append(": ").append(typeRefToString(arg.getJsonObject("type")));
                }
                sb.append(")");
            }
            sb.append(": ").append(typeRefToString(field.getJsonObject("type"))).append("\n");
        }
    }

    private String typeRefToString(JsonObject typeRef)
    {
        if (typeRef == null) return "String";
        String kind = typeRef.getString("kind");
        String name = typeRef.getString("name");
        JsonObject ofType = typeRef.getJsonObject("ofType");
        return switch (kind)
        {
            case "NON_NULL" -> typeRefToString(ofType) + "!";
            case "LIST" -> "[" + typeRefToString(ofType) + "]";
            default -> name != null ? name : "String";
        };
    }

    /**
     * Gets remote GraphQL URLs from service registry, filtered by environment.
     * Uses reflection to avoid hard compile-time dependency on service-registry module.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> getRemoteGraphQLUrls()
    {
        try
        {
            Class<?> registryClass = Class.forName("com.guicedee.service.registry.ServiceRegistry");
            var allMethod = registryClass.getMethod("all");
            Map<String, Object> services = (Map<String, Object>) allMethod.invoke(null);

            String environment = Environment.getProperty("GRAPHQL_GATEWAY_ENVIRONMENT", "");
            Map<String, String> result = new LinkedHashMap<>();

            for (var entry : services.entrySet())
            {
                Object serviceEntry = entry.getValue();
                Class<?> entryClass = serviceEntry.getClass();

                var metadataMethod = entryClass.getMethod("metadata");
                Map<String, String> metadata = (Map<String, String>) metadataMethod.invoke(serviceEntry);

                String graphqlPath = metadata.getOrDefault("graphqlPath", "");
                if (graphqlPath.isEmpty()) continue;

                // Environment filter
                String envFilter = metadata.getOrDefault("graphqlEnvironments", "");
                if (!envFilter.isEmpty() && !environment.isEmpty())
                {
                    boolean allowed = Arrays.stream(envFilter.split(","))
                            .map(String::trim)
                            .anyMatch(e -> e.equalsIgnoreCase(environment));
                    if (!allowed) continue;
                }

                // Health check
                var isHealthyMethod = entryClass.getMethod("isHealthy");
                if (!(boolean) isHealthyMethod.invoke(serviceEntry))
                {
                    log.info("GraphQL Gateway: Skipping unhealthy service '" + entry.getKey() + "'");
                    continue;
                }

                // Build full URL
                var urlMethod = entryClass.getMethod("url");
                String baseUrl = (String) urlMethod.invoke(serviceEntry);
                String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
                result.put(entry.getKey(), base + (graphqlPath.startsWith("/") ? graphqlPath : "/" + graphqlPath));
            }

            return result;
        }
        catch (ClassNotFoundException e)
        {
            log.fine("GraphQL Gateway: service-registry not on classpath, gateway disabled");
            return Map.of();
        }
        catch (Throwable t)
        {
            log.log(Level.WARNING, "GraphQL Gateway: Error accessing service registry", t);
            return Map.of();
        }
    }

    private boolean isEnabled()
    {
        return Boolean.parseBoolean(Environment.getProperty("GRAPHQL_GATEWAY_ENABLED", "false"));
    }

    record RemoteServiceSchema(String serviceName, String url, TypeDefinitionRegistry registry) {}
}

