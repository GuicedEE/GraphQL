package com.guicedee.vertx.graphql.services;

import com.guicedee.client.services.IDefaultService;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * SPI for contributing GraphQL schema definitions and runtime wiring.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} at startup
 * and merged into a single {@link graphql.GraphQL} instance.</p>
 *
 * <p>Register in both {@code module-info.java} and
 * {@code META-INF/services/com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider}.</p>
 */
public interface IGraphQLSchemaProvider<J extends IGraphQLSchemaProvider<J>> extends IDefaultService<J>
{
    /**
     * Returns the type definitions (SDL) contributed by this provider.
     *
     * @return the type definition registry, never null
     */
    TypeDefinitionRegistry getTypeDefinitions();

    /**
     * Configures runtime wiring (data fetchers, type resolvers, scalars) on the given builder.
     *
     * @param builder the current wiring builder
     * @return the updated wiring builder
     */
    RuntimeWiring.Builder configureWiring(RuntimeWiring.Builder builder);
}

