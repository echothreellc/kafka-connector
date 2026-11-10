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
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.resource.NotSupportedException;
import javax.resource.ResourceException;
import javax.resource.spi.ConnectionEvent;
import javax.resource.spi.ConnectionEventListener;
import javax.resource.spi.ConnectionRequestInfo;
import javax.resource.spi.LocalTransaction;
import javax.resource.spi.ManagedConnection;
import javax.resource.spi.ManagedConnectionMetaData;
import javax.resource.spi.TransactionSupport.TransactionSupportLevel;
import javax.security.auth.Subject;
import javax.transaction.xa.XAResource;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.InvalidProducerEpochException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.TransactionalIdAuthorizationException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Steve Millidge (Payara Foundation)
 */
public class KafkaManagedConnection<K, V>
        implements ManagedConnection, KafkaConnection<K, V> {

    private static final Logger log = LoggerFactory.getLogger(KafkaManagedConnection.class);
    private static final Duration PRODUCER_CLOSE_TIMEOUT = Duration.ofSeconds(30);
    
    private final Producer<K, V> producer;
    private final KafkaLocalTransaction<K, V> localTransaction;
    
    private final List<ConnectionEventListener> listeners = new CopyOnWriteArrayList<>();
    private final HashSet<KafkaConnectionImpl<K, V>> connectionHandles = new HashSet<>();
    private final AtomicBoolean connectionErrorReported = new AtomicBoolean();
    private final boolean usingTransactions;
    private volatile boolean closed;
    private KafkaManagedConnectionFactory connectionFactory;
    private Subject connectionSubject;
    private ConnectionRequestInfo connectionRequestInfo;
    private PrintWriter writer;

    // Accept an initialized producer so lifecycle behavior can be tested without a broker.
    KafkaManagedConnection(Producer<K, V> producer, boolean usingTransactions) {
        this.producer = producer;
        this.usingTransactions = usingTransactions;
        localTransaction = new KafkaLocalTransaction<>(producer, this);
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

    private String getServerName()
        throws ResourceException  {
        try {
            return getCandidateInetAddress().getCanonicalHostName();
        } catch (SocketException|UnknownHostException ex) {
            throw new ResourceException(ex);
        }
    }

    KafkaManagedConnection(KafkaManagedConnectionFactory factory, Subject subject, ConnectionRequestInfo requestInfo,
            Function<Properties, Producer<K, V>> producerFactory)
            throws ResourceException {
        this(factory.getMergedProducerProperties(), factory.getClientId(),
                factory.getTransactionSupport() == TransactionSupportLevel.LocalTransaction,
                factory.getTransactionIdPrefix(), producerFactory);
        connectionFactory = factory;
        connectionSubject = subject;
        connectionRequestInfo = requestInfo;
    }

    KafkaManagedConnection(Properties producerProperties, String clientId, boolean usingTransactions,
            String transactionIdPrefix, Function<Properties, Producer<K, V>> producerFactory)
            throws ResourceException {
        log.debug("new KafkaManagedConnection(...)");

        this.usingTransactions = usingTransactions;

        producerProperties = (Properties) producerProperties.clone();
        if(transactionIdPrefix != null && !transactionIdPrefix.isEmpty()) {
            var transactionId = transactionIdPrefix
                    + "-" + getServerName()
                    + "-" + KafkaTransactionIdSequence.getInstance().getTransactionIdSequence().incrementAndGet();

            log.debug("ProducerConfig.TRANSACTIONAL_ID_CONFIG = " + transactionId);
            producerProperties.setProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionId);
        }

        // To compensate for InstanceAlreadyExistsException in KafkaProducer MBean registration:
        //      https://www.baeldung.com/kafka-instancealreadyexistsexception
        var clientIdConfig = clientId + "-" + UUID.randomUUID();
        log.debug("ProducerConfig.CLIENT_ID_CONFIG = " + clientIdConfig);
        producerProperties.setProperty(ProducerConfig.CLIENT_ID_CONFIG, clientIdConfig);
        
        producer = producerFactory.apply(producerProperties);
        localTransaction = new KafkaLocalTransaction<>(producer, this);

        if(usingTransactions) {
            try {
                producer.initTransactions();
            } catch (RuntimeException initializationFailure) {
                ResourceException failure = new ResourceException(
                        "Unable to initialize Kafka producer transactions", initializationFailure);
                try {
                    // Initialization failed, so do not wait for pending work to finish.
                    producer.close(Duration.ZERO);
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }
    }

    @Override
    public Object getConnection(Subject subject, ConnectionRequestInfo cxRequestInfo) throws ResourceException {
        log.debug("getConnection(...)");

        synchronized (connectionHandles) {
            if (!isUsable()) {
                throw new ResourceException("Kafka connection is closed or has failed");
            }
            var conn = new KafkaConnectionImpl<>(this);
            connectionHandles.add(conn);
            return conn;
        }
    }

    @Override
    public void destroy() throws ResourceException {
        log.debug("destroy()");

        shutdown();
    }

    @Override
    public void cleanup() throws ResourceException {
        log.debug("cleanup()");

        try {
            if (usingTransactions && !closed) {
                localTransaction.cleanup();
            }
        } finally {
            invalidateConnectionHandles();
        }
    }

    @Override
    public void associateConnection(Object connection) throws ResourceException {
        log.debug("associateConnection(...)");

        synchronized (connectionHandles) {
            if (!isUsable()) {
                throw new ResourceException("Kafka connection is closed or has failed");
            }
            if(connection instanceof KafkaConnectionImpl conn) {
                conn.setRealConn(this);
                connectionHandles.add(conn);
            }
        }
    }

    @Override
    public void addConnectionEventListener(ConnectionEventListener listener) {
        log.debug("addConnectionEventListener(...)");

        listeners.add(listener);
    }

    @Override
    public void removeConnectionEventListener(ConnectionEventListener listener) {
        log.debug("removeConnectionEventListener(...)");

        listeners.remove(listener);
    }

    @Override
    public XAResource getXAResource() throws ResourceException {
        throw new NotSupportedException("XA is not supported");
    }

    @Override
    public LocalTransaction getLocalTransaction() throws ResourceException {
        log.debug("getLocalTransaction()");
        if (!usingTransactions) {
            throw new NotSupportedException("Kafka transactions are not enabled for this connection");
        }
        if (!isUsable()) {
            throw new ResourceException("Kafka connection is closed or has failed");
        }
        return localTransaction;
    }

    @Override
    public ManagedConnectionMetaData getMetaData() throws ResourceException {
        return new KafkaConnectionMetadata();
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
    public Future<RecordMetadata> send(ProducerRecord<K, V> record) {
        log.debug("send(ProducerRecord record)");

        return send(record, null);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
        log.debug("send(ProducerRecord record, Callback callback)");

        checkOpen();
        try {
            // Always install a callback: failures must reach the pool even when callers
            // use only the Future, or ignore the result entirely.
            return producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    reportProducerFailure(exception);
                }
                if (callback != null) {
                    callback.onCompletion(metadata, exception);
                }
            });
        } catch (RuntimeException exception) {
            reportProducerFailure(exception);
            throw exception;
        }
    }

    @Override
    public void flush() {
        log.debug("flush()");

        checkOpen();
        try {
            producer.flush();
        } catch (RuntimeException exception) {
            reportProducerFailure(exception);
            throw exception;
        }
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        log.debug("partitionsFor(...)");
        
        checkOpen();
        try {
            return producer.partitionsFor(topic);
        } catch (RuntimeException exception) {
            reportProducerFailure(exception);
            throw exception;
        }
    }

    @Override
    public void close() throws ResourceException {
        log.debug("close()");

        shutdown();
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() throws ResourceException {
        checkOpen();
        return producer.metrics();
    }

    private void shutdown() throws ResourceException {
        synchronized (connectionHandles) {
            if (closed) {
                return;
            }
            closed = true;
            invalidateConnectionHandles();
        }

        // Kafka may invoke send callbacks during close. Do not hold the handle
        // monitor while waiting, since a callback can ask the pool to destroy us.
        try {
            producer.close(PRODUCER_CLOSE_TIMEOUT);
        } catch (RuntimeException exception) {
            throw new ResourceException("Unable to close Kafka producer", exception);
        }
    }

    private void invalidateConnectionHandles() {
        synchronized (connectionHandles) {
            for (KafkaConnectionImpl<K, V> handle : connectionHandles) {
                // Reassociation can leave an old registration behind. Only detach
                // handles that still belong to this physical connection.
                if (handle.getRealConn() == this) {
                    handle.setRealConn(null);
                }
            }
            connectionHandles.clear();
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Kafka connection is closed");
        }
    }

    boolean matches(KafkaManagedConnectionFactory factory, Subject subject, ConnectionRequestInfo requestInfo) {
        return isUsable() && (!usingTransactions || localTransaction.isIdle())
                && connectionFactory != null && connectionFactory.equals(factory)
                && Objects.equals(connectionSubject, subject)
                && Objects.equals(connectionRequestInfo, requestInfo);
    }

    boolean isUsable() {
        return !closed && !connectionErrorReported.get();
    }

    void reportProducerFailure(Exception exception) {
        if (isFatalProducerFailure(exception)) {
            invalidateConnection(exception);
        }
    }

    void invalidateConnection(Exception exception) {
        if (!connectionErrorReported.compareAndSet(false, true)) {
            return;
        }

        ConnectionEvent event = new ConnectionEvent(this, ConnectionEvent.CONNECTION_ERROR_OCCURRED, exception);
        for (ConnectionEventListener listener : listeners) {
            try {
                listener.connectionErrorOccurred(event);
            } catch (RuntimeException listenerFailure) {
                // One listener must not hide the producer failure or prevent other
                // listeners and the application's send callback from being notified.
                log.warn("Connection listener failed while reporting a producer error", listenerFailure);
            }
        }
    }

    private boolean isFatalProducerFailure(Throwable exception) {
        // A defunct Kafka producer wraps its original fatal error in KafkaException
        // on subsequent calls, so inspect the cause chain as well.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProducerFencedException
                    || cause instanceof InvalidProducerEpochException
                    || cause instanceof UnsupportedVersionException
                    || cause instanceof TransactionalIdAuthorizationException
                    || cause instanceof ClusterAuthorizationException) {
                return true;
            }
            if (usingTransactions && (cause instanceof OutOfOrderSequenceException
                    || cause instanceof AuthorizationException)) {
                return true;
            }
        }
        return false;
    }
    
    void remove(KafkaConnectionImpl<K, V> conn) {
        log.debug("remove(...)");
        
        synchronized (connectionHandles) {
            connectionHandles.remove(conn);
        }
        ConnectionEvent event = new ConnectionEvent(this, ConnectionEvent.CONNECTION_CLOSED);
        event.setConnectionHandle(conn);
        for (ConnectionEventListener listener : listeners) {
            listener.connectionClosed(event);
        }
    }
    
}
