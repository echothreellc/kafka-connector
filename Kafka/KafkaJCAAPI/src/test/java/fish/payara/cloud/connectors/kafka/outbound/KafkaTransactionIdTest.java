package fish.payara.cloud.connectors.kafka.outbound;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import java.util.ArrayList;
import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class KafkaTransactionIdTest {

    @Test
    public void independentJvmsOnTheSameHostDoNotReuseTransactionIds() throws Exception {
        String first = transactionIdFromNewJvm();
        String second = transactionIdFromNewJvm();

        assertNotEquals(first, second,
                "Independent JVMs using the same prefix must not fence each other's producers");
    }

    @Test
    public void transactionIdKeepsPrefixAndHostnameWithUuidSuffix() throws Exception {
        String prefix = "orders-west";
        Properties configuration = producerConfiguration(new Properties(), prefix, true);
        String id = configuration.getProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG);

        assertTrue(id.startsWith(prefix + "-"));
        String suffix = id.substring(id.length() - 36);
        assertEquals(UUID.fromString(suffix).toString(), suffix);
        String hostname = id.substring(prefix.length() + 1, id.length() - 37);
        assertTrue(!hostname.isEmpty(), "The hostname must remain available for diagnostics");
    }

    @Test
    public void concurrentlyCreatedProducersHaveDistinctIdsWithoutMutatingSharedProperties() throws Exception {
        Properties original = new Properties();
        original.setProperty("application-setting", "retained");
        var executor = Executors.newFixedThreadPool(8);
        try {
            var results = new ArrayList<Future<Properties>>();
            for (int producer = 0; producer < 256; producer++) {
                results.add(executor.submit(() -> producerConfiguration(original, "orders", true)));
            }
            var transactionIds = new HashSet<String>();
            var clientIds = new HashSet<String>();
            for (Future<Properties> result : results) {
                Properties configuration = result.get(10, TimeUnit.SECONDS);
                assertTrue(transactionIds.add(configuration.getProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG)),
                        "Each physical producer must receive a distinct transaction ID");
                assertTrue(clientIds.add(configuration.getProperty(ProducerConfig.CLIENT_ID_CONFIG)));
                assertEquals(configuration.getProperty("application-setting"), "retained");
            }
            assertEquals(transactionIds.size(), 256);
            assertNull(original.getProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG));
            assertNull(original.getProperty(ProducerConfig.CLIENT_ID_CONFIG));
            assertEquals(original.size(), 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @DataProvider(name = "absentPrefixes")
    public Object[][] absentPrefixes() {
        return new Object[][] {{null}, {""}};
    }

    @Test(dataProvider = "absentPrefixes")
    public void nontransactionalProducerDoesNotAcquireTransactionId(String prefix) throws Exception {
        Properties configuration = producerConfiguration(new Properties(), prefix, false);

        assertNull(configuration.getProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG));
    }

    private static Properties producerConfiguration(Properties original, String prefix, boolean transactional) throws Exception {
        AtomicReference<Properties> configuration = new AtomicReference<>();
        try (MockProducer<String, String> producer = new MockProducer<>(
                true, null, new StringSerializer(), new StringSerializer())) {
            KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(
                    original, "test-client", transactional, prefix, properties -> {
                        configuration.set(properties);
                        return producer;
                    });
            connection.destroy();
        }
        return configuration.get();
    }

    private static String transactionIdFromNewJvm() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-cp", classpath, ProducerProcess.class.getName()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Producer JVM did not exit");
            String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(process.exitValue(), 0, errors);
            String transactionId = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertTrue(transactionId.startsWith("orders-"), "Producer JVM did not return its transaction ID");
            return transactionId;
        } finally {
            process.destroyForcibly();
        }
    }

    // Each child JVM constructs a real managed connection using MockProducer,
    // so it exercises production ID generation without requiring a Kafka broker.
    public static class ProducerProcess {
        public static void main(String[] arguments) throws Exception {
            AtomicReference<String> transactionId = new AtomicReference<>();
            try (MockProducer<String, String> producer = new MockProducer<>(
                    true, null, new StringSerializer(), new StringSerializer())) {
                KafkaManagedConnection<String, String> connection = new KafkaManagedConnection<>(
                        new Properties(), "test-client", true, "orders", properties -> {
                            transactionId.set(properties.getProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG));
                            return producer;
                        });
                connection.destroy();
                System.out.println(transactionId.get());
            }
        }
    }
}
