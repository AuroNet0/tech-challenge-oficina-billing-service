package com.oficina.billing.infrastructure.messaging;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.oficina.billing.application.port.out.*;

@Component
public class OutboxPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepositoryPort outbox;
    private final MessageBrokerPort broker;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxPublisher(OutboxRepositoryPort outbox, MessageBrokerPort broker, OutboxProperties properties, Clock clock) {
        this.outbox = outbox;
        this.broker = broker;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${billing.outbox.fixed-delay:5s}")
    public void publishPendingEvents() {
        if (!properties.enabled()) return;
        for (int index = 0; index < properties.batchSize(); index++) {
            if (!publishOne()) break;
        }
    }

    public boolean publishOne() {
        var events = outbox.acquire(1, clock.instant(), properties.leaseDuration(), properties.maxAttempts());
        if (events.isEmpty()) return false;
        var event = events.getFirst();
        try {
            broker.publish(event);
            boolean updated = outbox.markPublished(event.eventId(), event.leaseToken(), clock.instant());
            LOGGER.info("outbox eventId={} osId={} attempt={} result={}", event.eventId(), event.osId(),
                    event.attempts(), updated ? "published" : "lease-lost");
        } catch (RuntimeException exception) {
            boolean updated = outbox.markAttemptFailed(event, clock.instant(), properties.maxAttempts(),
                    properties.backoff(event.attempts()), exception.getClass().getSimpleName());
            LOGGER.warn("outbox eventId={} osId={} attempt={} result={}", event.eventId(), event.osId(), event.attempts(),
                    !updated ? "lease-lost" : event.attempts() >= properties.maxAttempts() ? "failed" : "retry");
        }
        return true;
    }
}
