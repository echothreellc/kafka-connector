package fish.payara.cloud.connectors.kafka.outbound;

import javax.resource.ResourceException;
import javax.resource.spi.LocalTransaction;

import org.apache.kafka.clients.producer.KafkaProducer;

public class KafkaLocalTransaction
        implements LocalTransaction {
    
    private KafkaProducer producer;

    public KafkaLocalTransaction(KafkaProducer producer) {
        this.producer = producer;
    }

    @Override
    public void begin() throws ResourceException {
        producer.beginTransaction();
    }

    @Override
    public void commit() throws ResourceException {
        producer.commitTransaction();
    }

    @Override
    public void rollback() throws ResourceException {
        producer.abortTransaction();
    }

}
