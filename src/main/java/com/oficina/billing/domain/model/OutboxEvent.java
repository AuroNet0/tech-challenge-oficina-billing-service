package com.oficina.billing.domain.model;

import java.time.Instant;
import java.util.UUID;
import com.oficina.billing.domain.enums.OutboxStatus;

public record OutboxEvent(UUID eventId, UUID aggregateId, String aggregateType, String eventType, UUID osId,
                          UUID sagaId, UUID correlationId, String payload, OutboxStatus status, int attempts,
                          Instant createdAt, Instant publishedAt, Instant nextAttemptAt, Instant lockedUntil,
                          UUID leaseToken, String lastError, long sequence) {
}
