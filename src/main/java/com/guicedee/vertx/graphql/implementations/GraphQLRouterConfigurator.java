package com.guicedee.vertx.graphql.implementations;

import com.guicedee.client.IGuiceContext;
import com.guicedee.vertx.graphql.GraphQLOptions;
import com.guicedee.vertx.graphql.services.IGraphQLDataLoaderProvider;
import com.guicedee.vertx.web.spi.VertxHttpServerOptionsConfigurator;
import com.guicedee.vertx.web.spi.VertxRouterConfigurator;
import graphql.GraphQL;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.graphql.GraphQLHandler;
import io.vertx.ext.web.handler.graphql.GraphQLHandlerOptions;
import io.vertx.ext.web.handler.graphql.GraphiQLHandler;
import io.vertx.ext.web.handler.graphql.GraphiQLHandlerOptions;
import io.vertx.ext.web.handler.graphql.ws.GraphQLWSHandler;
import org.dataloader.DataLoaderRegistry;

import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mounts GraphQL HTTP, WebSocket, and GraphiQL routes on the Vert.x {@link Router},
 * and adds the {@code graphql-transport-ws} WebSocket subprotocol to {@link HttpServerOptions}.
 *
 * <p>Route paths and feature toggles are driven by {@link GraphQLOptions} environment variables.</p>
 */
public class GraphQLRouterConfigurator
        implements VertxRouterConfigurator<GraphQLRouterConfigurator>, VertxHttpServerOptionsConfigurator
{
    private static final Logger log = Logger.getLogger("GraphQLRouterConfigurator");

    @Override
    public Integer sortOrder()
    {
        // After REST routes (which are at MIN_VALUE + 50-70 range), before general catch-all routes
        return Integer.MIN_VALUE + 100;
    }

    /**
     * Registers the GraphQL HTTP handler, optional WebSocket handler, and optional GraphiQL
     * handler on the router.
     *
     * @param router the router instance to configure
     * @return the same router instance
     */
    @Override
    public Router builder(Router router)
    {
        GraphQLOptions options = IGuiceContext.get(GraphQLOptions.class);
        GraphQL graphQL = IGuiceContext.get(GraphQL.class);
        Vertx vertx = IGuiceContext.get(Vertx.class);

        // WebSocket handler must be installed BEFORE the HTTP handler on the same path
        if (options.isWsEnabled())
        {
            try
            {
                GraphQLWSHandler wsHandler = GraphQLWSHandler.create(graphQL);
                router.route(options.getWsPath()).handler(wsHandler);
                log.info("GraphQL WebSocket handler mounted at " + options.getWsPath());
            }
            catch (Throwable t)
            {
                log.log(Level.SEVERE, "Failed to mount GraphQL WebSocket handler", t);
            }
        }

        // HTTP handler for queries and mutations
        GraphQLHandlerOptions handlerOptions = new GraphQLHandlerOptions()
                .setRequestBatchingEnabled(options.isBatchingEnabled())
                .setRequestMultipartEnabled(options.isUploadsEnabled());

        GraphQLHandler graphQLHandler = GraphQLHandler.builder(graphQL)
                .beforeExecute(builderWithContext -> {
                    DataLoaderRegistry registry = buildDataLoaderRegistry();
                    builderWithContext.builder().dataLoaderRegistry(registry);
                })
                .with(handlerOptions)
                .build();

        router.route(options.getHttpPath()).handler(graphQLHandler);
        log.info("GraphQL HTTP handler mounted at " + options.getHttpPath());

        // GraphiQL IDE (disabled by default for security)
        if (options.isGraphiqlEnabled())
        {
            try
            {
                GraphiQLHandlerOptions graphiqlOptions = new GraphiQLHandlerOptions()
                        .setEnabled(true);

                GraphiQLHandler graphiqlHandler = GraphiQLHandler.create(vertx, graphiqlOptions);
                router.route(options.getGraphiqlPath() + "*").subRouter(graphiqlHandler.router());
                log.info("GraphiQL IDE mounted at " + options.getGraphiqlPath());
            }
            catch (Throwable t)
            {
                log.log(Level.SEVERE, "Failed to mount GraphiQL handler", t);
            }
        }

        return router;
    }

    /**
     * Adds the {@code graphql-transport-ws} WebSocket subprotocol to the server options.
     *
     * @param options the current HTTP server options
     * @return the updated options
     */
    @Override
    public HttpServerOptions builder(HttpServerOptions options)
    {
        return options.addWebSocketSubProtocol("graphql-transport-ws");
    }

    /**
     * Builds a per-request {@link DataLoaderRegistry} from all SPI-contributed data loader providers.
     *
     * @return a fresh data loader registry
     */
    @SuppressWarnings("rawtypes")
    private DataLoaderRegistry buildDataLoaderRegistry()
    {
        DataLoaderRegistry registry = new DataLoaderRegistry();
        Set<IGraphQLDataLoaderProvider> providers = IGuiceContext.loaderToSet(
                ServiceLoader.load(IGraphQLDataLoaderProvider.class));
        for (IGraphQLDataLoaderProvider<?> provider : providers)
        {
            try
            {
                provider.configureDataLoaders(registry);
            }
            catch (Throwable t)
            {
                log.log(Level.WARNING, "Error configuring data loader from " + provider.getClass().getName(), t);
            }
        }
        return registry;
    }
}


