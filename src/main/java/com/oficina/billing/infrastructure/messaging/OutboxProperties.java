package com.oficina.billing.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("billing.outbox")
public record OutboxProperties(boolean enabled, int batchSize, Duration leaseDuration, int maxAttempts,
                               Duration initialBackoff, Duration maxBackoff) {
    public OutboxProperties {
        if (batchSize < 1 || maxAttempts < 1 || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()
                || initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()
                || maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0)
            throw new IllegalArgumentException("Configuracao de outbox invalida");
    }

    public Duration backoff(int attempt) {
        long delay = initialBackoff.toMillis();
        long max = maxBackoff.toMillis();
        for (int index = 1; index < attempt && delay < max; index++) {
            delay = delay > max / 2 ? max : delay * 2;
        }
        return Duration.ofMillis(Math.min(delay, max));
    }
}
