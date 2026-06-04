package com.guicedee.vertx.graphql.implementations;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.guicedee.client.IGuiceContext;
import com.guicedee.client.services.lifecycle.IGuiceModule;
import com.guicedee.vertx.graphql.GraphQLOptions;
import com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider;
import graphql.GraphQL;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.vertx.ext.web.handler.graphql.instrumentation.JsonObjectAdapter;
import io.vertx.ext.web.handler.graphql.instrumentation.VertxFutureAdapter;

import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Guice module that builds and binds the {@link GraphQL} instance from SPI-contributed
 * schema providers, with Vert.x instrumentations auto-configured.
 */
public class GraphQLModule extends AbstractModule implements IGuiceModule<GraphQLModule>
{
    private static final Logger log = Logger.getLogger("GraphQLModule");

    @Override
    protected void configure()
    {
        bind(GraphQLOptions.class).asEagerSingleton();
    }

    /**
     * Builds a singleton {@link GraphQL} instance by merging all {@link IGraphQLSchemaProvider}
     * contributions and adding Vert.x instrumentations.
     *
     * @return the fully-configured GraphQL instance
     */
    @Provides
    @Singleton
    @SuppressWarnings("rawtypes")
    GraphQL provideGraphQL()
    {
        Set<IGraphQLSchemaProvider> providers = IGuiceContext.loaderToSet(
                ServiceLoader.load(IGraphQLSchemaProvider.class));

        TypeDefinitionRegistry mergedRegistry = new TypeDefinitionRegistry();
        RuntimeWiring.Builder wiringBuilder = RuntimeWiring.newRuntimeWiring();

        for (IGraphQLSchemaProvider<?> provider : providers)
        {
            try
            {
                TypeDefinitionRegistry typeDefs = provider.getTypeDefinitions();
                if (typeDefs != null)
                {
                    mergedRegistry.merge(typeDefs);
                }
                wiringBuilder = provider.configureWiring(wiringBuilder);
            }
            catch (Throwable t)
            {
                log.log(Level.SEVERE, "Error loading GraphQL schema provider: " + provider.getClass().getName(), t);
            }
        }

        GraphQLSchema schema = new SchemaGenerator()
                .makeExecutableSchema(mergedRegistry, wiringBuilder.build());

        return GraphQL.newGraphQL(schema)
                .instrumentation(new ChainedInstrumentation(
                        VertxFutureAdapter.create(),
                        new JsonObjectAdapter()))
                .build();
    }
}

