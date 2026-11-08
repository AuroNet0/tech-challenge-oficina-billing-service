package com.oficina.billing.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.oficina.billing.domain.enums.InboxStatus;
import com.oficina.billing.domain.model.InboxMessage;

public interface InboxRepositoryPort {
    void lockOs(UUID osId);
    InboxMessage claim(InboxMessage message);
    void finish(UUID eventId, InboxStatus status, Instant now, String error);
    Optional<InboxMessage> findOrigin(UUID osId, UUID sagaId, String eventType);
}
