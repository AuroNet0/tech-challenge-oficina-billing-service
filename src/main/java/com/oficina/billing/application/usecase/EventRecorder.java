package com.oficina.billing.application.usecase;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.oficina.billing.application.message.MessageEnvelope;
import com.oficina.billing.application.port.out.*;
import com.oficina.billing.domain.enums.OutboxStatus;
import com.oficina.billing.domain.model.OutboxEvent;

@Service
public class EventRecorder {
    private final InboxRepositoryPort inbox;
    private final OutboxRepositoryPort outbox;
    private final MessageCodecPort codec;

    public EventRecorder(InboxRepositoryPort inbox, OutboxRepositoryPort outbox, MessageCodecPort codec) {
        this.inbox = inbox;
        this.outbox = outbox;
        this.codec = codec;
    }

    public UUID correlation(UUID osId, UUID sagaId, UUID supplied) {
        if (supplied != null) return supplied;
        return inbox.findOrigin(osId, sagaId, "quote.create.v1").map(message -> message.correlationId())
                .orElseGet(() -> UUID.nameUUIDFromBytes(("billing:" + sagaId + ":" + osId).getBytes(StandardCharsets.UTF_8)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID aggregateId, String aggregateType, UUID osId, UUID sagaId, UUID correlationId,
                       String eventType, Map<String, Object> payload, Instant now) {
        inbox.lockOs(osId);
        UUID eventId = UUID.randomUUID();
        String body = codec.serialize(new MessageEnvelope(eventId, eventType, now, correlationId,
                sagaId, osId, "billing-service", payload));
        outbox.insert(new OutboxEvent(eventId, aggregateId, aggregateType, eventType, osId, sagaId, correlationId,
                body, OutboxStatus.PENDING, 0, now, null, now, null, null, null, 0));
    }
}
