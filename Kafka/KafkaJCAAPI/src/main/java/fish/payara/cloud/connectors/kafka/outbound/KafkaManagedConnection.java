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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Future;
import javax.resource.NotSupportedException;
import javax.resource.ResourceException;
import javax.resource.spi.ConnectionEvent;
import javax.resource.spi.ConnectionEventListener;
import javax.resource.spi.ConnectionRequestInfo;
import javax.resource.spi.LocalTransaction;
import javax.resource.spi.ManagedConnection;
import javax.resource.spi.ManagedConnectionMetaData;
import javax.security.auth.Subject;
import javax.transaction.xa.XAResource;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Steve Millidge (Payara Foundation)
 */
public class KafkaManagedConnection<K, V>
        implements ManagedConnection, KafkaConnection<K, V> {

    private static final Logger log = LoggerFactory.getLogger(KafkaManagedConnection.class);
    
    private KafkaProducer<K, V> producer;
    private LocalTransaction localTransaction;
    
    private final List<ConnectionEventListener> listeners;
    private final HashSet<KafkaConnectionImpl<K, V>> connectionHandles;
    private PrintWriter writer;

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

    KafkaManagedConnection(Properties producerProperties, String clientId, boolean usingTransactions, String transactionIdPrefix)
            throws ResourceException {
        log.info("new KafkaManagedConnection(...)");

        producerProperties = (Properties) producerProperties.clone();
        if(transactionIdPrefix != null && !transactionIdPrefix.isEmpty()) {
            var transactionId = transactionIdPrefix
                    + "-" + getServerName()
                    + "-" + KafkaTransactionIdSequence.getInstance().getTransactionIdSequence().incrementAndGet();

            log.info("ProducerConfig.TRANSACTIONAL_ID_CONFIG = " + transactionId);
            producerProperties.setProperty(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionId);
        }

        // To compensate for InstanceAlreadyExistsException in KafkaProducer MBean registration:
        //      https://www.baeldung.com/kafka-instancealreadyexistsexception
        var clientIdConfig = clientId + "-" + UUID.randomUUID();
        log.info("ProducerConfig.CLIENT_ID_CONFIG = " + clientIdConfig);
        producerProperties.setProperty(ProducerConfig.CLIENT_ID_CONFIG, clientIdConfig);
        
        producer = new KafkaProducer<>(producerProperties);
        localTransaction = new KafkaLocalTransaction<>(producer);

        if(usingTransactions) {
            producer.initTransactions();
        }

        listeners = new LinkedList<>();
        connectionHandles = new HashSet<>();
    }

    @Override
    public Object getConnection(Subject subject, ConnectionRequestInfo cxRequestInfo) throws ResourceException {
        log.info("getConnection(...)");

        var conn = new KafkaConnectionImpl<>(this);
        connectionHandles.add(conn);
        return conn;
    }

    @Override
    public void destroy() throws ResourceException {
        log.info("destroy()");

        producer.close();
        producer = null;
    }

    @Override
    public void cleanup() throws ResourceException {
        log.info("cleanup()");

        for (KafkaConnectionImpl<K, V> conn : connectionHandles) {
            conn.setRealConn(null);
        }
        connectionHandles.clear();
    }

    @Override
    public void associateConnection(Object connection) throws ResourceException {
        log.info("associateConnection(...)");

        if(connection instanceof KafkaConnectionImpl conn) {
            conn.setRealConn(this);
            connectionHandles.add(conn);
        }
    }

    @Override
    public void addConnectionEventListener(ConnectionEventListener listener) {
        log.info("addConnectionEventListener(...)");

        listeners.add(listener);
    }

    @Override
    public void removeConnectionEventListener(ConnectionEventListener listener) {
        log.info("removeConnectionEventListener(...)");

        listeners.remove(listener);
    }

    @Override
    public XAResource getXAResource() throws ResourceException {
        throw new NotSupportedException("XA is not supported");
    }

    @Override
    public LocalTransaction getLocalTransaction() throws ResourceException {
        log.info("getLocalTransaction()");
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
        log.info("send(ProducerRecord record)");

        return producer.send(record);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
        log.info("send(ProducerRecord record, Callback callback)");

        return producer.send(record,callback);
    }

    @Override
    public void flush() {
        log.info("flush()");

        producer.flush();
    }

    @Override
    public List<PartitionInfo> partitionsFor(String topic) {
        log.info("partitionsFor(...)");
        
        return producer.partitionsFor(topic);
    }

    @Override
    public void close() throws Exception {
        log.info("close()");

        producer.close();
        
        producer = null;
        localTransaction = null;
    }

    @Override
    public Map<MetricName, ? extends Metric> metrics() throws ResourceException {
        return producer.metrics();
    }
    
    void remove(KafkaConnectionImpl<K, V> conn) {
        log.info("remove(...)");
        
        connectionHandles.remove(conn);
        ConnectionEvent event = new ConnectionEvent(this, ConnectionEvent.CONNECTION_CLOSED);
        event.setConnectionHandle(conn);
        for (ConnectionEventListener listener : listeners) {
            listener.connectionClosed(event);
        }
    }
    
}
