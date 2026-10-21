package com.oficina.billing.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import com.oficina.billing.domain.enums.*;

@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {
    @Id
    public UUID eventId;

    public UUID aggregateId;

    public String aggregateType;

    public String eventType;

    public UUID osId;

    public UUID sagaId;

    public UUID correlationId;

    @Column(columnDefinition = "text")
    public String payload;

    @Enumerated(EnumType.STRING)
    public OutboxStatus status;

    public int attempts;

    public Instant createdAt;

    public Instant publishedAt;

    public Instant nextAttemptAt;

    public Instant lockedUntil;

    public UUID leaseToken;

    public String lastError;

    @Column(insertable = false, updatable = false)
    public Long sequence;
}
