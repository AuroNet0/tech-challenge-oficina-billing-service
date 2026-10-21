package com.oficina.billing.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.oficina.billing.domain.model.OutboxEvent;

public interface OutboxRepositoryPort {
    void insert(OutboxEvent event);
    List<OutboxEvent> acquire(int limit, Instant now, Duration lease, int maxAttempts);
    boolean markPublished(UUID eventId, UUID token, Instant now);
    boolean markAttemptFailed(OutboxEvent event, Instant now, int maxAttempts, Duration backoff, String error);
}
