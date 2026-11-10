// --------------------------------------------------------------------------------
// Copyright 2002-2023 Echo Three, LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// --------------------------------------------------------------------------------

package fish.payara.cloud.connectors.kafka.outbound;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.resource.ResourceException;
import javax.resource.spi.LocalTransaction;
import javax.resource.spi.LocalTransactionException;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KafkaLocalTransaction<K, V>
        implements LocalTransaction {
    
    private static final Logger log = LoggerFactory.getLogger(KafkaLocalTransaction.class);

    private final Producer<K, V> producer;
    private final KafkaManagedConnection<K, V> connection;
    private final AtomicBoolean operationInProgress = new AtomicBoolean();
    private volatile State state = State.IDLE;

    private enum State {
        IDLE, BEGINNING, ACTIVE, COMMITTING, ABORT_REQUIRED, ABORTING, FAILED
    }

    KafkaLocalTransaction(Producer<K, V> producer, KafkaManagedConnection<K, V> connection) {
        log.debug("new KafkaLocalTransaction(...)");

        this.producer = producer;
        this.connection = connection;
    }

    @Override
    public void begin()
            throws ResourceException {
        log.debug("begin()");

        acquireOperation();
        try {
            requireState("begin", State.IDLE);
            state = State.BEGINNING;
            try {
                producer.beginTransaction();
                state = State.ACTIVE;
            } catch (RuntimeException exception) {
                throw discardConnection("begin", exception);
            }
        } finally {
            operationInProgress.set(false);
        }
    }

    @Override
    public void commit()
            throws ResourceException {
        log.debug("commit()");

        acquireOperation();
        try {
            requireState("commit", State.ACTIVE, State.COMMITTING);
            state = State.COMMITTING;
            try {
                producer.commitTransaction();
                state = State.IDLE;
            } catch (RuntimeException exception) {
                connection.reportProducerFailure(exception);
                if (!connection.isUsable()) {
                    state = State.FAILED;
                } else if (!(exception instanceof TimeoutException) && !(exception instanceof InterruptException)) {
                    state = State.ABORT_REQUIRED;
                }
                // A timed-out or interrupted commit may still complete at the broker.
                // Keep COMMITTING so callers can retry commit, but cannot abort it.
                throw new ResourceException("Unable to commit Kafka transaction", exception);
            }
        } finally {
            operationInProgress.set(false);
        }
    }

    @Override
    public void rollback()
            throws ResourceException {
        log.debug("rollback()");

        acquireOperation();
        try {
            requireState("rollback", State.ACTIVE, State.ABORT_REQUIRED, State.ABORTING);
            state = State.ABORTING;
            try {
                producer.abortTransaction();
                state = State.IDLE;
            } catch (TimeoutException | InterruptException exception) {
                // Only another rollback is safe until the abort outcome is known.
                throw new ResourceException("Unable to roll back Kafka transaction", exception);
            } catch (RuntimeException exception) {
                throw discardConnection("roll back", exception);
            }
        } finally {
            operationInProgress.set(false);
        }
    }

    boolean isIdle() {
        return !operationInProgress.get() && state == State.IDLE;
    }

    void cleanup() throws ResourceException {
        if (isIdle()) {
            return;
        }
        if (!operationInProgress.get()
                && (state == State.ACTIVE || state == State.ABORT_REQUIRED || state == State.ABORTING)) {
            try {
                rollback();
                return;
            } catch (ResourceException failure) {
                state = State.FAILED;
                connection.invalidateConnection(failure);
                throw failure;
            }
        }

        // Never guess whether an in-flight commit succeeded, or switch it to abort.
        ResourceException failure = new ResourceException("Cannot reuse Kafka connection with transaction in state " + state);
        state = State.FAILED;
        connection.invalidateConnection(failure);
        throw failure;
    }

    private void acquireOperation() throws ResourceException {
        // Do not hold a monitor while Kafka waits for send callbacks: a callback can
        // notify the pool, which may call back into cleanup on the same connection.
        if (!operationInProgress.compareAndSet(false, true)) {
            throw new LocalTransactionException("Another Kafka transaction operation is already in progress");
        }
        if (!connection.isUsable()) {
            operationInProgress.set(false);
            throw new ResourceException("Kafka connection is closed or has failed");
        }
    }

    private void requireState(String operation, State... allowedStates) throws LocalTransactionException {
        for (State allowed : allowedStates) {
            if (state == allowed) {
                return;
            }
        }
        throw new LocalTransactionException("Cannot " + operation + " Kafka transaction in state " + state);
    }

    private ResourceException discardConnection(String operation, RuntimeException exception) {
        state = State.FAILED;
        ResourceException failure = new ResourceException("Unable to " + operation + " Kafka transaction", exception);
        connection.invalidateConnection(exception);
        return failure;
    }
}
