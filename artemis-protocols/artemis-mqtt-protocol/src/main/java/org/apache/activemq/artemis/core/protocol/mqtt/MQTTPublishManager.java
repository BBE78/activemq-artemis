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

package org.apache.activemq.artemis.core.protocol.mqtt;

import java.util.Arrays;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.EmptyByteBuf;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessageType;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttQoS;
import org.apache.activemq.artemis.api.core.ActiveMQBuffer;
import org.apache.activemq.artemis.api.core.ActiveMQIllegalStateException;
import org.apache.activemq.artemis.api.core.ActiveMQSecurityException;
import org.apache.activemq.artemis.api.core.ICoreMessage;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.Pair;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.io.IOCallback;
import org.apache.activemq.artemis.core.protocol.mqtt.exceptions.DisconnectException;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.core.server.impl.ServerSessionImpl;
import org.apache.activemq.artemis.core.transaction.Transaction;
import org.apache.activemq.artemis.reader.MessageUtil;
import org.jboss.logging.Logger;

import static io.netty.handler.codec.mqtt.MqttProperties.MqttPropertyType.CONTENT_TYPE;
import static io.netty.handler.codec.mqtt.MqttProperties.MqttPropertyType.CORRELATION_DATA;
import static io.netty.handler.codec.mqtt.MqttProperties.MqttPropertyType.PAYLOAD_FORMAT_INDICATOR;
import static io.netty.handler.codec.mqtt.MqttProperties.MqttPropertyType.PUBLICATION_EXPIRY_INTERVAL;
import static io.netty.handler.codec.mqtt.MqttProperties.MqttPropertyType.RESPONSE_TOPIC;
import static io.netty.handler.codec.mqtt.MqttProperties.MqttPropertyType.SUBSCRIPTION_IDENTIFIER;
import static io.netty.handler.codec.mqtt.MqttProperties.MqttPropertyType.TOPIC_ALIAS;
import static org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil.MQTT_CONTENT_TYPE_KEY;
import static org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil.MQTT_CORRELATION_DATA_KEY;
import static org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil.MQTT_MESSAGE_RETAIN_KEY;
import static org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil.MQTT_PAYLOAD_FORMAT_INDICATOR_KEY;
import static org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil.MQTT_RESPONSE_TOPIC_KEY;
import static org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil.MQTT_USER_PROPERTY_EXISTS_KEY;
import static org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil.MQTT_USER_PROPERTY_KEY_PREFIX_SIMPLE;

/**
 * Handles MQTT Exactly Once (QoS level 2) Protocol.
 */
public class MQTTPublishManager {

   private static final Logger logger = Logger.getLogger(MQTTPublishManager.class);

   private SimpleString managementAddress;

   private ServerConsumer managementConsumer;

   private MQTTSession session;

   private MQTTLogger log = MQTTLogger.LOGGER;

   private final Object lock = new Object();

   private MQTTSessionState state;

   private MQTTSessionState.OutboundStore outboundStore;

   public MQTTPublishManager(MQTTSession session) {
      this.session = session;
   }

   synchronized void start() {
      this.state = session.getState();
      this.outboundStore = state.getOutboundStore();
   }

   synchronized void stop() throws Exception {
      ServerSessionImpl serversession = session.getServerSession();
      if (serversession != null) {
         serversession.removeProducer(serversession.getName());
      }
      if (managementConsumer != null) {
         managementConsumer.removeItself();
         managementConsumer.setStarted(false);
         managementConsumer.close(false);
      }
   }

   void clean() throws Exception {
      SimpleString managementAddress = createManagementAddress();
      Queue queue = session.getServer().locateQueue(managementAddress);
      if (queue != null) {
         queue.deleteQueue();
      }
   }

   private void createManagementConsumer() throws Exception {
      long consumerId = session.getServer().getStorageManager().generateID();
      managementConsumer = session.getInternalServerSession().createConsumer(consumerId, managementAddress, null, false, false, -1);
      managementConsumer.setStarted(true);
   }

   private SimpleString createManagementAddress() {
      return new SimpleString(MQTTUtil.MANAGEMENT_QUEUE_PREFIX + session.getState().getClientId());
   }

   private void createManagementQueue() throws Exception {
      Queue q = session.getServer().locateQueue(managementAddress);
      if (q == null) {
         session.getServer().createQueue(new QueueConfiguration(managementAddress)
                                            .setRoutingType(RoutingType.ANYCAST)
                                            .setDurable(MQTTUtil.DURABLE_MESSAGES));
      }
   }

   boolean isManagementConsumer(ServerConsumer consumer) {
      return consumer == managementConsumer;
   }

   /**
    * Since MQTT Subscriptions can overlap, a client may receive the same message twice. When this happens the client
    * returns a PubRec or PubAck with ID.  But we need to know which consumer to ack, since we only have the ID to go on
    * we are not able to decide which consumer to ack. Instead we send MQTT messages with different IDs and store a
    * reference to original ID and consumer in the Session state. This way we can look up the consumer Id and the
    * message Id from the PubAck or PubRec message id.
    */
   protected void sendMessage(ICoreMessage message, ServerConsumer consumer, int deliveryCount) throws Exception {
      // This is to allow retries of PubRel.
      if (isManagementConsumer(consumer)) {
         sendPubRelMessage(message);
      } else {
         int qos = decideQoS(message, consumer);
         if (qos == 0) {
            if (publishToClient((int) message.getMessageID(), message, deliveryCount, qos, consumer.getID())) {
               session.getServerSession().individualAcknowledge(consumer.getID(), message.getMessageID());
            }
         } else if (qos == 1 || qos == 2) {
            int mqttid = outboundStore.generateMqttId(message.getMessageID(), consumer.getID());
            outboundStore.publish(mqttid, message.getMessageID(), consumer.getID());
            publishToClient(mqttid, message, deliveryCount, qos, consumer.getID());
         } else {
            // Client must have disconnected and it's Subscription QoS cleared
            consumer.individualCancel(message.getMessageID(), false);
         }
      }
   }

   /**
    * Sends a message either on behalf of the client or on behalf of the broker (Will Messages)
    *
    * @param internal if true means on behalf of the broker (skips authorisation) and does not return ack.
    * @throws Exception
    */
   void sendToQueue(MqttPublishMessage message, boolean internal) throws Exception {
      synchronized (lock) {
         String topic = message.variableHeader().topicName();
         if (session.is5()) {
            Integer alias = MQTTUtil.getProperty(Integer.class, message.variableHeader().properties(), TOPIC_ALIAS);
            Integer topicAliasMax = session.getProtocolManager().getTopicAliasMaximum();
            if (alias != null) {
               if (alias == 0) {
                  // [MQTT-3.3.2-8]
                  throw new DisconnectException(MQTTReasonCodes.TOPIC_ALIAS_INVALID);
               } else if (topicAliasMax != null && alias > topicAliasMax) {
                  // [MQTT-3.3.2-9]
                  throw new DisconnectException(MQTTReasonCodes.TOPIC_ALIAS_INVALID);
               } else {
                  topic = session.getState().getClientTopicAlias(alias);
                  if (topic == null) {
                     topic = message.variableHeader().topicName();
                     session.getState().addClientTopicAlias(alias, topic);
                  }
               }
            } else {
               topic = message.variableHeader().topicName();
            }
         }

         Message serverMessage = MQTTUtil.createServerMessageFromByteBuf(session, topic, message);
         int qos = message.fixedHeader().qosLevel().value();
         if (qos > 0) {
            serverMessage.setDurable(MQTTUtil.DURABLE_MESSAGES);
         }

         int messageId = message.variableHeader().packetId();
         if (qos < 2 || !state.getPubRec().contains(messageId)) {
            if (qos == 2 && !internal)
               state.getPubRec().add(messageId);

            Transaction tx = session.getServerSession().newTransaction();
            try {
               if (internal) {
                  session.getServer().getPostOffice().route(serverMessage, tx, true);
               } else {
                  session.getServerSession().send(tx, serverMessage, true, false);
               }

               if (message.fixedHeader().isRetain()) {
                  ByteBuf payload = message.payload();
                  boolean reset = payload instanceof EmptyByteBuf || payload.capacity() == 0;
                  session.getRetainMessageManager().handleRetainedMessage(serverMessage, topic, reset, tx);
               }
               tx.commit();
            } catch (ActiveMQSecurityException e) {
               tx.rollback();
               if (session.is5()) {
                  sendMessageAck(internal, qos, messageId, MQTTReasonCodes.NOT_AUTHORIZED);
                  return;
               } else {
                  /*
                   * For MQTT 3.x clients:
                   *
                   * [MQTT-3.3.5-2] If a Server implementation does not authorize a PUBLISH to be performed by a Client;
                   * it has no way of informing that Client. It MUST either make a positive acknowledgement, according
                   * to the normal QoS rules, or close the Network Connection
                   */
                  throw e;
               }
            } catch (Throwable t) {
               MQTTLogger.LOGGER.failedToPublishMqttMessage(t.getMessage(), t);
               tx.rollback();
               throw t;
            }
            createMessageAck(messageId, qos, internal);
         }
      }
   }

   private void sendMessageAck(boolean internal, int qos, int messageId, byte reasonCode) {
      if (!internal) {
         if (qos == 1) {
            session.getProtocolHandler().sendPubAck(messageId, reasonCode);
         } else if (qos == 2) {
            session.getProtocolHandler().sendPubRec(messageId, reasonCode);
         }
      }
   }

   void sendPubRelMessage(Message message) {
      int messageId = message.getIntProperty(MQTTUtil.MQTT_MESSAGE_ID_KEY);
      session.getState().getOutboundStore().publishReleasedSent(messageId, message.getMessageID());
      session.getProtocolHandler().sendPubRel(messageId);
   }

   private SimpleString getManagementAddress() throws Exception {
      if (managementAddress == null) {
         managementAddress = createManagementAddress();
         createManagementQueue();
         createManagementConsumer();
      }
      return managementAddress;
   }

   void handlePubRec(int messageId) throws Exception {
      try {
         Pair<Long, Long> ref = outboundStore.publishReceived(messageId);
         if (ref != null) {
            Message m = MQTTUtil.createPubRelMessage(session, getManagementAddress(), messageId);
            //send the management message via the internal server session to bypass security.
            session.getInternalServerSession().send(m, true);
            session.getServerSession().individualAcknowledge(ref.getB(), ref.getA());
            releaseFlowControl(ref.getB());
         } else {
            session.getProtocolHandler().sendPubRel(messageId);
         }
      } catch (ActiveMQIllegalStateException e) {
         MQTTLogger.LOGGER.failedToAckMessage(session.getState().getClientId(), e);
      }
   }

   /**
    * Once we get an acknowledgement for a QoS 1 or 2 message we allow messages to flow
    *
    * @param consumerId
    */
   private void releaseFlowControl(Long consumerId) {
      ServerConsumer consumer = session.getServerSession().locateConsumer(consumerId);
      if (consumer != null) {
         consumer.promptDelivery();
      }
   }

   void handlePubComp(int messageId) throws Exception {
      Pair<Long, Long> ref = session.getState().getOutboundStore().publishComplete(messageId);
      if (ref != null) {
         // ack the message via the internal server session to bypass security.
         session.getInternalServerSession().individualAcknowledge(managementConsumer.getID(), ref.getA());
      }
   }

   private void createMessageAck(final int messageId, final int qos, final boolean internal) {
      session.getServer().getStorageManager().afterCompleteOperations(new IOCallback() {
         @Override
         public void done() {
            sendMessageAck(internal, qos, messageId, MQTTReasonCodes.SUCCESS);
         }

         @Override
         public void onError(int errorCode, String errorMessage) {
            log.error("Pub Sync Failed");
         }
      });
   }

   void handlePubRel(int messageId) {
      // We don't check to see if a PubRel existed for this message.  We assume it did and so send PubComp.
      state.getPubRec().remove(messageId);
      session.getProtocolHandler().sendPubComp(messageId);
      state.removeMessageRef(messageId);
   }

   void handlePubAck(int messageId) throws Exception {
      try {
         Pair<Long, Long> ref = outboundStore.publishAckd(messageId);
         if (ref != null) {
            session.getServerSession().individualAcknowledge(ref.getB(), ref.getA());
            releaseFlowControl(ref.getB());
         }
      } catch (ActiveMQIllegalStateException e) {
         log.warn("MQTT Client(" + session.getState().getClientId() + ") attempted to Ack already Ack'd message");
      }
   }

   private boolean publishToClient(int messageId, ICoreMessage message, int deliveryCount, int qos, long consumerId) throws Exception {
      String address = MQTTUtil.convertCoreAddressToMqttTopicFilter(message.getAddress() == null ? "" : message.getAddress(), session.getWildcardConfiguration());

      ByteBuf payload;
      switch (message.getType()) {
         case Message.TEXT_TYPE:
            SimpleString text = message.getDataBuffer().readNullableSimpleString();
            final int utf8Bytes = ByteBufUtil.utf8Bytes(text);
            payload = ByteBufAllocator.DEFAULT.directBuffer(utf8Bytes);
            // IMPORTANT: this one won't enlarge ByteBuf by ByteBufUtil.maxUtf8Bytes(text), but just utf8Bytes
            ByteBufUtil.reserveAndWriteUtf8(payload, text, utf8Bytes);
            break;
         default:
            ActiveMQBuffer bodyBuffer = message.getDataBuffer();
            payload = ByteBufAllocator.DEFAULT.directBuffer(bodyBuffer.writerIndex());
            payload.writeBytes(bodyBuffer.byteBuf());
            break;
      }

      // [MQTT-3.3.1-2] The DUP flag MUST be set to 0 for all QoS 0 messages.
      boolean redelivery = qos == 0 ? false : (deliveryCount > 1);

      boolean isRetain = message.getBooleanProperty(MQTT_MESSAGE_RETAIN_KEY);
      MqttProperties mqttProperties = getPublishProperties(message);

      if (session.is5()) {
         if (session.getState().getSubscription(message.getAddress()) != null && !session.getState().getSubscription(message.getAddress()).option().isRetainAsPublished()) {
            isRetain = false;
         }

         // [MQTT-3.8.3-3] remove property used for no-local implementation
         message.removeProperty(MessageUtil.CONNECTION_ID_PROPERTY_NAME);

         if (session.getState().getClientTopicAliasMaximum() != null) {
            Integer alias = session.getState().getServerTopicAlias(address);
            if (alias == null) {
               alias = session.getState().addServerTopicAlias(address);
               if (alias != null) {
                  mqttProperties.add(new MqttProperties.IntegerProperty(TOPIC_ALIAS.value(), alias));
               }
            } else {
               mqttProperties.add(new MqttProperties.IntegerProperty(TOPIC_ALIAS.value(), alias));
               address = "";
            }
         }
      }

      int remainingLength = MQTTUtil.calculateRemainingLength(address, mqttProperties, payload);
      MqttFixedHeader header = new MqttFixedHeader(MqttMessageType.PUBLISH, redelivery, MqttQoS.valueOf(qos), isRetain, remainingLength);
      MqttPublishVariableHeader varHeader = new MqttPublishVariableHeader(address, messageId, mqttProperties);
      MqttPublishMessage publish = new MqttPublishMessage(header, varHeader, payload);

      int maxSize = session.getState().getClientMaxPacketSize();
      if (session.is5() && maxSize != 0) {
         int size = MQTTUtil.calculateMessageSize(publish);
         if (size > maxSize) {
            /*
             * [MQTT-3.1.2-25] Where a Packet is too large to send, the Server MUST discard it without sending it and then
             * behave as if it had completed sending that Application Message
             */
            logger.debugf("Not sending message %s to client as its size (%d) exceeds the max (%d)", message, size, maxSize);
            session.getServerSession().individualAcknowledge(consumerId, message.getMessageID());
            return false;
         }
      }

      session.getProtocolHandler().sendToClient(publish);
      return true;
   }

   private MqttProperties getPublishProperties(ICoreMessage message) {
      MqttProperties props = new MqttProperties();
      if (message.containsProperty(MQTT_PAYLOAD_FORMAT_INDICATOR_KEY)) {
         props.add(new MqttProperties.IntegerProperty(PAYLOAD_FORMAT_INDICATOR.value(), message.getIntProperty(MQTT_PAYLOAD_FORMAT_INDICATOR_KEY)));
      }

      if (message.containsProperty(MQTT_RESPONSE_TOPIC_KEY)) {
         props.add(new MqttProperties.StringProperty(RESPONSE_TOPIC.value(), message.getStringProperty(MQTT_RESPONSE_TOPIC_KEY)));
      }

      if (message.containsProperty(MQTT_CORRELATION_DATA_KEY)) {
         props.add(new MqttProperties.BinaryProperty(CORRELATION_DATA.value(), message.getBytesProperty(MQTT_CORRELATION_DATA_KEY)));
      }

      if (message.containsProperty(MQTT_USER_PROPERTY_EXISTS_KEY)) {
         MqttProperties.StringPair[] orderedProperties = new MqttProperties.StringPair[message.getIntProperty(MQTT_USER_PROPERTY_EXISTS_KEY)];
         for (SimpleString propertyName : message.getPropertyNames()) {
            if (propertyName.startsWith(MQTT_USER_PROPERTY_KEY_PREFIX_SIMPLE)) {
               SimpleString[] split = propertyName.split('.');
               int position = Integer.valueOf(split[4].toString());
               String key = propertyName.subSeq(MQTT_USER_PROPERTY_KEY_PREFIX_SIMPLE.length() + split[4].length() + 1, propertyName.length()).toString();
               orderedProperties[position] = new MqttProperties.StringPair(key, message.getStringProperty(propertyName));
            }
         }
         props.add(new MqttProperties.UserProperties(Arrays.asList(orderedProperties)));
      }

      if (message.containsProperty(MQTT_CONTENT_TYPE_KEY)) {
         props.add(new MqttProperties.StringProperty(CONTENT_TYPE.value(), message.getStringProperty(MQTT_CONTENT_TYPE_KEY)));
      }

      List<Integer> subscriptionIdentifiers = session.getState().getMatchingSubscriptionIdentifiers(message.getAddress());
      if (subscriptionIdentifiers != null) {
         for (Integer id : subscriptionIdentifiers) {
            props.add(new MqttProperties.IntegerProperty(SUBSCRIPTION_IDENTIFIER.value(), id));
         }
      }

      if (message.getExpiration() != 0) {
         /*
          * [MQTT-3.3.2-6] The PUBLISH packet sent to a Client by the Server MUST contain a Message Expiry Interval set
          * to the received value minus the time that the Application Message has been waiting in the Server.
          *
          * Therefore, calculate how much time is left until the message expires rounded to the nearest *second*.
          */
         int messageExpiryInterval = (int) Math.round(((message.getExpiration() - System.currentTimeMillis()) / 1_000_000.0000) * 1000);
         props.add(new MqttProperties.IntegerProperty(PUBLICATION_EXPIRY_INTERVAL.value(), messageExpiryInterval));
      }
      return props;
   }

   private int decideQoS(Message message, ServerConsumer consumer) {
      int subscriptionQoS = -1;
      try {
         subscriptionQoS = session.getSubscriptionManager().getConsumerQoSLevels().get(consumer.getID());
      } catch (NullPointerException e) {
         // This can happen if the client disconnected during a server send.
         return subscriptionQoS;
      }

      int qos = 2;
      if (message.containsProperty(MQTTUtil.MQTT_QOS_LEVEL_KEY)) {
         qos = message.getIntProperty(MQTTUtil.MQTT_QOS_LEVEL_KEY);
      }

      /*
       * Subscription QoS is the maximum QoS the client is willing to receive for this subscription.  If the message QoS
       * is less than the subscription QoS then use it, otherwise use the subscription qos).
       */
      return subscriptionQoS < qos ? subscriptionQoS : qos;
   }
}
