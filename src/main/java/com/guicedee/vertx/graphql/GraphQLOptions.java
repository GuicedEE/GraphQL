package com.guicedee.vertx.graphql;

import com.google.inject.Singleton;
import com.guicedee.client.Environment;
import lombok.Data;

/**
 * Environment-driven configuration for the GuicedEE GraphQL module.
 *
 * <p>All settings can be overridden via system properties or environment variables.</p>
 *
 * <table>
 *     <tr><th>Variable</th><th>Default</th><th>Purpose</th></tr>
 *     <tr><td>GRAPHQL_HTTP_PATH</td><td>/graphql</td><td>HTTP endpoint path</td></tr>
 *     <tr><td>GRAPHQL_WS_ENABLED</td><td>true</td><td>Enable GraphQL over WebSocket</td></tr>
 *     <tr><td>GRAPHQL_WS_PATH</td><td>/graphql</td><td>WebSocket endpoint path</td></tr>
 *     <tr><td>GRAPHIQL_ENABLED</td><td>false</td><td>Enable GraphiQL IDE</td></tr>
 *     <tr><td>GRAPHIQL_PATH</td><td>/graphiql</td><td>GraphiQL mount path</td></tr>
 *     <tr><td>GRAPHQL_BATCHING_ENABLED</td><td>false</td><td>Enable query batching</td></tr>
 *     <tr><td>GRAPHQL_UPLOADS_ENABLED</td><td>false</td><td>Enable multipart file uploads</td></tr>
 * </table>
 */
@Data
@Singleton
public class GraphQLOptions
{
    private String httpPath = Environment.getProperty("GRAPHQL_HTTP_PATH", "/graphql");
    private boolean wsEnabled = Boolean.parseBoolean(Environment.getProperty("GRAPHQL_WS_ENABLED", "true"));
    private String wsPath = Environment.getProperty("GRAPHQL_WS_PATH", "/graphql");
    private boolean graphiqlEnabled = Boolean.parseBoolean(Environment.getProperty("GRAPHIQL_ENABLED", "false"));
    private String graphiqlPath = Environment.getProperty("GRAPHIQL_PATH", "/graphiql");
    private boolean batchingEnabled = Boolean.parseBoolean(Environment.getProperty("GRAPHQL_BATCHING_ENABLED", "false"));
    private boolean uploadsEnabled = Boolean.parseBoolean(Environment.getProperty("GRAPHQL_UPLOADS_ENABLED", "false"));
}

