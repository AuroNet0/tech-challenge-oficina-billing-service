package com.oficina.billing.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("billing.messaging")
public record MessagingProperties(boolean enabled, int maxDeliveries, Duration retryDelay, Duration confirmTimeout) {
    public MessagingProperties {
        if (maxDeliveries < 1 || retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()
                || confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero())
            throw new IllegalArgumentException("Configuracao de mensageria invalida");
    }
}
