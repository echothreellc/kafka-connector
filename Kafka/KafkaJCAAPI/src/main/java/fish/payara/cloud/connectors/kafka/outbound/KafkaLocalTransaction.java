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

import javax.resource.ResourceException;
import javax.resource.spi.LocalTransaction;
import java.util.function.Consumer;

import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KafkaLocalTransaction<K, V>
        implements LocalTransaction {
    
    private static final Logger log = LoggerFactory.getLogger(KafkaLocalTransaction.class);

    private final Producer<K, V> producer;
    private final Consumer<Exception> failureHandler;

    KafkaLocalTransaction(Producer<K, V> producer, Consumer<Exception> failureHandler) {
        log.debug("new KafkaLocalTransaction(...)");

        this.producer = producer;
        this.failureHandler = failureHandler;
    }

    @Override
    public void begin()
            throws ResourceException {
        log.debug("begin()");

        try {
            producer.beginTransaction();
        } catch (RuntimeException exception) {
            failureHandler.accept(exception);
            throw exception;
        }
    }

    @Override
    public void commit()
            throws ResourceException {
        log.debug("commit()");

        try {
            producer.commitTransaction();
        } catch (RuntimeException exception) {
            failureHandler.accept(exception);
            throw exception;
        }
    }

    @Override
    public void rollback()
            throws ResourceException {
        log.debug("rollback()");

        try {
            producer.abortTransaction();
        } catch (RuntimeException exception) {
            failureHandler.accept(exception);
            throw exception;
        }
    }

}
