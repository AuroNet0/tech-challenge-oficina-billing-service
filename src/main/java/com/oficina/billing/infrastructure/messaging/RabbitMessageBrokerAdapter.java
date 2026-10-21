package com.oficina.billing.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import com.oficina.billing.application.port.out.MessageBrokerPort;
import com.oficina.billing.domain.model.OutboxEvent;

@Component
public class RabbitMessageBrokerAdapter implements MessageBrokerPort {
    private final RabbitTemplate rabbit;
    private final MessagingProperties properties;

    public RabbitMessageBrokerAdapter(RabbitTemplate rabbit, MessagingProperties properties) {
        this.rabbit = rabbit;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxEvent event) {
        var metadata = new MessageProperties();
        metadata.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        metadata.setContentEncoding(StandardCharsets.UTF_8.name());
        metadata.setMessageId(event.eventId().toString());
        metadata.setCorrelationId(event.correlationId().toString());
        metadata.setHeader("eventType", event.eventType());
        metadata.setHeader("aggregateId", event.aggregateId().toString());
        metadata.setHeader("aggregateType", event.aggregateType());
        send(RabbitMessagingConfig.EVENTS, event.eventType(),
                new Message(event.payload().getBytes(StandardCharsets.UTF_8), metadata));
    }

    public void send(String exchange, String routingKey, Message message) {
        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send(exchange, routingKey, message, correlation);
            var confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null)
                throw new BrokerPublishException("Publicacao sem confirmacao roteavel", null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BrokerPublishException("Confirmacao interrompida", exception);
        } catch (BrokerPublishException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BrokerPublishException("Falha de comunicacao RabbitMQ", exception);
        }
    }
}
