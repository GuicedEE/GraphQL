import com.guicedee.client.services.lifecycle.IGuiceModule;
import com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider;
import com.guicedee.vertx.graphql.test.GraphQLTestModule;
import com.guicedee.vertx.graphql.test.TestSchemaProvider;

module guiced.graphql.test {

    requires com.guicedee.vertx.graphql;
    requires com.guicedee.service.registry;

    requires java.net.http;

    requires org.junit.jupiter.api;

    requires com.google.guice;
    requires com.guicedee.client;
    requires com.graphqljava;

    provides IGuiceModule with GraphQLTestModule;
    provides IGraphQLSchemaProvider with TestSchemaProvider;

    opens com.guicedee.vertx.graphql.test to org.junit.platform.commons, com.google.guice, com.fasterxml.jackson.databind;
}
