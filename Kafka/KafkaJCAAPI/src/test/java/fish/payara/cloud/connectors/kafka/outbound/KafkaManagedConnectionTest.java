package fish.payara.cloud.connectors.kafka.outbound;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import javax.resource.ResourceException;
import javax.resource.spi.ConnectionEvent;
import javax.resource.spi.ConnectionEventListener;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.InvalidProducerEpochException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.TransactionalIdAuthorizationException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class KafkaManagedConnectionTest {

    @DataProvider(name = "initializationFailures")
    public Object[][] initializationFailures() {
        return new Object[][] {
            {new TimeoutException("Transaction initialization timed out")},
            {new ProducerFencedException("Producer was fenced during initialization")}
        };
    }

    @Test(dataProvider = "initializationFailures")
    public void failedTransactionInitializationClosesProducer(RuntimeException failure) {
        InitializationTrackingProducer producer = new InitializationTrackingProducer();
        producer.initTransactionException = failure;
        try {
            ResourceException thrown = expectThrows(ResourceException.class,
                    () -> new KafkaManagedConnection<>(new Properties(), "test", true, "", properties -> producer));

            assertTrue(producer.closed(), "A producer must not leak when transaction initialization fails");
            assertEquals(producer.closeTimeout, Duration.ZERO);
            assertSame(thrown.getCause(), failure);
        } finally {
            producer.close();
        }
    }

    @Test
    public void initializationFailureIsPreservedWhenCloseAlsoFails() {
        IllegalStateException closeFailure = new IllegalStateException("Close failed");
        InitializationTrackingProducer producer = new InitializationTrackingProducer() {
            @Override
            public void close(Duration timeout) {
                super.close(timeout);
                throw closeFailure;
            }
        };
        TimeoutException initializationFailure = new TimeoutException("Transaction initialization timed out");
        producer.initTransactionException = initializationFailure;

        ResourceException thrown = expectThrows(ResourceException.class,
                () -> new KafkaManagedConnection<>(new Properties(), "test", true, "", properties -> producer));

        assertSame(thrown.getCause(), initializationFailure);
        assertEquals(thrown.getSuppressed().length, 1);
        assertSame(thrown.getSuppressed()[0], closeFailure);
        assertEquals(producer.closeTimeout, Duration.ZERO);
    }

    @DataProvider(name = "transactionModes")
    public Object[][] transactionModes() {
        return new Object[][] {{true}, {false}};
    }

    @Test(dataProvider = "transactionModes")
    public void successfulConstructionKeepsProducerOpen(boolean transactional) throws Exception {
        InitializationTrackingProducer producer = new InitializationTrackingProducer();
        KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(
                new Properties(), "test", transactional, "", properties -> producer);
        try {
            assertEquals(producer.initializationCalls, transactional ? 1 : 0);
            assertEquals(producer.transactionInitialized(), transactional);
            assertFalse(producer.closed());
        } finally {
            connection.destroy();
        }
        assertTrue(producer.closed());
    }

    @DataProvider(name = "fatalFailures")
    public Object[][] fatalFailures() {
        return new Object[][] {
            {new ProducerFencedException("Fenced"), false},
            {new InvalidProducerEpochException("Old epoch"), false},
            {new UnsupportedVersionException("Unsupported broker"), false},
            {new TransactionalIdAuthorizationException("Unauthorized transaction"), true},
            {new ClusterAuthorizationException("Unauthorized producer"), false},
            {new TopicAuthorizationException("orders"), true},
            {new OutOfOrderSequenceException("Out of order"), true},
            {new KafkaException("Producer is defunct", new ProducerFencedException("Fenced")), true}
        };
    }

    @Test(dataProvider = "fatalFailures")
    public void synchronousFatalSendNotifiesPool(RuntimeException failure, boolean transactional) {
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, transactional);
            PoolListener pool = new PoolListener();
            connection.addConnectionEventListener(pool);
            producer.sendException = failure;

            RuntimeException thrown = expectThrows(RuntimeException.class,
                    () -> connection.send(new ProducerRecord<>("orders", "value")));

            assertSame(thrown, failure);
            assertPoolError(pool, connection, failure);
        }
    }

    @Test
    public void asynchronousFatalSendPreservesCallerCallback() {
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            PoolListener pool = new PoolListener();
            connection.addConnectionEventListener(pool);
            AtomicReference<Exception> callbackFailure = new AtomicReference<>();
            ProducerFencedException failure = new ProducerFencedException("Fenced");

            var result = connection.send(new ProducerRecord<>("orders", "value"), (metadata, exception) -> {
                assertPoolError(pool, connection, failure);
                callbackFailure.set(exception);
            });
            assertTrue(producer.errorNext(failure));

            assertSame(callbackFailure.get(), failure);
            assertSame(expectThrows(ExecutionException.class, result::get).getCause(), failure);
        }
    }

    @DataProvider(name = "recordFailures")
    public Object[][] recordFailures() {
        return new Object[][] {
            {new TimeoutException("Timed out")},
            {new SerializationException("Bad record")},
            {new RecordTooLargeException("Record too large")},
            {new TopicAuthorizationException("orders")},
            {new OutOfOrderSequenceException("Nontransactional producer can recover")}
        };
    }

    @Test(dataProvider = "recordFailures")
    public void recordFailureDoesNotDiscardNontransactionalProducer(RuntimeException failure) {
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            PoolListener pool = new PoolListener();
            connection.addConnectionEventListener(pool);
            producer.sendException = failure;
            assertSame(expectThrows(RuntimeException.class,
                    () -> connection.send(new ProducerRecord<>("orders", "value"))), failure);

            producer.sendException = null;
            AtomicReference<Exception> callbackFailure = new AtomicReference<>();
            var failedResult = connection.send(new ProducerRecord<>("orders", "value"),
                    (metadata, exception) -> callbackFailure.set(exception));
            assertTrue(producer.errorNext(failure));
            assertSame(expectThrows(ExecutionException.class, failedResult::get).getCause(), failure);
            assertSame(callbackFailure.get(), failure);

            var successfulResult = connection.send(new ProducerRecord<>("orders", "valid"));
            assertTrue(producer.completeNext());
            assertTrue(successfulResult.isDone());
            assertTrue(pool.errors.isEmpty(), "A record-level error must not discard the producer");
        }
    }

    @Test
    public void successfulSendPreservesMetadataAndCallback() throws Exception {
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            PoolListener pool = new PoolListener();
            connection.addConnectionEventListener(pool);
            AtomicReference<RecordMetadata> callbackMetadata = new AtomicReference<>();
            var result = connection.send(new ProducerRecord<>("orders", "value"), (metadata, exception) -> {
                assertNull(exception);
                callbackMetadata.set(metadata);
            });

            assertTrue(producer.completeNext());
            assertNotNull(callbackMetadata.get());
            assertEquals(callbackMetadata.get().topic(), result.get().topic());
            assertEquals(callbackMetadata.get().partition(), result.get().partition());
            assertEquals(callbackMetadata.get().offset(), result.get().offset());
            assertTrue(pool.errors.isEmpty());
        }
    }

    @Test
    public void repeatedFatalFailuresNotifyPoolOnlyOnce() {
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            PoolListener pool = new PoolListener();
            connection.addConnectionEventListener(pool);
            ProducerFencedException failure = new ProducerFencedException("Fenced");
            var result = connection.send(new ProducerRecord<>("orders", "value"));
            assertTrue(producer.errorNext(failure));
            assertTrue(result.isDone());
            producer.sendException = new KafkaException("Producer is defunct", failure);

            expectThrows(KafkaException.class, () -> connection.send(new ProducerRecord<>("orders", "value")));

            assertPoolError(pool, connection, failure);
        }
    }

    @Test
    public void listenerCanRemoveItselfWithoutHidingFailureOrCallback() {
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            connection.addConnectionEventListener(new PoolListener() {
                @Override
                public void connectionErrorOccurred(ConnectionEvent event) {
                    connection.removeConnectionEventListener(this);
                    throw new IllegalStateException("Listener failed");
                }
            });
            PoolListener pool = new PoolListener();
            connection.addConnectionEventListener(pool);
            AtomicReference<Exception> callbackFailure = new AtomicReference<>();
            ProducerFencedException failure = new ProducerFencedException("Fenced");

            connection.send(new ProducerRecord<>("orders", "value"),
                    (metadata, exception) -> callbackFailure.set(exception));
            assertTrue(producer.errorNext(failure));

            assertPoolError(pool, connection, failure);
            assertSame(callbackFailure.get(), failure);
        }
    }

    @DataProvider(name = "transactionOperations")
    public Object[][] transactionOperations() {
        return new Object[][] {{"begin"}, {"commit"}, {"rollback"}};
    }

    @Test(dataProvider = "transactionOperations")
    public void fatalTransactionFailureNotifiesPool(String operation) {
        try (MockProducer<String, String> producer = newProducer()) {
            producer.initTransactions();
            producer.beginTransaction();
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, true);
            PoolListener pool = new PoolListener();
            connection.addConnectionEventListener(pool);
            ProducerFencedException failure = new ProducerFencedException("Fenced");
            producer.beginTransactionException = failure;
            producer.commitTransactionException = failure;
            producer.abortTransactionException = failure;

            RuntimeException thrown = expectThrows(RuntimeException.class, () -> {
                switch (operation) {
                    case "begin" -> connection.getLocalTransaction().begin();
                    case "commit" -> connection.getLocalTransaction().commit();
                    case "rollback" -> connection.getLocalTransaction().rollback();
                    default -> throw new AssertionError(operation);
                }
            });

            assertSame(thrown, failure);
            assertPoolError(pool, connection, failure);
        }
    }

    @DataProvider(name = "producerOperations")
    public Object[][] producerOperations() {
        return new Object[][] {{"flush"}, {"partitionsFor"}};
    }

    @Test(dataProvider = "producerOperations")
    public void fatalFailureFromFlushOrMetadataLookupNotifiesPool(String operation) {
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            PoolListener pool = new PoolListener();
            connection.addConnectionEventListener(pool);
            ProducerFencedException failure = new ProducerFencedException("Fenced");
            producer.flushException = failure;
            producer.partitionsForException = failure;

            RuntimeException thrown = expectThrows(RuntimeException.class, () -> {
                if (operation.equals("flush")) {
                    connection.flush();
                } else {
                    connection.partitionsFor("orders");
                }
            });
            assertSame(thrown, failure);
            assertPoolError(pool, connection, failure);
        }
    }

    @Test(dataProvider = "fatalFailures")
    public void asynchronousFatalSendWithoutCallerCallbackNotifiesPool(RuntimeException failure, boolean transactional) {
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, transactional);
            PoolListener pool = new PoolListener();
            connection.addConnectionEventListener(pool);

            var result = connection.send(new ProducerRecord<>("orders", "value"));
            assertTrue(producer.errorNext(failure));

            ExecutionException thrown = expectThrows(ExecutionException.class, result::get);
            assertSame(thrown.getCause(), failure);
            assertPoolError(pool, connection, failure);
        }
    }

    private static MockProducer<String, String> newProducer() {
        return new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
    }

    private static class InitializationTrackingProducer extends MockProducer<String, String> {
        private int initializationCalls;
        private Duration closeTimeout;

        InitializationTrackingProducer() {
            super(false, null, new StringSerializer(), new StringSerializer());
        }

        @Override
        public void initTransactions() {
            initializationCalls++;
            super.initTransactions();
        }

        @Override
        public void close(Duration timeout) {
            closeTimeout = timeout;
            super.close(timeout);
        }
    }

    private static void assertPoolError(PoolListener pool, KafkaManagedConnection<?, ?> connection,
            Exception failure) {
        assertEquals(pool.errors.size(), 1, "The pool must be told to discard the failed producer");
        ConnectionEvent event = pool.errors.get(0);
        assertEquals(event.getId(), ConnectionEvent.CONNECTION_ERROR_OCCURRED);
        assertSame(event.getSource(), connection);
        assertSame(event.getException(), failure);
    }

    private static class PoolListener implements ConnectionEventListener {
        private final List<ConnectionEvent> errors = new ArrayList<>();

        @Override
        public void connectionErrorOccurred(ConnectionEvent event) {
            errors.add(event);
        }

        @Override
        public void connectionClosed(ConnectionEvent event) { }

        @Override
        public void localTransactionStarted(ConnectionEvent event) { }

        @Override
        public void localTransactionCommitted(ConnectionEvent event) { }

        @Override
        public void localTransactionRolledback(ConnectionEvent event) { }
    }
}
