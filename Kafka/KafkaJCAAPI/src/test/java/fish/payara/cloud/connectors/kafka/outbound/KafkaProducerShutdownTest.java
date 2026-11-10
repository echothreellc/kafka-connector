package fish.payara.cloud.connectors.kafka.outbound;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.resource.ResourceException;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class KafkaProducerShutdownTest {

    @DataProvider(name = "shutdownMethods")
    public Object[][] shutdownMethods() {
        return new Object[][] {{"destroy"}, {"close"}};
    }

    @Test(dataProvider = "shutdownMethods")
    public void shutdownUsesExplicitCloseTimeout(String method) throws Exception {
        try (ClosingProducer producer = new ClosingProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);

            shutdown(connection, method);

            assertEquals(producer.unboundedCloseCalls, 0, "Shutdown must not use Kafka's unbounded close()");
            assertEquals(producer.closeCalls, 1);
            assertEquals(producer.closeTimeout, Duration.ofSeconds(30));
        }
    }

    @Test(dataProvider = "shutdownMethods")
    public void shutdownInvalidatesOutstandingHandles(String method) throws Exception {
        try (ClosingProducer producer = new ClosingProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            var first = (KafkaConnectionImpl<?, ?>) connection.getConnection(null, null);
            var second = (KafkaConnectionImpl<?, ?>) connection.getConnection(null, null);

            shutdown(connection, method);

            assertNull(first.getRealConn(), "Shutdown must detach existing connection handles");
            assertNull(second.getRealConn());
            expectThrows(ResourceException.class, first::flush);
            expectThrows(ResourceException.class, () -> connection.getConnection(null, null));
        }
    }

    @Test
    public void repeatedShutdownClosesProducerOnlyOnce() throws Exception {
        try (ClosingProducer producer = new ClosingProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);

            connection.destroy();
            connection.close();
            connection.destroy();

            assertEquals(producer.closeCalls, 1);
        }
    }

    @Test(dataProvider = "shutdownMethods")
    public void closeFailureStillInvalidatesHandlesAndRepeatedShutdownIsSafe(String method) throws Exception {
        try (ClosingProducer producer = new ClosingProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            var handle = (KafkaConnectionImpl<?, ?>) connection.getConnection(null, null);
            IllegalStateException failure = new IllegalStateException("Close failed");
            producer.closeException = failure;
            try {
                ResourceException thrown = expectThrows(ResourceException.class, () -> shutdown(connection, method));

                assertSame(thrown.getCause(), failure);
                assertNull(handle.getRealConn());
                expectThrows(ResourceException.class, handle::flush);
                expectThrows(ResourceException.class, () -> connection.getConnection(null, null));
                connection.close();
                connection.destroy();
                assertEquals(producer.closeCalls, 1);
            } finally {
                producer.closeException = null;
            }
        }
    }

    @Test
    public void concurrentShutdownDoesNotCloseTwiceOrWaitForTheClosingThread() throws Exception {
        var executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "producer-shutdown-test");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch finishClose = new CountDownLatch(1);
        try (ClosingProducer producer = new ClosingProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            var handle = (KafkaConnectionImpl<?, ?>) connection.getConnection(null, null);
            producer.duringClose = () -> {
                closeStarted.countDown();
                try {
                    assertTrue(finishClose.await(5, TimeUnit.SECONDS), "Test did not release the closing producer");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            };
            var firstShutdown = executor.submit(() -> {
                connection.destroy();
                return null;
            });
            try {
                assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
                var secondShutdown = executor.submit(() -> {
                    connection.close();
                    return null;
                });
                secondShutdown.get(2, TimeUnit.SECONDS);
                assertNull(handle.getRealConn());
                expectThrows(ResourceException.class, () -> connection.getConnection(null, null));
                expectThrows(ResourceException.class, () -> connection.associateConnection(handle));
            } finally {
                finishClose.countDown();
                producer.duringClose = null;
            }
            firstShutdown.get(5, TimeUnit.SECONDS);
            assertEquals(producer.closeCalls, 1);
        } finally {
            finishClose.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void shutdownDoesNotDetachAHandleReassociatedWithAnotherConnection() throws Exception {
        try (ClosingProducer originalProducer = new ClosingProducer();
                ClosingProducer replacementProducer = new ClosingProducer()) {
            KafkaManagedConnection<String, String> original = new KafkaManagedConnection<>(originalProducer, false);
            KafkaManagedConnection<String, String> replacement = new KafkaManagedConnection<>(replacementProducer, false);
            var handle = (KafkaConnectionImpl<?, ?>) original.getConnection(null, null);
            replacement.associateConnection(handle);

            original.destroy();

            assertSame(handle.getRealConn(), replacement);
            replacement.destroy();
            assertNull(handle.getRealConn());
        }
    }

    @Test(dataProvider = "shutdownMethods")
    public void shutdownDoesNotAddSeparateFlushOrRollbackWaits(String method) throws Exception {
        try (ClosingProducer producer = new ClosingProducer()) {
            producer.initTransactions();
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, true);
            var transaction = connection.getLocalTransaction();
            transaction.begin();

            shutdown(connection, method);

            assertEquals(producer.flushCalls, 0);
            assertEquals(producer.abortCalls, 0);
            assertEquals(producer.closeTimeout, Duration.ofSeconds(30));
            expectThrows(ResourceException.class, transaction::commit);
        }
    }

    @Test
    public void directOperationsAfterShutdownFailClearlyInsteadOfDereferencingNull() throws Exception {
        try (ClosingProducer producer = new ClosingProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            connection.close();

            expectThrows(IllegalStateException.class, () -> connection.send(new ProducerRecord<>("orders", "value")));
            expectThrows(IllegalStateException.class, connection::flush);
            expectThrows(IllegalStateException.class, () -> connection.partitionsFor("orders"));
            expectThrows(IllegalStateException.class, connection::metrics);
            connection.cleanup();
        }
    }

    private static void shutdown(KafkaManagedConnection<?, ?> connection, String method) throws Exception {
        if (method.equals("destroy")) {
            connection.destroy();
        } else {
            connection.close();
        }
    }

    private static class ClosingProducer extends MockProducer<String, String> {
        int unboundedCloseCalls;
        int closeCalls;
        Duration closeTimeout;
        int flushCalls;
        int abortCalls;
        volatile Runnable duringClose;

        ClosingProducer() {
            super(false, null, new StringSerializer(), new StringSerializer());
        }

        @Override
        public void close() {
            unboundedCloseCalls++;
            super.close();
        }

        @Override
        public void close(Duration timeout) {
            closeCalls++;
            closeTimeout = timeout;
            Runnable action = duringClose;
            if (action != null) {
                action.run();
            }
            super.close(timeout);
        }

        @Override
        public void flush() {
            flushCalls++;
            super.flush();
        }

        @Override
        public void abortTransaction() {
            abortCalls++;
            super.abortTransaction();
        }
    }
}
