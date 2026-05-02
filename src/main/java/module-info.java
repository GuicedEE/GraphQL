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
 */
module com.guicedee.vertx.graphql {

    requires transitive com.guicedee.vertx.web;
    requires transitive com.guicedee.client;
    requires transitive io.vertx.web.graphql;
    requires transitive com.graphqljava;
    requires transitive org.dataloader;

    requires java.logging;
    requires static lombok;

    // Public SPI for schema and data loader contribution
    exports com.guicedee.vertx.graphql.services;
    // Public config
    exports com.guicedee.vertx.graphql;

    // SPI contracts this module discovers
    uses com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider;
    uses com.guicedee.vertx.graphql.services.IGraphQLDataLoaderProvider;

    // SPI implementations this module provides
    provides IGuiceModule with GraphQLModule;
    provides VertxRouterConfigurator with GraphQLRouterConfigurator;
    provides VertxHttpServerOptionsConfigurator with GraphQLRouterConfigurator;

    // Open implementation packages for Guice injection
    opens com.guicedee.vertx.graphql to com.google.guice;
    opens com.guicedee.vertx.graphql.implementations to com.google.guice;
}



