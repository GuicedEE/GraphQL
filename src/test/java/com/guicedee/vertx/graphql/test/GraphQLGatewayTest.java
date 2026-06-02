package com.guicedee.vertx.graphql.test;

import com.guicedee.client.IGuiceContext;
import com.guicedee.vertx.graphql.gateway.RemoteGraphQLSchemaProvider;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the GraphQL Gateway (remote schema stitching).
 *
 * <p>This test:
 * <ol>
 *     <li>Boots GuicedEE with the local GraphQL endpoint (hello/greet)</li>
 *     <li>Registers the local service in the service registry with graphqlPath metadata</li>
 *     <li>Enables the gateway and verifies it can introspect the local schema</li>
 *     <li>Verifies proxy data fetchers can forward queries</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GraphQLGatewayTest
{
    private static boolean booted = false;

    @BeforeAll
    static void setUp() throws Exception
    {
        // Enable gateway for testing
        System.setProperty("GRAPHQL_GATEWAY_ENABLED", "true");
        System.setProperty("GRAPHQL_GATEWAY_ENVIRONMENT", "");
        System.setProperty("GRAPHQL_GATEWAY_TIMEOUT_MS", "10000");

        if (!booted)
        {
            IGuiceContext.instance().inject();
            // Allow Vert.x server to start
            Thread.sleep(2000);
            booted = true;
        }

        // Register the local service in the service registry with graphqlPath metadata
        registerLocalServiceInRegistry();
    }

    /**
     * Registers the local GraphQL service (running on localhost:8080) into the service registry
     * with graphqlPath metadata so the gateway can discover it.
     */
    private static void registerLocalServiceInRegistry()
    {
        try
        {
            Class<?> registryClass = Class.forName("com.guicedee.service.registry.ServiceRegistry");
            Class<?> entryClass = Class.forName("com.guicedee.service.registry.ServiceEntry");
            Class<?> statusClass = Class.forName("com.guicedee.service.registry.ServiceStatus");

            // Get ServiceStatus.UP
            Object statusUp = null;
            for (Object constant : statusClass.getEnumConstants())
            {
                if ("UP".equals(constant.toString()))
                {
                    statusUp = constant;
                    break;
                }
            }

            // Create a ServiceEntry with graphqlPath metadata
            Map<String, String> metadata = Map.of(
                    "graphqlPath", "/graphql",
                    "graphqlEnvironments", "dev,int,prod"
            );

            // Use the constructor: ServiceEntry(name, url, healthPath, status, lastChecked, metadata)
            var constructor = entryClass.getConstructor(
                    String.class, String.class, String.class, statusClass, Instant.class, Map.class);
            Object entry = constructor.newInstance(
                    "test-graphql-service",
                    "http://localhost:8080",
                    "/health/ready",
                    statusUp,
                    Instant.now(),
                    metadata
            );

            // Register it
            var registerMethod = registryClass.getMethod("register", entryClass);
            registerMethod.invoke(null, entry);

            System.out.println("[TEST] Registered test-graphql-service in service registry");
        }
        catch (ClassNotFoundException e)
        {
            System.out.println("[TEST] service-registry not available, skipping registry setup: " + e.getMessage());
        }
        catch (Exception e)
        {
            System.out.println("[TEST] Failed to register service: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    @Order(1)
    public void testGatewayIsEnabled()
    {
        System.setProperty("GRAPHQL_GATEWAY_ENABLED", "true");
        RemoteGraphQLSchemaProvider provider = new RemoteGraphQLSchemaProvider();
        // The provider should attempt to introspect when enabled
        assertNotNull(provider, "Provider should be instantiable");
    }

    @Test
    @Order(2)
    public void testGatewayDisabledReturnsEmptyRegistry()
    {
        System.setProperty("GRAPHQL_GATEWAY_ENABLED", "false");
        RemoteGraphQLSchemaProvider provider = new RemoteGraphQLSchemaProvider();
        TypeDefinitionRegistry result = provider.getTypeDefinitions();
        assertNotNull(result, "Should return non-null registry");
        assertTrue(result.types().isEmpty(), "Disabled gateway should return empty type definitions");
        System.setProperty("GRAPHQL_GATEWAY_ENABLED", "true");
    }

    @Test
    @Order(3)
    public void testGatewayIntrospectionFromLocalService() throws Exception
    {
        // First verify the local GraphQL endpoint is working
        HttpClient client = HttpClient.newBuilder()
                                      .connectTimeout(Duration.of(5, ChronoUnit.SECONDS))
                                      .build();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                           .POST(HttpRequest.BodyPublishers.ofString("{\"query\": \"{ hello }\"}"))
                           .header("Content-Type", "application/json")
                           .uri(new URI("http://localhost:8080/graphql"))
                           .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Local GraphQL endpoint should be available");
        System.out.println("[TEST] Local endpoint verified: " + response.body());

        // Now test introspection via the gateway provider
        RemoteGraphQLSchemaProvider provider = new RemoteGraphQLSchemaProvider();
        TypeDefinitionRegistry registry = provider.getTypeDefinitions();

        System.out.println("[TEST] Gateway introspected types: " + registry.types().keySet());

        // The gateway should have discovered the test-graphql-service and introspected its schema
        assertFalse(registry.types().isEmpty(),
                "Gateway should have introspected at least one type from the remote service");

        // Verify Query type was found
        assertTrue(registry.getType("Query").isPresent(),
                "Gateway should have discovered the Query type");
    }

    @Test
    @Order(4)
    public void testGatewayIntrospectionQuery() throws Exception
    {
        // Test that the standard introspection query works against the local endpoint
        HttpClient client = HttpClient.newBuilder()
                                      .connectTimeout(Duration.of(5, ChronoUnit.SECONDS))
                                      .build();

        String introspectionQuery = """
                {"query": "{ __schema { queryType { name } types { name kind } } }"}
                """;

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                           .POST(HttpRequest.BodyPublishers.ofString(introspectionQuery))
                           .header("Content-Type", "application/json")
                           .uri(new URI("http://localhost:8080/graphql"))
                           .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Introspection should succeed");
        assertTrue(response.body().contains("\"name\":\"Query\""),
                "Introspection should contain Query type");
        System.out.println("[TEST] Introspection result: " + response.body().substring(0, Math.min(500, response.body().length())));
    }

    @Test
    @Order(5)
    public void testGatewayEnvironmentFiltering()
    {
        // Set environment to something that doesn't match
        System.setProperty("GRAPHQL_GATEWAY_ENVIRONMENT", "staging");

        RemoteGraphQLSchemaProvider provider = new RemoteGraphQLSchemaProvider();
        TypeDefinitionRegistry registry = provider.getTypeDefinitions();

        // The service is registered with "dev,int,prod" environments, so "staging" should filter it out
        assertTrue(registry.types().isEmpty(),
                "Gateway should filter out services not matching the current environment");

        // Reset
        System.setProperty("GRAPHQL_GATEWAY_ENVIRONMENT", "");
    }

    @Test
    @Order(6)
    public void testGatewayEnvironmentMatch()
    {
        // Set environment to one that matches
        System.setProperty("GRAPHQL_GATEWAY_ENVIRONMENT", "dev");

        RemoteGraphQLSchemaProvider provider = new RemoteGraphQLSchemaProvider();
        TypeDefinitionRegistry registry = provider.getTypeDefinitions();

        // The service is registered with "dev,int,prod" environments, so "dev" should match
        assertFalse(registry.types().isEmpty(),
                "Gateway should include services matching the current environment");

        System.out.println("[TEST] Environment 'dev' matched, types: " + registry.types().keySet());

        // Reset
        System.setProperty("GRAPHQL_GATEWAY_ENVIRONMENT", "");
    }

    @AfterAll
    static void tearDown()
    {
        System.clearProperty("GRAPHQL_GATEWAY_ENABLED");
        System.clearProperty("GRAPHQL_GATEWAY_ENVIRONMENT");
        System.clearProperty("GRAPHQL_GATEWAY_TIMEOUT_MS");
    }
}

