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

import org.apache.kafka.clients.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KafkaLocalTransaction
        implements LocalTransaction {
    
    private static final Logger log = LoggerFactory.getLogger(KafkaLocalTransaction.class);

    private KafkaProducer producer;

    public KafkaLocalTransaction(KafkaProducer producer) {
        log.info("new KafkaLocalTransaction(...)");

        this.producer = producer;
    }

    @Override
    public void begin() throws ResourceException {
        log.info("begin()");

        producer.beginTransaction();
    }

    @Override
    public void commit() throws ResourceException {
        log.info("commit()");

        producer.commitTransaction();
    }

    @Override
    public void rollback() throws ResourceException {
        log.info("rollback()");

        producer.abortTransaction();
    }

}
