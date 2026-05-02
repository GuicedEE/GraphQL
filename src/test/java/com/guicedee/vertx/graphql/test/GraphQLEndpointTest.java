package com.guicedee.vertx.graphql.test;

import com.guicedee.client.IGuiceContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that bootstraps GuicedEE, starts the Vert.x HTTP server,
 * and verifies the GraphQL HTTP endpoint.
 */
public class GraphQLEndpointTest
{
    @Test
    public void testGraphQLHttpEndpoint() throws Exception
    {
        System.out.println("Starting GraphQL integration test...");
        IGuiceContext.instance()
                     .inject();

        // Allow time for the Vert.x HTTP server to start listening
        Thread.sleep(2000);

        HttpClient client = HttpClient.newBuilder()
                                      .connectTimeout(Duration.of(5, ChronoUnit.SECONDS))
                                      .build();

        // Test simple hello query
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                           .POST(HttpRequest.BodyPublishers.ofString("{\"query\": \"{ hello }\"}"))
                           .header("Content-Type", "application/json")
                           .uri(new URI("http://localhost:8080/graphql"))
                           .build(),
                HttpResponse.BodyHandlers.ofString());

        System.out.println("Response from /graphql (hello) - " + response.statusCode());
        System.out.println("Response body - " + response.body());

        assertEquals(200, response.statusCode(), "GraphQL endpoint not available");
        assertTrue(response.body().contains("Hello, GraphQL!"), "Expected hello response");

        // Test query with argument
        response = client.send(
                HttpRequest.newBuilder()
                           .POST(HttpRequest.BodyPublishers.ofString(
                                   "{\"query\": \"{ greet(name: \\\"World\\\") }\"}"))
                           .header("Content-Type", "application/json")
                           .uri(new URI("http://localhost:8080/graphql"))
                           .build(),
                HttpResponse.BodyHandlers.ofString());

        System.out.println("Response from /graphql (greet) - " + response.statusCode());
        System.out.println("Response body - " + response.body());

        assertEquals(200, response.statusCode(), "GraphQL endpoint not available for greet query");
        assertTrue(response.body().contains("Hello, World!"), "Expected greet response");
    }

    public static void main(String[] args) throws Exception
    {
        new GraphQLEndpointTest().testGraphQLHttpEndpoint();
    }
}


