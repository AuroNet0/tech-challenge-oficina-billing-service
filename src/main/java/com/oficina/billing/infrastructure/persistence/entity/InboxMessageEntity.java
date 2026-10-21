package com.oficina.billing.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import com.oficina.billing.domain.enums.*;

@Entity
@Table(name = "inbox_messages")
public class InboxMessageEntity {
    @Id
    public UUID eventId;

    public String eventType;

    public UUID osId;

    public UUID sagaId;

    public UUID correlationId;

    @Column(columnDefinition = "text")
    public String payload;

    @Enumerated(EnumType.STRING)
    public InboxStatus status;

    public Instant receivedAt;

    public Instant processedAt;

    public String lastError;

    @Column(insertable = false, updatable = false)
    public Long receivedOrder;
}
