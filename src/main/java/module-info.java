import com.guicedee.client.services.lifecycle.IGuiceModule;
import com.guicedee.vertx.graphql.implementations.GraphQLModule;
import com.guicedee.vertx.graphql.implementations.GraphQLRouterConfigurator;
import com.guicedee.vertx.web.spi.VertxHttpServerOptionsConfigurator;
import com.guicedee.vertx.web.spi.VertxRouterConfigurator;

/**
 * GuicedEE GraphQL module — annotation-driven GraphQL integration using Vert.x Web GraphQL.
 *
 * <p>Provides HTTP, WebSocket, and GraphiQL endpoints, auto-configured with
 * {@link io.vertx.ext.web.handler.graphql.instrumentation.VertxFutureAdapter VertxFutureAdapter}
 * and {@link io.vertx.ext.web.handler.graphql.instrumentation.JsonObjectAdapter JsonObjectAdapter}
 * instrumentations.</p>
 *
 * <p>Optional GraphQL Gateway: when {@code com.guicedee.service.registry} is on the module path
 * and {@code GRAPHQL_GATEWAY_ENABLED=true}, remote services with {@code graphqlPath} metadata
 * are introspected, their schemas merged, and queries proxied to the originating service.</p>
 */
module com.guicedee.vertx.graphql {

    requires transitive com.guicedee.vertx.web;
    requires transitive com.guicedee.client;
    requires transitive io.vertx.web.graphql;
    requires transitive com.graphqljava;
    requires transitive org.dataloader;

    requires java.logging;
    requires static lombok;

    // Optional: gateway feature activates only if service-registry and web-client are present
    requires static com.guicedee.service.registry;
    requires static io.vertx.web.client;

    // Public SPI for schema and data loader contribution
    exports com.guicedee.vertx.graphql.services;
    // Public config
    exports com.guicedee.vertx.graphql;
    // Public gateway package
    exports com.guicedee.vertx.graphql.gateway;

    // SPI contracts this module discovers
    uses com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider;
    uses com.guicedee.vertx.graphql.services.IGraphQLDataLoaderProvider;

    // SPI implementations this module provides
    provides IGuiceModule with GraphQLModule;
    provides VertxRouterConfigurator with GraphQLRouterConfigurator;
    provides VertxHttpServerOptionsConfigurator with GraphQLRouterConfigurator;
    provides com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider
        with com.guicedee.vertx.graphql.gateway.RemoteGraphQLSchemaProvider;

    // Open implementation packages for Guice injection
    opens com.guicedee.vertx.graphql to com.google.guice;
    opens com.guicedee.vertx.graphql.implementations to com.google.guice;
    opens com.guicedee.vertx.graphql.gateway to com.google.guice;
}
