package com.guicedee.vertx.graphql.test;

import com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * Test schema provider that contributes a simple "hello" query.
 */
public class TestSchemaProvider implements IGraphQLSchemaProvider<TestSchemaProvider>
{
    @Override
    public TypeDefinitionRegistry getTypeDefinitions()
    {
        return new SchemaParser().parse("""
                type Query {
                    hello: String
                    greet(name: String!): String
                }
                """);
    }

    @Override
    public RuntimeWiring.Builder configureWiring(RuntimeWiring.Builder builder)
    {
        return builder.type("Query", b -> b
                .dataFetcher("hello", env -> "Hello, GraphQL!")
                .dataFetcher("greet", env -> "Hello, " + env.getArgument("name") + "!")
        );
    }
}

