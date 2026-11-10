package fish.payara.cloud.connectors.kafka.outbound;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.resource.spi.ConnectionRequestInfo;
import javax.security.auth.Subject;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class KafkaManagedConnectionFactoryTest {

    @Test
    public void emptyPoolHasNoMatch() throws Exception {
        KafkaManagedConnectionFactory factory = new KafkaManagedConnectionFactory();

        assertNull(factory.matchManagedConnections(Set.of(), null, null));
    }

    @Test
    public void fatallyFailedConnectionIsNotMatched() throws Exception {
        KafkaManagedConnectionFactory factory = new KafkaManagedConnectionFactory();
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(
                    factory, null, null, properties -> producer);
            producer.sendException = new ProducerFencedException("Fenced");
            expectThrows(ProducerFencedException.class,
                    () -> connection.send(new ProducerRecord<>("orders", "value")));

            assertNull(factory.matchManagedConnections(Set.of(connection), null, null),
                    "A producer with a fatal error must not be reused");
        }
    }

    @Test
    public void compatibleConnectionMatchesEquivalentFactoryAndRequest() throws Exception {
        KafkaManagedConnectionFactory owner = factory("cluster-a:9092");
        KafkaManagedConnectionFactory requester = factory("cluster-a:9092");
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(
                    owner, subject("alice"), new RequestInfo("orders"), properties -> producer);

            assertSame(requester.matchManagedConnections(Set.of(connection), subject("alice"),
                    new RequestInfo("orders")), connection);
        }
    }

    @DataProvider(name = "incompatibleRequests")
    public Object[][] incompatibleRequests() {
        return new Object[][] {
            {"factory"}, {"subject"}, {"requestInfo"}, {"missingSubject"}, {"missingRequestInfo"},
            {"transactionMode"}
        };
    }

    @Test(dataProvider = "incompatibleRequests")
    public void incompatibleConnectionIsNotMatched(String difference) throws Exception {
        KafkaManagedConnectionFactory owner = factory("cluster-a:9092");
        KafkaManagedConnectionFactory requester = factory("cluster-a:9092");
        Subject requestedSubject = subject("alice");
        RequestInfo requestedInfo = new RequestInfo("orders");
        switch (difference) {
            case "factory" -> requester.setBootstrapServersConfig("cluster-b:9092");
            case "subject" -> requestedSubject = subject("bob");
            case "requestInfo" -> requestedInfo = new RequestInfo("payments");
            case "missingSubject" -> requestedSubject = null;
            case "missingRequestInfo" -> requestedInfo = null;
            case "transactionMode" -> requester.setTransactionIdPrefix("transactional");
            default -> throw new AssertionError(difference);
        }
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(
                    owner, subject("alice"), new RequestInfo("orders"), properties -> producer);

            assertNull(requester.matchManagedConnections(Set.of(connection), requestedSubject, requestedInfo));
        }
    }

    @DataProvider(name = "closeOperations")
    public Object[][] closeOperations() {
        return new Object[][] {
            {"destroy", false}, {"close", false}, {"destroy", true}, {"close", true}
        };
    }

    @Test(dataProvider = "closeOperations")
    public void closedConnectionIsNotMatchedEvenIfClosingFails(String operation, boolean closeFails) throws Exception {
        KafkaManagedConnectionFactory factory = new KafkaManagedConnectionFactory();
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(
                    factory, null, null, properties -> producer);
            if (closeFails) {
                producer.closeException = new IllegalStateException("Close failed");
            }
            try {
                if (closeFails) {
                    expectThrows(IllegalStateException.class, () -> close(connection, operation));
                } else {
                    close(connection, operation);
                }
                assertNull(factory.matchManagedConnections(Set.of(connection), null, null));
            } finally {
                producer.closeException = null;
            }
        }
    }

    @Test
    public void matchingSkipsIncompatibleAndUnrelatedCandidates() throws Exception {
        KafkaManagedConnectionFactory factory = factory("cluster-a:9092");
        try (MockProducer<String, String> incompatibleProducer = newProducer();
                MockProducer<String, String> compatibleProducer = newProducer()) {
            KafkaManagedConnection<String, String> incompatible = new KafkaManagedConnection<>(
                    factory("cluster-b:9092"), null, null, properties -> incompatibleProducer);
            KafkaManagedConnection<String, String> compatible = new KafkaManagedConnection<>(
                    factory, null, null, properties -> compatibleProducer);
            Set<Object> candidates = new LinkedHashSet<>();
            candidates.add(new Object());
            candidates.add(incompatible);
            candidates.add(compatible);

            assertSame(factory.matchManagedConnections(candidates, null, null), compatible);
            assertNull(factory.matchManagedConnections(Set.of(new Object()), null, null));
        }
    }

    @Test
    public void cleanupLeavesHealthyPhysicalConnectionReusable() throws Exception {
        KafkaManagedConnectionFactory factory = new KafkaManagedConnectionFactory();
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(
                    factory, null, null, properties -> producer);
            connection.getConnection(null, null);
            connection.cleanup();

            assertSame(factory.matchManagedConnections(Set.of(connection), null, null), connection);
        }
    }

    @Test
    public void asynchronousFatalFailureMakesConnectionIneligible() throws Exception {
        KafkaManagedConnectionFactory factory = new KafkaManagedConnectionFactory();
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(
                    factory, null, null, properties -> producer);
            connection.send(new ProducerRecord<>("orders", "value"));
            assertTrue(producer.errorNext(new ProducerFencedException("Fenced")));

            assertNull(factory.matchManagedConnections(Set.of(connection), null, null));
        }
    }

    @Test
    public void recoverableFailureDoesNotPreventReuse() throws Exception {
        KafkaManagedConnectionFactory factory = new KafkaManagedConnectionFactory();
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(
                    factory, null, null, properties -> producer);
            producer.sendException = new TimeoutException("Temporary timeout");
            expectThrows(TimeoutException.class,
                    () -> connection.send(new ProducerRecord<>("orders", "value")));

            assertSame(factory.matchManagedConnections(Set.of(connection), null, null), connection);
        }
    }

    private static KafkaManagedConnectionFactory factory(String bootstrapServers) {
        KafkaManagedConnectionFactory factory = new KafkaManagedConnectionFactory();
        factory.setBootstrapServersConfig(bootstrapServers);
        return factory;
    }

    private static MockProducer<String, String> newProducer() {
        return new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
    }

    private static Subject subject(String name) {
        return new Subject(true, Set.of(new UserPrincipal(name)), Set.of(), Set.of());
    }

    private static void close(KafkaManagedConnection<?, ?> connection, String operation) throws Exception {
        if (operation.equals("destroy")) {
            connection.destroy();
        } else {
            connection.close();
        }
    }

    private record RequestInfo(String destination) implements ConnectionRequestInfo { }

    private record UserPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
