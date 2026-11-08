package com.oficina.billing.application.message;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MessageEnvelope(UUID eventId, String eventType, Instant occurredAt, UUID correlationId,
                              UUID sagaId, UUID osId, String source, Map<String, Object> payload) {
}
