/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.artemis.tests.integration.mqtt5.spec;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttPubAckMessage;
import io.netty.handler.codec.mqtt.MqttPubReplyMessageVariableHeader;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.core.protocol.mqtt.MQTTInterceptor;
import org.apache.activemq.artemis.core.protocol.mqtt.MQTTReasonCodes;
import org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil;
import org.apache.activemq.artemis.tests.integration.mqtt5.MQTT5TestSupport;
import org.apache.activemq.artemis.tests.util.RandomUtil;
import org.apache.activemq.artemis.tests.util.Wait;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.jboss.logging.Logger;
import org.junit.Test;

/**
 * Fulfilled by client or Netty codec (i.e. not tested here):
 *
 * [MQTT-4.3.1-1] In the QoS 0 delivery protocol, the sender MUST send a PUBLISH packet with QoS 0 and DUP flag set to 0.
 * [MQTT-4.3.2-1] In the QoS 1 delivery protocol, the sender MUST assign an unused Packet Identifier each time it has a new Application Message to publish.
 * [MQTT-4.3.3-1] In the QoS 2 delivery protocol, the sender MUST assign an unused Packet Identifier when it has a new Application Message to publish.
 * [MQTT-4.3.3-2] In the QoS 2 delivery protocol, the sender MUST send a PUBLISH packet containing this Packet Identifier with QoS 2 and DUP flag set to 0.
 *
 *
 * Unsure how to test:
 *
 * [MQTT-4.3.2-5] In the QoS 1 delivery protocol, the receiver after it has sent a PUBACK packet the receiver MUST treat any incoming PUBLISH packet that contains the same Packet Identifier as being a new Application Message, irrespective of the setting of its DUP flag.
 * [MQTT-4.3.3-6] In the QoS 2 delivery protocol, the sender MUST NOT re-send the PUBLISH once it has sent the corresponding PUBREL packet.
 * [MQTT-4.3.3-9] In the QoS 2 delivery protocol, the receiver if it has sent a PUBREC with a Reason Code of 0x80 or greater, the receiver MUST treat any subsequent PUBLISH packet that contains that Packet Identifier as being a new Application Message.
 * [MQTT-4.3.3-10] In the QoS 2 delivery protocol, the receiver until it has received the corresponding PUBREL packet, the receiver MUST acknowledge any subsequent PUBLISH packet with the same Packet Identifier by sending a PUBREC. It MUST NOT cause duplicate messages to be delivered to any onward recipients in this case.
 * [MQTT-4.3.3-12] In the QoS 2 delivery protocol, the receiver After it has sent a PUBCOMP, the receiver MUST treat any subsequent PUBLISH packet that contains that Packet Identifier as being a new Application Message.
 */

public class QoSTests extends MQTT5TestSupport {

   private static final Logger log = Logger.getLogger(QoSTests.class);

   public QoSTests(String protocol) {
      super(protocol);
   }

   /*
    * [MQTT-4.3.2-2] In the QoS 1 delivery protocol, the sender MUST send a PUBLISH packet containing this Packet
    * Identifier with QoS 1 and DUP flag set to 0.
    *
    * This test looks at the PUBLISH packet coming from *the broker* to the client
    */
   @Test(timeout = DEFAULT_TIMEOUT)
   public void testQoS1andDupFlag() throws Exception {
      final String TOPIC = RandomUtil.randomString();

      final CountDownLatch latch = new CountDownLatch(1);
      MqttClient consumer = createPahoClient("consumer");
      consumer.connect();
      consumer.setCallback(new DefaultMqttCallback() {
         @Override
         public void messageArrived(String incomingTopic, MqttMessage message) throws Exception {
            assertEquals(1, message.getQos());
            assertFalse(message.isDuplicate());
            latch.countDown();
         }
      });
      consumer.subscribe(TOPIC, 1);

      MqttClient producer = createPahoClient("producer");
      producer.connect();
      producer.publish(TOPIC, RandomUtil.randomString().getBytes(), 1, false);
      producer.disconnect();
      producer.close();

      assertTrue(latch.await(2, TimeUnit.SECONDS));
      consumer.disconnect();
      consumer.close();
   }

   /*
    * [MQTT-4.3.2-3] In the QoS 1 delivery protocol, the sender MUST treat the PUBLISH packet as “unacknowledged” until
    * it has received the corresponding PUBACK packet from the receiver.
    */
   @Test(timeout = DEFAULT_TIMEOUT)
   public void testQoS1PubAck() throws Exception {
      final String TOPIC = RandomUtil.randomString();
      final CountDownLatch ackLatch = new CountDownLatch(1);
      final AtomicInteger packetId = new AtomicInteger();

      MQTTInterceptor incomingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBACK) {
            // ensure the message is still in the queue before we get the ack from the client
            assertEquals(1, getSubscriptionQueue(TOPIC).getMessageCount());
            assertEquals(1, getSubscriptionQueue(TOPIC).getDeliveringCount());

            // ensure the ids match so we know this is the "corresponding" PUBACK for the previous PUBLISH
            assertEquals(packetId.get(), ((MqttPubReplyMessageVariableHeader)packet.variableHeader()).messageId());

            ackLatch.countDown();
         }
         return true;
      };

      MQTTInterceptor outgoingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBLISH) {
            packetId.set(((MqttPublishMessage)packet).variableHeader().packetId());
         }
         return true;
      };

      server.getRemotingService().addIncomingInterceptor(incomingInterceptor);
      server.getRemotingService().addOutgoingInterceptor(outgoingInterceptor);

      final CountDownLatch latch = new CountDownLatch(1);
      MqttClient consumer = createPahoClient("consumer");
      consumer.connect();
      consumer.setCallback(new LatchedMqttCallback(latch));
      consumer.subscribe(TOPIC, 1);

      MqttClient producer = createPahoClient("producer");
      producer.connect();
      producer.publish(TOPIC, RandomUtil.randomString().getBytes(), 1, false);
      producer.disconnect();
      producer.close();

      assertTrue(ackLatch.await(2, TimeUnit.SECONDS));
      assertTrue(latch.await(2, TimeUnit.SECONDS));
      assertEquals(0, getSubscriptionQueue(TOPIC).getMessageCount());
      assertEquals(0, getSubscriptionQueue(TOPIC).getDeliveringCount());
      consumer.disconnect();
      consumer.close();
   }

   /*
    * [MQTT-4.3.2-4] In the QoS 1 delivery protocol, the receiver MUST respond with a PUBACK packet containing the
    * Packet Identifier from the incoming PUBLISH packet, having accepted ownership of the Application Message.
    */
   @Test(timeout = DEFAULT_TIMEOUT)
   public void testQoS1PubAckId() throws Exception {
      final String TOPIC = RandomUtil.randomString();
      final CountDownLatch ackLatch = new CountDownLatch(1);
      final AtomicInteger packetId = new AtomicInteger();

      MQTTInterceptor incomingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBLISH) {
            packetId.set(((MqttPublishMessage)packet).variableHeader().packetId());
         }
         return true;
      };

      MQTTInterceptor outgoingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBACK) {
            assertEquals(packetId.get(), ((MqttPubAckMessage)packet).variableHeader().messageId());
            ackLatch.countDown();
         }
         return true;
      };
      server.getRemotingService().addIncomingInterceptor(incomingInterceptor);
      server.getRemotingService().addOutgoingInterceptor(outgoingInterceptor);

      final CountDownLatch latch = new CountDownLatch(1);
      MqttClient consumer = createPahoClient("consumer");
      consumer.connect();
      consumer.setCallback(new LatchedMqttCallback(latch));
      consumer.subscribe(TOPIC, 1);

      MqttClient producer = createPahoClient("producer");
      producer.connect();
      producer.publish(TOPIC, RandomUtil.randomString().getBytes(), 1, false);
      producer.disconnect();
      producer.close();

      assertTrue(ackLatch.await(2, TimeUnit.SECONDS));
      assertTrue(latch.await(2, TimeUnit.SECONDS));
      consumer.disconnect();
      consumer.close();
   }

   /*
    * QoS 2 exactly-once delivery semantics. This diagram was adapted from:
    *   https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901237
    *
    * ================================================================================
    * |      Sender Action      |   MQTT Control Packet    |     Receiver Action     |
    * |-------------------------|--------------------------|-------------------------|
    * | Store Message           |                          |                         |
    * --------------------------------------------------------------------------------
    * | PUBLISH QoS 2, DUP=0    |                          |                         |
    * | <Packet ID>             |                          |                         |
    * |-------------------------|--------------------------|-------------------------|
    * |                         |          =====>          |                         |
    * |-------------------------|--------------------------|-------------------------|
    * |                         |                          |Store <Packet ID> then   |
    * |                         |                          |initiate onward delivery |
    * |                         |                          |of the Application       |
    * |                         |                          |Message                  |
    * |-------------------------|--------------------------|-------------------------|
    * |                         |                          |PUBREC <Packet ID>       |
    * |                         |                          |<Reason Code>            |
    * |-------------------------|--------------------------|-------------------------|
    * |                         |          <=====          |                         |
    * |-------------------------|--------------------------|-------------------------|
    * | Discard message, store  |                          |                         |
    * | PUBREC <Packet ID>      |                          |                         |
    * |-------------------------|--------------------------|-------------------------|
    * | PUBREL <Packet ID>      |                          |                         |
    * |-------------------------|--------------------------|-------------------------|
    * |                         |          =====>          |                         |
    * |-------------------------|--------------------------|-------------------------|
    * |                         |                          |Discard <Packet ID>      |
    * |-------------------------|--------------------------|-------------------------|
    * |                         |                          |Send PUBCOMP <Packet ID> |
    * |-------------------------|--------------------------|-------------------------|
    * |                         |          <=====          |                         |
    * |-------------------------|--------------------------|-------------------------|
    * | Discard stored state    |                          |                         |
    * ================================================================================
    */

   /*
    * [MQTT-4.3.3-3] In the QoS 2 delivery protocol, the sender MUST treat the PUBLISH packet as “unacknowledged” until
    * it has received the corresponding PUBREC packet from the receiver.
    */
   @Test(timeout = DEFAULT_TIMEOUT)
   public void testQoS2PubRec() throws Exception {
      final String TOPIC = RandomUtil.randomString();
      final CountDownLatch ackLatch = new CountDownLatch(1);
      final AtomicInteger packetId = new AtomicInteger();

      MQTTInterceptor incomingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBREC) {
            // ensure the message is still in the queue before we get the ack from the client
            assertEquals(1, getSubscriptionQueue(TOPIC).getMessageCount());
            assertEquals(1, getSubscriptionQueue(TOPIC).getDeliveringCount());

            // ensure the ids match so we know this is the "corresponding" PUBREC for the previous PUBLISH
            assertEquals(packetId.get(), ((MqttPubReplyMessageVariableHeader)packet.variableHeader()).messageId());

            ackLatch.countDown();
         }
         return true;
      };

      MQTTInterceptor outgoingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBLISH) {
            packetId.set(((MqttPublishMessage)packet).variableHeader().packetId());
         }
         return true;
      };

      server.getRemotingService().addIncomingInterceptor(incomingInterceptor);
      server.getRemotingService().addOutgoingInterceptor(outgoingInterceptor);

      final CountDownLatch latch = new CountDownLatch(1);
      MqttClient consumer = createPahoClient("consumer");
      consumer.connect();
      consumer.setCallback(new LatchedMqttCallback(latch));
      consumer.subscribe(TOPIC, 2);

      MqttClient producer = createPahoClient("producer");
      producer.connect();
      producer.publish(TOPIC, RandomUtil.randomString().getBytes(), 2, false);
      producer.disconnect();
      producer.close();

      assertTrue(ackLatch.await(2, TimeUnit.SECONDS));
      assertTrue(latch.await(2, TimeUnit.SECONDS));
      assertEquals(0, getSubscriptionQueue(TOPIC).getMessageCount());
      assertEquals(0, getSubscriptionQueue(TOPIC).getDeliveringCount());
      consumer.disconnect();
      consumer.close();
   }

   /*
    * [MQTT-4.3.3-4] In the QoS 2 delivery protocol, the sender MUST send a PUBREL packet when it receives a PUBREC
    * packet from the receiver with a Reason Code value less than 0x80. This PUBREL packet MUST contain the same Packet
    * Identifier as the original PUBLISH packet.
    */
   @Test(timeout = DEFAULT_TIMEOUT)
   public void testQoS2PubRelId() throws Exception {
      final String TOPIC = RandomUtil.randomString();
      final CountDownLatch ackLatch = new CountDownLatch(1);
      final AtomicInteger packetId = new AtomicInteger();
      final AtomicBoolean pubRecReceived = new AtomicBoolean(false);

      MQTTInterceptor incomingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBLISH) {
            packetId.set(((MqttPublishMessage)packet).variableHeader().packetId());
         }
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBREC) {
            assertEquals(packetId.get(), ((MqttPubReplyMessageVariableHeader)packet.variableHeader()).messageId());
            assertEquals(MQTTReasonCodes.SUCCESS, ((MqttPubReplyMessageVariableHeader)packet.variableHeader()).reasonCode());
            pubRecReceived.set(true);
         }
         return true;
      };

      MQTTInterceptor outgoingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBREL) {
            assertTrue(pubRecReceived.get());
            assertEquals(packetId.get(), ((MqttPubAckMessage)packet).variableHeader().messageId());
            ackLatch.countDown();
         }
         return true;
      };
      server.getRemotingService().addIncomingInterceptor(incomingInterceptor);
      server.getRemotingService().addOutgoingInterceptor(outgoingInterceptor);

      final CountDownLatch latch = new CountDownLatch(1);
      MqttClient consumer = createPahoClient("consumer");
      consumer.connect();
      consumer.setCallback(new LatchedMqttCallback(latch));
      consumer.subscribe(TOPIC, 2);

      MqttClient producer = createPahoClient("producer");
      producer.connect();
      producer.publish(TOPIC, RandomUtil.randomString().getBytes(), 2, false);
      producer.disconnect();
      producer.close();

      assertTrue(ackLatch.await(2, TimeUnit.SECONDS));
      assertTrue(latch.await(2, TimeUnit.SECONDS));
      consumer.disconnect();
      consumer.close();
   }

   /*
    * [MQTT-4.3.3-5] In the QoS 2 delivery protocol, the sender MUST treat the PUBREL packet as “unacknowledged” until
    * it has received the corresponding PUBCOMP packet from the receiver.
    */
   @Test(timeout = DEFAULT_TIMEOUT)
   public void testQoS2PubRel() throws Exception {
      final String TOPIC = RandomUtil.randomString();
      final String CONSUMER_CLIENT_ID = "consumer";
      final CountDownLatch ackLatch = new CountDownLatch(1);
      final AtomicInteger packetId = new AtomicInteger();

      MQTTInterceptor incomingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBCOMP) {
            try {
               // ensure the message is still in the management queue before we get the PUBCOMP from the client
               Wait.assertEquals(1L, () -> server.locateQueue(MQTTUtil.MANAGEMENT_QUEUE_PREFIX + CONSUMER_CLIENT_ID).getMessageCount(), 2000, 100);
               Wait.assertEquals(1L, () -> server.locateQueue(MQTTUtil.MANAGEMENT_QUEUE_PREFIX + CONSUMER_CLIENT_ID).getDeliveringCount(), 2000, 100);
            } catch (Exception e) {
               return false;
            }

            // ensure the ids match so we know this is the "corresponding" PUBCOMP for the previous PUBLISH
            assertEquals(packetId.get(), ((MqttPubReplyMessageVariableHeader)packet.variableHeader()).messageId());

            ackLatch.countDown();
         }
         return true;
      };

      MQTTInterceptor outgoingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBLISH) {
            packetId.set(((MqttPublishMessage)packet).variableHeader().packetId());
         }
         return true;
      };

      server.getRemotingService().addIncomingInterceptor(incomingInterceptor);
      server.getRemotingService().addOutgoingInterceptor(outgoingInterceptor);

      final CountDownLatch latch = new CountDownLatch(1);
      MqttClient consumer = createPahoClient(CONSUMER_CLIENT_ID);
      consumer.connect();
      consumer.setCallback(new LatchedMqttCallback(latch));
      consumer.subscribe(TOPIC, 2);

      MqttClient producer = createPahoClient("producer");
      producer.connect();
      producer.publish(TOPIC, RandomUtil.randomString().getBytes(), 2, false);
      producer.disconnect();
      producer.close();

      assertTrue(ackLatch.await(2, TimeUnit.SECONDS));
      assertTrue(latch.await(2, TimeUnit.SECONDS));
      assertEquals(0, getSubscriptionQueue(TOPIC).getMessageCount());
      assertEquals(0, getSubscriptionQueue(TOPIC).getDeliveringCount());
      consumer.disconnect();
      consumer.close();
   }

   /*
    * [MQTT-4.3.3-7] In the QoS 2 delivery protocol, the sender MUST NOT apply Application Message expiry if a PUBLISH
    * packet has been sent.
    *
    * [MQTT-4.3.3-13] In the QoS 2 delivery protocol, the receiver MUST continue the QoS 2 acknowledgement sequence even if it has applied Application Message expiry.
    *
    * Due to the nature of the underlying queue semantics once a message is "in delivery" it's no longer available for
    * expiration. This test demonstrates that.
    */
   @Test(timeout = DEFAULT_TIMEOUT)
   public void testQoS2WithExpiration() throws Exception {
      final String TOPIC = "myTopic";
      final CountDownLatch ackLatch = new CountDownLatch(1);
      final CountDownLatch expireRefsLatch = new CountDownLatch(1);
      final long messageExpiryInterval = 2;

      MQTTInterceptor incomingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBREC) {
            // ensure the message is still in the queue before we get the PUBREC from the client
            assertEquals(1, getSubscriptionQueue(TOPIC).getMessageCount());
            assertEquals(1, getSubscriptionQueue(TOPIC).getDeliveringCount());
            try {
               // ensure enough time has passed for the message to expire
               Thread.sleep(messageExpiryInterval * 1500);
               getSubscriptionQueue(TOPIC).expireReferences(expireRefsLatch::countDown);
               assertTrue(expireRefsLatch.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
               e.printStackTrace();
               fail();
            }
            ackLatch.countDown();
         }
         return true;
      };

      server.getRemotingService().addIncomingInterceptor(incomingInterceptor);

      final CountDownLatch latch = new CountDownLatch(1);
      MqttClient consumer = createPahoClient("consumer");
      consumer.connect();
      consumer.setCallback(new DefaultMqttCallback() {
         @Override
         public void messageArrived(String topic, MqttMessage message) throws Exception {
            latch.countDown();
         }
      });
      consumer.subscribe(TOPIC, 2);

      MqttClient producer = createPahoClient("producer");
      producer.connect();
      MqttMessage m = new MqttMessage();
      MqttProperties props = new MqttProperties();
      props.setMessageExpiryInterval(messageExpiryInterval);
      m.setProperties(props);
      m.setQos(2);
      m.setPayload("foo".getBytes(StandardCharsets.UTF_8));
      producer.publish(TOPIC, m);
      producer.disconnect();
      producer.close();

      assertTrue(ackLatch.await(messageExpiryInterval * 2, TimeUnit.SECONDS));
      assertTrue(latch.await(messageExpiryInterval * 2, TimeUnit.SECONDS));
      Wait.assertEquals(0, () -> getSubscriptionQueue(TOPIC).getMessageCount());
      Wait.assertEquals(0, () -> getSubscriptionQueue(TOPIC).getDeliveringCount());
      Wait.assertEquals(0, () -> getSubscriptionQueue(TOPIC).getMessagesExpired());
      consumer.disconnect();
      consumer.close();
   }

   /*
    * [MQTT-4.3.3-8] In the QoS 2 delivery protocol, the receiver MUST respond with a PUBREC containing the Packet
    * Identifier from the incoming PUBLISH packet, having accepted ownership of the Application Message.
    */
   @Test(timeout = DEFAULT_TIMEOUT)
   public void testQoS2PubRecId() throws Exception {
      final String TOPIC = RandomUtil.randomString();
      final CountDownLatch ackLatch = new CountDownLatch(1);
      final AtomicInteger packetId = new AtomicInteger();

      MQTTInterceptor incomingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBLISH) {
            packetId.set(((MqttPublishMessage)packet).variableHeader().packetId());
         }
         return true;
      };

      MQTTInterceptor outgoingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBREC) {
            assertEquals(packetId.get(), ((MqttPubAckMessage)packet).variableHeader().messageId());
            ackLatch.countDown();
         }
         return true;
      };
      server.getRemotingService().addIncomingInterceptor(incomingInterceptor);
      server.getRemotingService().addOutgoingInterceptor(outgoingInterceptor);

      final CountDownLatch latch = new CountDownLatch(1);
      MqttClient consumer = createPahoClient("consumer");
      consumer.connect();
      consumer.setCallback(new LatchedMqttCallback(latch));
      consumer.subscribe(TOPIC, 2);

      MqttClient producer = createPahoClient("producer");
      producer.connect();
      producer.publish(TOPIC, RandomUtil.randomString().getBytes(), 2, false);
      producer.disconnect();
      producer.close();

      assertTrue(ackLatch.await(2, TimeUnit.SECONDS));
      assertTrue(latch.await(2, TimeUnit.SECONDS));
      consumer.disconnect();
      consumer.close();
   }

   /*
    * [MQTT-4.3.3-11] In the QoS 2 delivery protocol, the receiver MUST respond to a PUBREL packet by sending a PUBCOMP
    * packet containing the same Packet Identifier as the PUBREL.
    */
   @Test(timeout = DEFAULT_TIMEOUT)
   public void testQoS2PubCompId() throws Exception {
      final String TOPIC = RandomUtil.randomString();
      final CountDownLatch ackLatch = new CountDownLatch(1);
      final AtomicInteger packetId = new AtomicInteger();

      MQTTInterceptor incomingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBREL) {
            packetId.set(((MqttPubReplyMessageVariableHeader)packet.variableHeader()).messageId());
         }
         return true;
      };

      MQTTInterceptor outgoingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBCOMP) {
            assertEquals(packetId.get(), ((MqttPubAckMessage)packet).variableHeader().messageId());
            ackLatch.countDown();
         }
         return true;
      };
      server.getRemotingService().addIncomingInterceptor(incomingInterceptor);
      server.getRemotingService().addOutgoingInterceptor(outgoingInterceptor);

      final CountDownLatch latch = new CountDownLatch(1);
      MqttClient consumer = createPahoClient("consumer");
      consumer.connect();
      consumer.setCallback(new LatchedMqttCallback(latch));
      consumer.subscribe(TOPIC, 2);

      MqttClient producer = createPahoClient("producer");
      producer.connect();
      producer.publish(TOPIC, RandomUtil.randomString().getBytes(), 2, false);
      producer.disconnect();
      producer.close();

      assertTrue(ackLatch.await(2, TimeUnit.SECONDS));
      assertTrue(latch.await(2, TimeUnit.SECONDS));
      consumer.disconnect();
      consumer.close();
   }

   /*
    * [MQTT-4.3.3-13] In the QoS 2 delivery protocol, the receiver MUST continue the QoS 2 acknowledgement sequence even
    * if it has applied Application Message expiry.
    */
   @Test(timeout = DEFAULT_TIMEOUT)
   public void testQoS2WithExpiration2() throws Exception {
      final String TOPIC = "myTopic";
      server.createQueue(new QueueConfiguration(RandomUtil.randomString()).setAddress(TOPIC).setRoutingType(RoutingType.MULTICAST));
      final CountDownLatch ackLatch = new CountDownLatch(1);
      final CountDownLatch expireRefsLatch = new CountDownLatch(1);
      final long messageExpiryInterval = 1;

      MQTTInterceptor outgoingInterceptor = (packet, connection) -> {
         if (packet.fixedHeader().messageType() == MqttMessageType.PUBREC) {
            // ensure the message is in the queue before trying to expire
            Wait.assertTrue(() -> getSubscriptionQueue(TOPIC).getMessageCount() == 1, 2000, 100);
            try {
               // ensure enough time has passed for the message to expire
               Thread.sleep(messageExpiryInterval * 1500);
               getSubscriptionQueue(TOPIC).expireReferences(expireRefsLatch::countDown);
               assertTrue(expireRefsLatch.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
               e.printStackTrace();
               fail();
            }
            ackLatch.countDown();
         }
         return true;
      };

      server.getRemotingService().addOutgoingInterceptor(outgoingInterceptor);

      MqttClient producer = createPahoClient("producer");
      producer.connect();
      MqttMessage m = new MqttMessage();
      MqttProperties props = new MqttProperties();
      props.setMessageExpiryInterval(messageExpiryInterval);
      m.setProperties(props);
      m.setQos(2);
      m.setPayload("foo".getBytes(StandardCharsets.UTF_8));
      producer.publish(TOPIC, m);
      producer.disconnect();
      producer.close();

      assertTrue(ackLatch.await(messageExpiryInterval * 2, TimeUnit.SECONDS));
      Wait.assertEquals(1, () -> getSubscriptionQueue(TOPIC).getMessagesExpired());
   }
}