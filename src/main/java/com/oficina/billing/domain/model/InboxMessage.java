package com.oficina.billing.domain.model;

import java.time.Instant;
import java.util.UUID;
import com.oficina.billing.domain.enums.InboxStatus;

public record InboxMessage(UUID eventId, String eventType, UUID osId, UUID sagaId, UUID correlationId,
                           String payload, InboxStatus status, Instant receivedAt, Instant processedAt, String lastError) {
}
