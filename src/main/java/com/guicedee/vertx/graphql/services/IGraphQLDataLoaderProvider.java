package com.guicedee.vertx.graphql.services;

import com.guicedee.client.services.IDefaultService;
import org.dataloader.DataLoaderRegistry;

/**
 * SPI for contributing {@link org.dataloader.DataLoader} instances to the per-request
 * {@link DataLoaderRegistry}.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} and invoked
 * for every GraphQL request to build a fresh {@link DataLoaderRegistry}.</p>
 *
 * <p>Register in both {@code module-info.java} and
 * {@code META-INF/services/com.guicedee.vertx.graphql.services.IGraphQLDataLoaderProvider}.</p>
 */
public interface IGraphQLDataLoaderProvider<J extends IGraphQLDataLoaderProvider<J>> extends IDefaultService<J>
{
    /**
     * Registers data loaders into the given registry.
     *
     * @param registry the data loader registry to populate
     */
    void configureDataLoaders(DataLoaderRegistry registry);
}

