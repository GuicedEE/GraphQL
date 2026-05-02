package com.guicedee.vertx.graphql.test;

import com.google.inject.AbstractModule;
import com.guicedee.client.services.lifecycle.IGuiceModule;

/**
 * Test Guice module for GraphQL tests.
 */
public class GraphQLTestModule extends AbstractModule implements IGuiceModule<GraphQLTestModule>
{
    @Override
    protected void configure()
    {
        bind(TestSchemaProvider.class);
    }
}

