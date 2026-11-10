package fish.payara.cloud.connectors.kafka.outbound;

import javax.resource.ResourceException;
import javax.resource.NotSupportedException;
import javax.resource.spi.ConnectionEvent;
import javax.resource.spi.ConnectionEventListener;
import javax.resource.spi.LocalTransaction;
import javax.resource.spi.TransactionSupport.TransactionSupportLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class KafkaTransactionLifecycleTest {

    @Test
    public void cleanupRollsBackUnfinishedTransactionBeforeReuse() throws Exception {
        try (MockProducer<String, String> producer = newProducer()) {
            producer.initTransactions();
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, true);
            var transaction = connection.getLocalTransaction();
            transaction.begin();
            connection.send(new ProducerRecord<>("orders", "old borrower's record"));

            connection.cleanup();

            assertTrue(producer.transactionAborted(), "Pool cleanup must abort the previous borrower's transaction");
            transaction.begin();
            connection.send(new ProducerRecord<>("orders", "new borrower's record"));
            transaction.commit();
            assertEquals(producer.history().size(), 1);
            assertTrue(producer.history().stream().allMatch(record -> record.value().equals("new borrower's record")));
        }
    }

    @Test
    public void successfulTransactionsCanBeFollowedByAnotherTransaction() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.transaction.begin();
            fixture.transaction.commit();
            fixture.transaction.begin();
            fixture.transaction.rollback();
            fixture.transaction.begin();
            fixture.transaction.commit();

            assertEquals(fixture.producer.beginCalls, 3);
            assertEquals(fixture.producer.commitCalls, 2);
            assertEquals(fixture.producer.abortCalls, 1);
            fixture.assertReusable();
            assertTrue(fixture.errors.isEmpty());
        }
    }

    @Test
    public void invalidOperationsAreRejectedBeforeCallingKafka() throws Exception {
        try (Fixture fixture = new Fixture()) {
            expectThrows(ResourceException.class, fixture.transaction::commit);
            expectThrows(ResourceException.class, fixture.transaction::rollback);
            assertEquals(fixture.producer.commitCalls, 0);
            assertEquals(fixture.producer.abortCalls, 0);

            fixture.transaction.begin();
            expectThrows(ResourceException.class, fixture.transaction::begin);
            assertEquals(fixture.producer.beginCalls, 1);
            fixture.transaction.rollback();
            fixture.assertReusable();
        }
    }

    @DataProvider(name = "uncertainCompletionFailures")
    public Object[][] uncertainCompletionFailures() {
        return new Object[][] {
            {new TimeoutException("Outcome unknown")},
            {new InterruptException(new InterruptedException("Operation interrupted"))}
        };
    }

    @Test(dataProvider = "uncertainCompletionFailures")
    public void uncertainCommitAllowsOnlyCommitRetry(RuntimeException failure) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.transaction.begin();
            fixture.producer.commitTransactionException = failure;
            assertSame(expectThrows(ResourceException.class, fixture.transaction::commit).getCause(), failure);

            fixture.assertNotReusable();
            expectThrows(ResourceException.class, fixture.transaction::rollback);
            expectThrows(ResourceException.class, fixture.transaction::begin);
            assertEquals(fixture.producer.abortCalls, 0);

            fixture.producer.commitTransactionException = null;
            fixture.transaction.commit();
            assertEquals(fixture.producer.commitCalls, 2);
            fixture.assertReusable();
            assertTrue(fixture.errors.isEmpty());
        }
    }

    @Test(dataProvider = "uncertainCompletionFailures")
    public void uncertainRollbackAllowsOnlyRollbackRetry(RuntimeException failure) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.transaction.begin();
            fixture.producer.abortTransactionException = failure;
            assertSame(expectThrows(ResourceException.class, fixture.transaction::rollback).getCause(), failure);

            fixture.assertNotReusable();
            expectThrows(ResourceException.class, fixture.transaction::commit);
            expectThrows(ResourceException.class, fixture.transaction::begin);
            assertEquals(fixture.producer.commitCalls, 0);

            fixture.producer.abortTransactionException = null;
            fixture.transaction.rollback();
            assertEquals(fixture.producer.abortCalls, 2);
            fixture.assertReusable();
            assertTrue(fixture.errors.isEmpty());
        }
    }

    @Test
    public void cleanupDiscardsUnknownCommitWithoutAttemptingRollback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var handle = (KafkaConnectionImpl<?, ?>) fixture.connection.getConnection(null, null);
            fixture.transaction.begin();
            fixture.producer.commitTransactionException = new TimeoutException("Commit outcome unknown");
            expectThrows(ResourceException.class, fixture.transaction::commit);

            expectThrows(ResourceException.class, fixture.connection::cleanup);

            assertEquals(fixture.producer.abortCalls, 0);
            assertNull(handle.getRealConn(), "Old handles must be invalidated even when cleanup fails");
            assertEquals(fixture.errors.size(), 1);
            fixture.assertNotReusable();
            expectThrows(ResourceException.class, () -> fixture.connection.getConnection(null, null));
            expectThrows(ResourceException.class, fixture.transaction::commit);
        }
    }

    @Test
    public void cleanupDiscardsConnectionIfRollbackStillFails() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var handle = (KafkaConnectionImpl<?, ?>) fixture.connection.getConnection(null, null);
            fixture.transaction.begin();
            TimeoutException failure = new TimeoutException("Abort outcome unknown");
            fixture.producer.abortTransactionException = failure;
            expectThrows(ResourceException.class, fixture.transaction::rollback);

            ResourceException thrown = expectThrows(ResourceException.class, fixture.connection::cleanup);

            assertSame(thrown.getCause(), failure);
            assertEquals(fixture.producer.abortCalls, 2);
            assertNull(handle.getRealConn());
            assertEquals(fixture.errors.size(), 1);
            fixture.assertNotReusable();
            expectThrows(ResourceException.class, fixture.transaction::begin);
        }
    }

    @Test
    public void abortableCommitFailureRequiresRollbackBeforeReuse() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.transaction.begin();
            KafkaException failure = new KafkaException("Transaction must be aborted");
            fixture.producer.commitTransactionException = failure;
            assertSame(expectThrows(ResourceException.class, fixture.transaction::commit).getCause(), failure);
            expectThrows(ResourceException.class, fixture.transaction::commit);
            expectThrows(ResourceException.class, fixture.transaction::begin);
            fixture.assertNotReusable();

            fixture.connection.cleanup();

            assertEquals(fixture.producer.abortCalls, 1);
            fixture.assertReusable();
            assertTrue(fixture.errors.isEmpty());
        }
    }

    @DataProvider(name = "failedOperations")
    public Object[][] failedOperations() {
        return new Object[][] {{"begin"}, {"rollback"}};
    }

    @Test(dataProvider = "failedOperations")
    public void unrecoverableOperationFailureDiscardsConnection(String operation) throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProducerFencedException failure = new ProducerFencedException("Fenced");
            if (operation.equals("rollback")) {
                fixture.transaction.begin();
                fixture.producer.abortTransactionException = failure;
            } else {
                fixture.producer.beginTransactionException = failure;
            }
            ResourceException thrown = expectThrows(ResourceException.class, () -> {
                if (operation.equals("begin")) {
                    fixture.transaction.begin();
                } else {
                    fixture.transaction.rollback();
                }
            });

            assertSame(thrown.getCause(), failure);
            assertEquals(fixture.errors.size(), 1);
            assertSame(fixture.errors.get(0).getException(), failure);
            fixture.assertNotReusable();
            expectThrows(ResourceException.class, fixture.transaction::begin);
        }
    }

    @Test
    public void activeTransactionIsNotMatchedUntilCleanupCompletes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.transaction.begin();
            fixture.assertNotReusable();

            fixture.connection.cleanup();

            assertTrue(fixture.producer.transactionAborted());
            fixture.assertReusable();
            fixture.connection.cleanup();
            assertEquals(fixture.producer.abortCalls, 1, "Idle cleanup must not abort again");
        }
    }

    @Test
    public void nontransactionalConnectionRejectsTransactionAccess() {
        try (MockProducer<String, String> producer = newProducer()) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, false);
            expectThrows(NotSupportedException.class, connection::getLocalTransaction);
        }
    }

    @Test
    public void retainedTransactionCannotBeUsedAfterConnectionIsDestroyed() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.connection.destroy();

            expectThrows(ResourceException.class, fixture.transaction::begin);
            expectThrows(ResourceException.class, fixture.connection::getLocalTransaction);
            assertEquals(fixture.producer.beginCalls, 0);
        }
    }

    @Test
    public void cleanupDuringCommitDoesNotDeadlockAndPreventsReuse() throws Exception {
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "pool-cleanup-test");
            thread.setDaemon(true);
            return thread;
        });
        try (Fixture fixture = new Fixture()) {
            fixture.transaction.begin();
            fixture.producer.duringCommit = () -> {
                var cleanup = executor.submit(() -> expectThrows(ResourceException.class, fixture.connection::cleanup));
                try {
                    cleanup.get(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError("Pool cleanup must not block on the committing thread", exception);
                }
            };

            fixture.transaction.commit();

            assertEquals(fixture.errors.size(), 1);
            assertEquals(fixture.producer.abortCalls, 0);
            fixture.assertNotReusable();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void overlappingTransactionOperationsAreRejectedWithoutDisturbingCommit() throws Exception {
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "transaction-operation-test");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch commitStarted = new CountDownLatch(1);
        CountDownLatch finishCommit = new CountDownLatch(1);
        try (Fixture fixture = new Fixture()) {
            fixture.transaction.begin();
            fixture.producer.duringCommit = () -> {
                commitStarted.countDown();
                try {
                    assertTrue(finishCommit.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            };
            var commit = executor.submit(() -> {
                fixture.transaction.commit();
                return null;
            });
            try {
                assertTrue(commitStarted.await(5, TimeUnit.SECONDS));
                expectThrows(ResourceException.class, fixture.transaction::begin);
                expectThrows(ResourceException.class, fixture.transaction::commit);
                expectThrows(ResourceException.class, fixture.transaction::rollback);
                fixture.assertNotReusable();
            } finally {
                finishCommit.countDown();
            }
            commit.get(5, TimeUnit.SECONDS);
            assertEquals(fixture.producer.beginCalls, 1);
            assertEquals(fixture.producer.commitCalls, 1);
            assertEquals(fixture.producer.abortCalls, 0);
            fixture.assertReusable();
            assertTrue(fixture.errors.isEmpty());
        } finally {
            finishCommit.countDown();
            executor.shutdownNow();
        }
    }

    private static class Fixture implements AutoCloseable {
        final TrackingProducer producer = new TrackingProducer();
        final KafkaManagedConnectionFactory factory = new KafkaManagedConnectionFactory() {
            @Override
            public TransactionSupportLevel getTransactionSupport() {
                return TransactionSupportLevel.LocalTransaction;
            }
        };
        final KafkaManagedConnection<String, String> connection;
        final LocalTransaction transaction;
        final List<ConnectionEvent> errors = new ArrayList<>();

        Fixture() throws ResourceException {
            connection = new KafkaManagedConnection<>(factory, null, null, properties -> producer);
            transaction = connection.getLocalTransaction();
            connection.addConnectionEventListener(new ConnectionEventListener() {
                @Override
                public void connectionErrorOccurred(ConnectionEvent event) { errors.add(event); }
                @Override
                public void connectionClosed(ConnectionEvent event) { }
                @Override
                public void localTransactionStarted(ConnectionEvent event) { }
                @Override
                public void localTransactionCommitted(ConnectionEvent event) { }
                @Override
                public void localTransactionRolledback(ConnectionEvent event) { }
            });
        }

        void assertReusable() throws ResourceException {
            assertSame(factory.matchManagedConnections(Set.of(connection), null, null), connection);
        }

        void assertNotReusable() throws ResourceException {
            assertNull(factory.matchManagedConnections(Set.of(connection), null, null));
        }

        @Override
        public void close() {
            producer.close();
        }
    }

    private static class TrackingProducer extends MockProducer<String, String> {
        int beginCalls;
        int commitCalls;
        int abortCalls;
        Runnable duringCommit;

        TrackingProducer() {
            super(true, null, new StringSerializer(), new StringSerializer());
        }

        @Override
        public void beginTransaction() {
            beginCalls++;
            super.beginTransaction();
        }

        @Override
        public void commitTransaction() {
            commitCalls++;
            if (duringCommit != null) {
                duringCommit.run();
            }
            super.commitTransaction();
        }

        @Override
        public void abortTransaction() {
            abortCalls++;
            super.abortTransaction();
        }
    }

    @Test
    public void transactionFailureIsTranslatedToResourceException() throws Exception {
        try (MockProducer<String, String> producer = newProducer()) {
            producer.initTransactions();
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(producer, true);
            var transaction = connection.getLocalTransaction();
            transaction.begin();
            TimeoutException failure = new TimeoutException("Commit outcome is unknown");
            producer.commitTransactionException = failure;

            ResourceException thrown = expectThrows(ResourceException.class, transaction::commit);

            assertSame(thrown.getCause(), failure);
        }
    }

    private static MockProducer<String, String> newProducer() {
        return new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    }
}
