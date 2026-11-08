package com.oficina.billing.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({MessagingProperties.class, OutboxProperties.class})
public class RabbitMessagingConfig {
    public static final String COMMANDS = "oficina.commands";
    public static final String EVENTS = "oficina.events";
    public static final String RETRY = "billing.commands.retry";
    public static final String DLX = "billing.commands.dlx";

    @Bean
    Declarables billingTopology(MessagingProperties properties) {
        var declarations = new java.util.ArrayList<Declarable>();
        declarations.add(new TopicExchange(COMMANDS, true, false));
        declarations.add(new TopicExchange(EVENTS, true, false));
        declarations.add(new TopicExchange(RETRY, true, false));
        declarations.add(new TopicExchange(DLX, true, false));
        declarations.add(new Queue("billing.commands.dlq", true));
        declarations.add(new Binding("billing.commands.dlq", Binding.DestinationType.QUEUE, DLX, "#", null));
        for (var entry : java.util.Map.of("billing.quote.create", "quote.create.v1",
                "billing.payment.request", "payment.request.v1").entrySet()) {
            declarations.add(new Queue(entry.getKey(), true));
            declarations.add(new Binding(entry.getKey(), Binding.DestinationType.QUEUE, COMMANDS, entry.getValue(), null));
            declarations.add(QueueBuilder.durable(entry.getKey() + ".retry")
                    .withArgument("x-message-ttl", properties.retryDelay().toMillis())
                    .deadLetterExchange(COMMANDS).deadLetterRoutingKey(entry.getValue()).build());
            declarations.add(new Binding(entry.getKey() + ".retry", Binding.DestinationType.QUEUE, RETRY, entry.getValue(), null));
        }
        return new Declarables(declarations);
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessagingProperties messaging, OutboxProperties outbox) {
        if (outbox.leaseDuration().compareTo(messaging.confirmTimeout().plusSeconds(3)) <= 0)
            throw new IllegalArgumentException("Lease deve exceder confirm-timeout e connection-timeout");
        var template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setUsePublisherConnection(true);
        return template;
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(2);
        factory.setPrefetchCount(1);
        factory.setRecoveryInterval(5000L);
        factory.setMissingQueuesFatal(false);
        return factory;
    }
}
