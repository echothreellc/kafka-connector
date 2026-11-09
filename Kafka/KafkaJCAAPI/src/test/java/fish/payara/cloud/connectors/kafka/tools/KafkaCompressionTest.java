package fish.payara.cloud.connectors.kafka.tools;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Random;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.utils.internals.BufferSupplier;
import org.apache.kafka.common.utils.internals.ByteBufferOutputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class KafkaCompressionTest {

    @DataProvider(name = "compressionCases")
    public Object[][] compressionCases() {
        return new Object[][] {
            {"lz4", 0}, {"lz4", 32}, {"lz4", 131072},
            {"snappy", 0}, {"snappy", 32}, {"snappy", 131072}
        };
    }

    @Test(dataProvider = "compressionCases")
    public void roundTripKafkaCompression(String type, int size) throws Exception {
        byte[] payload = new byte[size];
        new Random(42).nextBytes(payload);
        Compression compression = Compression.of(type).build();
        // Kafka record format v2, used by the current client.
        byte magic = 2;
        ByteBufferOutputStream buffer = new ByteBufferOutputStream(128);
        try (OutputStream output = compression.wrapForOutput(buffer, magic)) {
            output.write(payload);
        }
        ByteBuffer compressed = buffer.buffer();
        compressed.flip();
        try (BufferSupplier supplier = BufferSupplier.create();
                InputStream input = compression.wrapForInput(compressed, magic, supplier)) {
            assertEquals(input.readAllBytes(), payload);
        }
    }
}
