package fish.payara.cloud.connectors.kafka.outbound;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.resource.ResourceException;
import javax.resource.spi.ConnectionEvent;
import javax.resource.spi.ConnectionEventListener;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class KafkaConnectionConcurrencyTest {

    @DataProvider(name = "invalidationMethods")
    public Object[][] invalidationMethods() {
        return new Object[][] {{"shutdown"}, {"cleanup"}};
    }

    @Test(dataProvider = "invalidationMethods")
    public void invalidationCannotEraseReassociationAfterReadingTheOldOwner(String method) throws Exception {
        ExecutorService executor = newExecutor();
        CountDownLatch ownerRead = new CountDownLatch(1);
        CountDownLatch continueShutdown = new CountDownLatch(1);
        try (MockProducer<String, String> originalProducer = newProducer();
                MockProducer<String, String> replacementProducer = newProducer()) {
            KafkaManagedConnection<String, String> original = new KafkaManagedConnection<>(originalProducer, false);
            KafkaManagedConnection<String, String> replacement = new KafkaManagedConnection<>(replacementProducer, false);
            KafkaConnectionImpl<String, String> handle = new KafkaConnectionImpl<>(original) {
                private final AtomicBoolean pauseFirstRead = new AtomicBoolean(true);

                @Override
                KafkaManagedConnection<String, String> getRealConn() {
                    KafkaManagedConnection<String, String> owner = super.getRealConn();
                    if (pauseFirstRead.compareAndSet(true, false)) {
                        ownerRead.countDown();
                        await(continueShutdown);
                    }
                    return owner;
                }
            };
            original.associateConnection(handle);
            var shutdown = executor.submit(() -> {
                if (method.equals("shutdown")) {
                    original.destroy();
                } else {
                    original.cleanup();
                }
                return null;
            });
            try {
                await(ownerRead);
                replacement.associateConnection(handle);
            } finally {
                continueShutdown.countDown();
            }
            shutdown.get(5, TimeUnit.SECONDS);

            assertSame(handle.getRealConn(), replacement, "Old-owner shutdown must not erase the new owner");
            replacement.destroy();
            assertNull(handle.getRealConn());
        } finally {
            continueShutdown.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void logicalCloseDetachesBeforeNotifyingListenersAndRejectsReassociation() throws Exception {
        ExecutorService executor = newExecutor();
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch finishListener = new CountDownLatch(1);
        try (MockProducer<String, String> originalProducer = newProducer();
                MockProducer<String, String> replacementProducer = newProducer()) {
            KafkaManagedConnection<String, String> original = new KafkaManagedConnection<>(originalProducer, false);
            KafkaManagedConnection<String, String> replacement = new KafkaManagedConnection<>(replacementProducer, false);
            var handle = (KafkaConnectionImpl<?, ?>) original.getConnection(null, null);
            original.addConnectionEventListener(new Listener() {
                @Override
                public void connectionClosed(ConnectionEvent event) {
                    listenerEntered.countDown();
                    await(finishListener);
                }
            });
            var close = executor.submit(() -> {
                handle.close();
                return null;
            });
            try {
                await(listenerEntered);
                assertNull(handle.getRealConn(), "A close listener must not see an open handle");
                expectThrows(ResourceException.class, () -> replacement.associateConnection(handle));
                expectThrows(ResourceException.class, handle::close);
            } finally {
                finishListener.countDown();
            }
            close.get(5, TimeUnit.SECONDS);
        } finally {
            finishListener.countDown();
            executor.shutdownNow();
        }
    }

    @Test(invocationCount = 5, threadPoolSize = 2)
    public void registrationReassociationAndShutdownStressTest() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3, task -> {
            Thread thread = new Thread(task, "connection-stress-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int iteration = 0; iteration < 500; iteration++) {
                try (CountingProducer originalProducer = new CountingProducer();
                        MockProducer<String, String> replacementProducer = newProducer()) {
                    KafkaManagedConnection<String, String> original = new KafkaManagedConnection<>(originalProducer, false);
                    KafkaManagedConnection<String, String> replacement = new KafkaManagedConnection<>(replacementProducer, false);
                    var handle = (KafkaConnectionImpl<?, ?>) original.getConnection(null, null);
                    CountDownLatch start = new CountDownLatch(1);
                    var registration = executor.submit(() -> {
                        await(start);
                        try {
                            return (KafkaConnectionImpl<?, ?>) original.getConnection(null, null);
                        } catch (ResourceException closed) {
                            return null;
                        }
                    });
                    var reassociation = executor.submit(() -> {
                        await(start);
                        replacement.associateConnection(handle);
                        return null;
                    });
                    var shutdown = executor.submit(() -> {
                        await(start);
                        original.destroy();
                        return null;
                    });
                    start.countDown();
                    var newHandle = registration.get(5, TimeUnit.SECONDS);
                    reassociation.get(5, TimeUnit.SECONDS);
                    shutdown.get(5, TimeUnit.SECONDS);

                    if (newHandle != null) {
                        assertNull(newHandle.getRealConn(), "Shutdown must invalidate a concurrently registered handle");
                    }
                    assertSame(handle.getRealConn(), replacement);
                    assertEquals(originalProducer.closeCalls.get(), 1);
                    replacement.destroy();
                    assertNull(handle.getRealConn());
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void concurrentLogicalClosesNotifyPoolOnlyOnce() throws Exception {
        ExecutorService executor = newExecutor();
        CountDownLatch start = new CountDownLatch(1);
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            var handle = (KafkaConnectionImpl<?, ?>) connection.getConnection(null, null);
            AtomicInteger notifications = new AtomicInteger();
            connection.addConnectionEventListener(new Listener() {
                @Override
                public void connectionClosed(ConnectionEvent event) { notifications.incrementAndGet(); }
            });
            Callable<Boolean> closeHandle = () -> {
                await(start);
                try {
                    handle.close();
                    return true;
                } catch (ResourceException alreadyClosed) {
                    return false;
                }
            };
            var first = executor.submit(closeHandle);
            var second = executor.submit(closeHandle);
            start.countDown();

            assertTrue(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS));
            assertEquals(notifications.get(), 1);
            assertNull(handle.getRealConn());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void fatalCallbackDuringProducerCloseCanReenterPoolShutdown() throws Exception {
        ExecutorService executor = newExecutor();
        AtomicBoolean deliverCallback = new AtomicBoolean(true);
        try (MockProducer<String, String> producer = new MockProducer<>(
                false, null, new StringSerializer(), new StringSerializer()) {
            @Override
            public void close(Duration timeout) {
                if (deliverCallback.compareAndSet(true, false)) {
                    var callback = executor.submit(() -> errorNext(new ProducerFencedException("Fenced during close")));
                    try {
                        assertTrue(callback.get(5, TimeUnit.SECONDS));
                    } catch (Exception exception) {
                        throw new AssertionError("Shutdown must not block its producer's callback thread", exception);
                    }
                }
                super.close(timeout);
            }
        }) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            AtomicInteger notifications = new AtomicInteger();
            connection.addConnectionEventListener(new Listener() {
                @Override
                public void connectionErrorOccurred(ConnectionEvent event) {
                    notifications.incrementAndGet();
                    try {
                        connection.destroy();
                    } catch (ResourceException exception) {
                        throw new AssertionError(exception);
                    }
                }
            });
            var result = connection.send(new ProducerRecord<>("orders", "value"));

            connection.destroy();

            assertTrue(result.isDone());
            assertEquals(notifications.get(), 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void concurrentFatalFailuresNotifyThePoolOnce() throws Exception {
        ExecutorService executor = newExecutor();
        CountDownLatch start = new CountDownLatch(1);
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            AtomicInteger notifications = new AtomicInteger();
            connection.addConnectionEventListener(new Listener() {
                @Override
                public void connectionErrorOccurred(ConnectionEvent event) { notifications.incrementAndGet(); }
            });
            Callable<Void> reportFailure = () -> {
                await(start);
                connection.reportProducerFailure(new ProducerFencedException("Fenced"));
                return null;
            };
            var first = executor.submit(reportFailure);
            var second = executor.submit(reportFailure);
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertEquals(notifications.get(), 1);
            expectThrows(ResourceException.class, () -> connection.getConnection(null, null));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static class CountingProducer extends MockProducer<String, String> {
        final AtomicInteger closeCalls = new AtomicInteger();

        CountingProducer() {
            super(false, null, new StringSerializer(), new StringSerializer());
        }

        @Override
        public void close(Duration timeout) {
            closeCalls.incrementAndGet();
            super.close(timeout);
        }
    }

    private static ExecutorService newExecutor() {
        return Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "connection-race-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static MockProducer<String, String> newProducer() {
        return new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out coordinating race test");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static class Listener implements ConnectionEventListener {
        @Override
        public void connectionClosed(ConnectionEvent event) { }
        @Override
        public void connectionErrorOccurred(ConnectionEvent event) { }
        @Override
        public void localTransactionStarted(ConnectionEvent event) { }
        @Override
        public void localTransactionCommitted(ConnectionEvent event) { }
        @Override
        public void localTransactionRolledback(ConnectionEvent event) { }
    }
}
