package com.oficina.billing.infrastructure.messaging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import com.oficina.billing.application.exception.InvalidCommandException;
import com.oficina.billing.application.port.out.MessageCodecPort;
import com.oficina.billing.application.usecase.CommandHandler;
import com.oficina.billing.domain.enums.InboxStatus;

@Component
public class CommandConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandConsumer.class);
    private final MessageCodecPort codec;
    private final CommandHandler handler;
    private final RabbitMessageBrokerAdapter broker;
    private final MessagingProperties properties;

    public CommandConsumer(MessageCodecPort codec, CommandHandler handler,
                           RabbitMessageBrokerAdapter broker, MessagingProperties properties) {
        this.codec = codec;
        this.handler = handler;
        this.broker = broker;
        this.properties = properties;
    }

    @RabbitListener(queues = {"billing.quote.create", "billing.payment.request"},
            autoStartup = "${billing.messaging.enabled:true}")
    public void consume(Message message, Channel channel) throws IOException {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        boolean rejected;
        try {
            rejected = handler.handle(codec.readCommand(body, routingKey), body) == InboxStatus.REJECTED;
        } catch (InvalidCommandException exception) {
            rejected = true;
        } catch (RuntimeException exception) {
            forward(message, routingKey, false, channel);
            return;
        }
        if (rejected) {
            forward(message, routingKey, true, channel);
        } else {
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        }
    }

    private void forward(Message original, String routingKey, boolean permanent, Channel channel) throws IOException {
        Object value = original.getMessageProperties().getHeader("billing-attempt");
        long attempt = value instanceof Number number ? Math.max(0, number.longValue()) : 0;
        boolean dead = permanent || attempt >= properties.maxDeliveries() - 1L;
        var metadata = MessagePropertiesBuilder.fromClonedProperties(original.getMessageProperties()).build();
        metadata.setHeader("billing-attempt", Math.min(Integer.MAX_VALUE, attempt + 1));
        metadata.setHeader("billing-result", dead ? "rejected" : "retry");
        metadata.setExpiration(null);
        try {
            broker.send(dead ? RabbitMessagingConfig.DLX : RabbitMessagingConfig.RETRY,
                    routingKey, new Message(original.getBody(), metadata));
            channel.basicAck(original.getMessageProperties().getDeliveryTag(), false);
            LOGGER.warn("command eventType={} attempt={} result={}", routingKey, attempt + 1, dead ? "dlq" : "retry");
        } catch (RuntimeException exception) {
            LOGGER.warn("command eventType={} result=recovery-channel-closed", routingKey);
            channel.abort();
        }
    }
}
