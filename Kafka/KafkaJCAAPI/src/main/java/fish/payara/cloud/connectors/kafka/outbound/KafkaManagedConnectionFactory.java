/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2017 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/main/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.cloud.connectors.kafka.outbound;

import fish.payara.cloud.connectors.kafka.api.KafkaConnection;
import fish.payara.cloud.connectors.kafka.api.KafkaConnectionFactory;
import fish.payara.cloud.connectors.kafka.tools.AdditionalPropertiesParser;

import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import javax.resource.ResourceException;
import javax.resource.spi.ConfigProperty;
import javax.resource.spi.ConnectionDefinition;
import javax.resource.spi.ConnectionManager;
import javax.resource.spi.ConnectionRequestInfo;
import javax.resource.spi.ManagedConnection;
import javax.resource.spi.ManagedConnectionFactory;
import javax.resource.spi.TransactionSupport;
import javax.security.auth.Subject;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;

/**
 *
 * @author Steve Millidge (Payara Foundation)
 */
@ConnectionDefinition( connection = KafkaConnection.class,
        connectionFactory = KafkaConnectionFactory.class,
        connectionFactoryImpl = KafkaConnectionFactoryImpl.class,
        connectionImpl = KafkaConnectionImpl.class
)
public class KafkaManagedConnectionFactory implements ManagedConnectionFactory, TransactionSupport {

    private final Properties producerProperties;
    private AdditionalPropertiesParser additionalPropertiesParser;

    @ConfigProperty(defaultValue = "localhost:9092", description = "Kafka Servers to Connect to", type = String.class)
    private String bootstrapServersConfig;

    @ConfigProperty(defaultValue = "KafkaJCAClient", description = "Client ID of the Producer", type = String.class)
    private String clientId;

    @ConfigProperty(defaultValue = "org.apache.kafka.common.serialization.StringSerializer", type = String.class, description = "Serializer class for key")
    private String valueSerializer;

    @ConfigProperty(defaultValue = "org.apache.kafka.common.serialization.StringSerializer", type = String.class, description = "Serializer class for value")
    private String keySerializer;

    @ConfigProperty(type = Long.class, defaultValue = "33554432", description = "The total bytes the producer can use to buffer messages")
    private Long bufferMemory;

    @ConfigProperty(type = String.class, description = "The number of acks the producer requires", defaultValue = "1")
    private String acks;

    @ConfigProperty(type = Integer.class, description = "The number of retries if there is a transient error", defaultValue = "2147483647")
    private Integer retries;

    @ConfigProperty(type = Long.class, description = "The producer will attempt to batch records together into fewer requests whenever multiple records are being sent to the same partition", defaultValue = "16384")
    private Long batchSize;

    @ConfigProperty(type = Long.class, defaultValue = "0", description = "The producer groups together any records that arrive in between request transmissions into a single batched request. ")
    private Long lingerMS;

    @ConfigProperty(type = Long.class, defaultValue = "60000", description = "How long can send block ")
    private Long maxBlockMS;

    @ConfigProperty(type = Long.class, defaultValue = "1048576", description = "Maximum size of request (bytes)")
    private Long maxRequestSize;

    @ConfigProperty(type = Integer.class, defaultValue = "32768", description = "Receive Buffer (bytes)")
    private Integer receiveBufferBytes;

    @ConfigProperty(type = Integer.class, defaultValue = "30000", description = "Request Timeout (ms)")
    private Integer requestTimeout;

    @ConfigProperty(type = String.class, description = "Compression type of data sent", defaultValue = "none")
    private String compression;

    @ConfigProperty(type = Long.class, description = "Close Idle Kafka Connections", defaultValue = "540000")
    private Long connectionsMaxIdle;

    @ConfigProperty(type = Integer.class, defaultValue = "5", description = "Maximum unacknowledged requests to send before blocking")
    private Integer maxInflightConnections;

    @ConfigProperty(type = Long.class, description = "Period of time before a refresh of Metadata (ms)", defaultValue = "300000")
    private Long metadataMaxAge;

    @ConfigProperty(type = Long.class, description = "The amount of time to wait before attempting a retry (ms)", defaultValue = "100")
    private Long retryBackoff;

    @ConfigProperty(type = Long.class, description = "The amount of time to wait before attempting a reconnection (ms)", defaultValue = "100")
    private Long reconnectBackoff;

    @ConfigProperty(type = Boolean.class, description = "Indicates if the producer will ensure that exactly one copy of each message is written in the stream", defaultValue = "false")
    private Boolean enableIdempotence;

    @ConfigProperty(type = String.class, description = "Provides the prefix for the transaction ID if transactions are in use", defaultValue = "")
    private String transactionIdPrefix;

    @ConfigProperty(type = String.class, description = "Additional properties to be passed to the KafkaConnection.")
    private String additionalProperties;

    transient private PrintWriter writer;
    
    transient private KafkaProducer producer;

    public KafkaManagedConnectionFactory() {
        producerProperties = new Properties();
    }

    public String getBootstrapServersConfig() {
        return bootstrapServersConfig;
    }

    public void setBootstrapServersConfig(String bootstrapServersConfig) {
        this.bootstrapServersConfig = bootstrapServersConfig;
        producerProperties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServersConfig);
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
        producerProperties.setProperty(ProducerConfig.CLIENT_ID_CONFIG, clientId);
    }

    public String getValueSerializer() {
        return valueSerializer;
    }

    public void setValueSerializer(String valueDeserializer) {
        this.valueSerializer = valueDeserializer;
        producerProperties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueDeserializer);
    }

    public String getKeySerializer() {
        return keySerializer;
    }

    public void setKeySerializer(String keyDeserializer) {
        this.keySerializer = keyDeserializer;
        producerProperties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, keyDeserializer);
    }

    public Long getBufferMemory() {
        return bufferMemory;
    }

    public void setBufferMemory(Long bufferMemory) {
        this.bufferMemory = bufferMemory;
        producerProperties.setProperty(ProducerConfig.BUFFER_MEMORY_CONFIG, Long.toString(bufferMemory));
    }

    public String getAcks() {
        return acks;
    }

    public void setAcks(String acks) {
        this.acks = acks;
        producerProperties.setProperty(ProducerConfig.ACKS_CONFIG, acks);
    }

    public Integer getRetries() {
        return retries;
    }

    public void setRetries(Integer retries) {
        this.retries = retries;
        producerProperties.setProperty(ProducerConfig.RETRIES_CONFIG, Long.toString(retries));
    }

    public Long getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Long batchSize) {
        this.batchSize = batchSize;
        producerProperties.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, Long.toString(batchSize));
    }

    public Long getLingerMS() {
        return lingerMS;
    }

    public void setLingerMS(Long lingerMS) {
        this.lingerMS = lingerMS;
        producerProperties.setProperty(ProducerConfig.LINGER_MS_CONFIG, Long.toString(lingerMS));
    }

    public Properties getProducerProperties() {
        return producerProperties;
    }

    public Long getMaxBlockMS() {
        return maxBlockMS;
    }

    public void setMaxBlockMS(Long maxBlockMS) {
        this.maxBlockMS = maxBlockMS;
        producerProperties.setProperty(ProducerConfig.MAX_BLOCK_MS_CONFIG, Long.toString(maxBlockMS));
    }

    public Long getMaxRequestSize() {
        return maxRequestSize;
    }

    public void setMaxRequestSize(Long maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
        producerProperties.setProperty(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, Long.toString(maxRequestSize));
    }

    public Integer getReceiveBufferBytes() {
        return receiveBufferBytes;
    }

    public void setReceiveBufferBytes(Integer receiveBufferBytes) {
        this.receiveBufferBytes = receiveBufferBytes;
        producerProperties.setProperty(ProducerConfig.RECEIVE_BUFFER_CONFIG, Integer.toString(receiveBufferBytes));
    }

    public Integer getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Integer requestTimeout) {
        this.requestTimeout = requestTimeout;
        producerProperties.setProperty(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Integer.toString(requestTimeout));
    }

    public String getCompression() {
        return compression;
    }

    public void setCompression(String compression) {
        this.compression = compression;
        producerProperties.setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, compression);
    }

    public Long getConnectionsMaxIdle() {
        return connectionsMaxIdle;
    }

    public void setConnectionsMaxIdle(Long maxIdle) {
        this.connectionsMaxIdle = maxIdle;
        producerProperties.setProperty(ProducerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, Long.toString(maxIdle));
    }

    public Integer getMaxInflightConnections() {
        return maxInflightConnections;
    }

    public void setMaxInflightConnections(Integer maxInflightConnections) {
        this.maxInflightConnections = maxInflightConnections;
        producerProperties.setProperty(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, Integer.toString(maxInflightConnections));
    }

    public Long getMetadataMaxAge() {
        return metadataMaxAge;
    }

    public void setMetadataMaxAge(Long metadataMaxAge) {
        this.metadataMaxAge = metadataMaxAge;
        producerProperties.setProperty(ProducerConfig.METADATA_MAX_AGE_CONFIG, Long.toString(metadataMaxAge));
    }

    public Long getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Long retryBackoff) {
        this.retryBackoff = retryBackoff;
        producerProperties.setProperty(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, Long.toString(retryBackoff));
    }

    public Long getReconnectBackoff() {
        return reconnectBackoff;
    }

    public void setReconnectBackoff(Long reconnectBackoff) {
        this.reconnectBackoff = reconnectBackoff;
        producerProperties.setProperty(ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG, Long.toString(reconnectBackoff));
    }

    public Boolean getEnableIdempotence() {
        return enableIdempotence;
    }

    public void setEnableIdempotence(Boolean enableIdempotence) {
        this.enableIdempotence = enableIdempotence;
        producerProperties.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.toString(enableIdempotence));
    }

    public String getTransactionIdPrefix() {
        return transactionIdPrefix;
    }

    // For getCandidateInetAddress() & getLocalHostInetAddress():
    //     https://stackoverflow.com/questions/9481865/getting-the-ip-address-of-the-current-machine-using-java
    //     License: https://creativecommons.org/licenses/by-sa/3.0/
    private InetAddress getLocalHostInetAddress() throws UnknownHostException {
        var jdkSuppliedAddress = InetAddress.getLocalHost();

        if (jdkSuppliedAddress == null) {
            throw new UnknownHostException("null InetAddress.getLocalHost()");
        }
        
        return jdkSuppliedAddress;
    }

    private InetAddress getCandidateInetAddress() throws SocketException, UnknownHostException {
        InetAddress candidateInetAddress = null;

        for(var networkInterfaces = NetworkInterface.getNetworkInterfaces(); networkInterfaces.hasMoreElements();) {
            var networkInterface = (NetworkInterface)networkInterfaces.nextElement();

            for(var inetAddresses = networkInterface.getInetAddresses(); inetAddresses.hasMoreElements();) {
                var inetAddress = (InetAddress)inetAddresses.nextElement();

                if (!inetAddress.isLoopbackAddress()) {
                    if (inetAddress.isSiteLocalAddress()) {
                        return inetAddress;
                    } else if (candidateInetAddress == null) {
                        candidateInetAddress = inetAddress;
                    }
                }
            }
        }

        return candidateInetAddress == null ? getLocalHostInetAddress() : candidateInetAddress;
    }

    private String getServerName() throws ResourceException  {
        try {
            return getCandidateInetAddress().getCanonicalHostName();
        } catch (SocketException|UnknownHostException ex) {
            throw new ResourceException(ex);
        }
    }

    public void setTransactionIdPrefix(String transactionIdPrefix) throws ResourceException {
        this.transactionIdPrefix = transactionIdPrefix;

        if(transactionIdPrefix != null && !"".equals(transactionIdPrefix)) {
            var transactionId = transactionIdPrefix
                    + "-" + getServerName()
                    + "-" + KafkaTransactionIdSequence.getInstance().getTransactionIdSequence().incrementAndGet();

            producerProperties.setProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionId);
        }
    }

    public String getAdditionalProperties() {
        return additionalProperties;
    }

    public void setAdditionalProperties(String additionalProperties) {
        this.additionalProperties = additionalProperties;
        this.additionalPropertiesParser = new AdditionalPropertiesParser(additionalProperties);
    }

    public PrintWriter getWriter() {
        return writer;
    }

    public void setWriter(PrintWriter writer) {
        this.writer = writer;
    }

    private boolean isUsingTransactions() {
        return transactionIdPrefix != null && !"".equals(transactionIdPrefix);
    }

    private void ensureKafkaProducer() {
        if (producer == null) {
            Properties properties =
                    additionalPropertiesParser == null
                            ? producerProperties
                            : AdditionalPropertiesParser.merge(producerProperties,  additionalPropertiesParser.parse());

            producer = new KafkaProducer(properties);

            if(isUsingTransactions()) {
                producer.initTransactions();
            }
        }
    }

    @Override
    public Object createConnectionFactory(ConnectionManager cxManager) throws ResourceException {
        ensureKafkaProducer();

        return new KafkaConnectionFactoryImpl(this,cxManager);
    }

    @Override
    public Object createConnectionFactory() throws ResourceException {
        ensureKafkaProducer();

        return new KafkaConnectionFactoryImpl(this, null);
    }

    @Override
    public ManagedConnection createManagedConnection(Subject subject, ConnectionRequestInfo cxRequestInfo) throws ResourceException {
        return new KafkaManagedConnection(producer);
    }

    @Override
    public ManagedConnection matchManagedConnections(Set connectionSet, Subject subject, ConnectionRequestInfo cxRequestInfo) throws ResourceException {
        return (ManagedConnection) connectionSet.toArray()[0];
    }

    @Override
    public void setLogWriter(PrintWriter out) throws ResourceException {
        writer = out;
    }

    @Override
    public PrintWriter getLogWriter() throws ResourceException {
        return writer;
    }

    @Override
    public TransactionSupportLevel getTransactionSupport() {
        return isUsingTransactions() ? TransactionSupportLevel.LocalTransaction : TransactionSupportLevel.NoTransaction;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KafkaManagedConnectionFactory that = (KafkaManagedConnectionFactory) o;
        return Objects.equals(producerProperties, that.producerProperties) &&
                Objects.equals(bootstrapServersConfig, that.bootstrapServersConfig) &&
                Objects.equals(clientId, that.clientId) &&
                Objects.equals(valueSerializer, that.valueSerializer) &&
                Objects.equals(keySerializer, that.keySerializer) &&
                Objects.equals(bufferMemory, that.bufferMemory) &&
                Objects.equals(acks, that.acks) &&
                Objects.equals(retries, that.retries) &&
                Objects.equals(batchSize, that.batchSize) &&
                Objects.equals(lingerMS, that.lingerMS) &&
                Objects.equals(maxBlockMS, that.maxBlockMS) &&
                Objects.equals(maxRequestSize, that.maxRequestSize) &&
                Objects.equals(receiveBufferBytes, that.receiveBufferBytes) &&
                Objects.equals(requestTimeout, that.requestTimeout) &&
                Objects.equals(compression, that.compression) &&
                Objects.equals(connectionsMaxIdle, that.connectionsMaxIdle) &&
                Objects.equals(maxInflightConnections, that.maxInflightConnections) &&
                Objects.equals(metadataMaxAge, that.metadataMaxAge) &&
                Objects.equals(retryBackoff, that.retryBackoff) &&
                Objects.equals(reconnectBackoff, that.reconnectBackoff) &&
                Objects.equals(enableIdempotence, that.enableIdempotence) &&
                Objects.equals(transactionIdPrefix, that.transactionIdPrefix) &&
                Objects.equals(additionalProperties, that.additionalProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(producerProperties, bootstrapServersConfig, clientId, valueSerializer, keySerializer, bufferMemory,
        acks, retries, batchSize, lingerMS, maxBlockMS, maxRequestSize, receiveBufferBytes, requestTimeout, compression,
        connectionsMaxIdle, maxInflightConnections, metadataMaxAge, retryBackoff, reconnectBackoff, enableIdempotence,
        transactionIdPrefix, additionalProperties);
    }

}
