package com.oficina.billing.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.rabbitmq.client.Channel;
import com.oficina.billing.application.exception.InvalidCommandException;
import com.oficina.billing.application.port.out.*;
import com.oficina.billing.application.usecase.CommandHandler;
import com.oficina.billing.domain.enums.*;
import com.oficina.billing.domain.model.OutboxEvent;
import com.oficina.billing.infrastructure.messaging.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.*;
import static com.oficina.billing.TestFixtures.NOW;
import static com.oficina.billing.messaging.MessagingFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryPolicyTests {
    @Mock private MessageCodecPort codec;
    @Mock private CommandHandler handler;
    @Mock private RabbitMessageBrokerAdapter broker;
    @Mock private Channel channel;
    @Mock private OutboxRepositoryPort outbox;
    @Mock private MessageBrokerPort eventBroker;

    private CommandConsumer consumer() {
        return new CommandConsumer(codec, handler, broker,
                new MessagingProperties(true, 3, Duration.ofMillis(100), Duration.ofSeconds(1)));
    }

    private Message message() {
        var properties = new MessageProperties();
        properties.setDeliveryTag(123);
        properties.setReceivedRoutingKey("quote.create.v1");
        properties.setExpiration("1000");
        return new Message("body".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
    }

    @Test
    void acknowledgementOccursAfterTransactionalHandlerReturns() throws Exception {
        var message = message();
        var command = quote();
        when(codec.readCommand("body", "quote.create.v1")).thenReturn(command);
        when(handler.handle(command, "body")).thenReturn(InboxStatus.PROCESSED);
        consumer().consume(message, channel);
        var ordered = inOrder(handler, channel);
        ordered.verify(handler).handle(command, "body");
        ordered.verify(channel).basicAck(123, false);
        verifyNoInteractions(broker);
    }

    @Test
    void permanentRejectionIsForwardedBeforeAcknowledgingOriginal() throws Exception {
        var message = message();
        when(codec.readCommand(anyString(), anyString())).thenThrow(new InvalidCommandException("invalid"));
        consumer().consume(message, channel);
        var order = inOrder(broker, channel);
        order.verify(broker).send(eq(RabbitMessagingConfig.DLX), eq("quote.create.v1"), any());
        order.verify(channel).basicAck(123, false);
        verifyNoInteractions(handler);
    }

    @Test
    void retryCopiesBodyIncrementsAttemptAndRemovesOriginalExpiration() throws Exception {
        var message = message();
        var command = quote();
        when(codec.readCommand(anyString(), anyString())).thenReturn(command);
        when(handler.handle(any(), anyString())).thenThrow(new IllegalStateException("transient"));
        consumer().consume(message, channel);
        var forwarded = ArgumentCaptor.forClass(Message.class);
        verify(broker).send(eq(RabbitMessagingConfig.RETRY), eq("quote.create.v1"), forwarded.capture());
        assertThat(forwarded.getValue().getBody()).isEqualTo(message.getBody());
        assertThat(forwarded.getValue().getMessageProperties().getExpiration()).isNull();
        assertThat(((Number) forwarded.getValue().getMessageProperties().getHeader("billing-attempt")).intValue()).isEqualTo(1);
        message.getMessageProperties().setHeader("billing-attempt", 2);
        consumer().consume(message, channel);
        verify(broker).send(eq(RabbitMessagingConfig.DLX), eq("quote.create.v1"), any());
    }

    @Test
    void recoveryTransferFailureClosesUnacknowledgedChannelRatherThanLosingOriginal() throws Exception {
        when(codec.readCommand(anyString(), anyString())).thenThrow(new InvalidCommandException("invalid"));
        doThrow(new BrokerPublishException("recovery unavailable", null)).when(broker).send(anyString(), anyString(), any());
        consumer().consume(message(), channel);
        verify(channel).abort();
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void publisherFencesCompletionAndProcessesABoundedBatch() {
        var properties = new OutboxProperties(true, 2, Duration.ofMinutes(2), 3, Duration.ofSeconds(1), Duration.ofSeconds(5));
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var command = quote();
        var event = new OutboxEvent(UUID.randomUUID(), command.osId(), "Orcamento", "quote.created.v1", command.osId(),
                command.sagaId(), command.correlationId(), body(command), OutboxStatus.PROCESSING, 1, NOW, null,
                NOW, NOW.plusSeconds(60), UUID.randomUUID(), null, 1);
        when(outbox.acquire(1, NOW, properties.leaseDuration(), 3)).thenReturn(List.of(event), List.of());
        var publisher = new OutboxPublisher(outbox, eventBroker, properties, clock);
        publisher.publishPendingEvents();
        verify(eventBroker).publish(event);
        verify(outbox).markPublished(event.eventId(), event.leaseToken(), NOW);
        verify(outbox, times(2)).acquire(1, NOW, properties.leaseDuration(), 3);
        var disabled = new OutboxPublisher(outbox, eventBroker,
                new OutboxProperties(false, 2, Duration.ofMinutes(2), 3, Duration.ofSeconds(1), Duration.ofSeconds(5)), clock);
        disabled.publishPendingEvents();
        verifyNoMoreInteractions(eventBroker);
    }

    @Test
    void rejectsConfigurationsThatCouldCreateUnboundedRetriesOrInvalidLeases() {
        assertThatThrownBy(() -> new MessagingProperties(true, 0, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(true, 0, Duration.ofSeconds(1), 2, Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties(true, 1, Duration.ZERO, 2, Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
