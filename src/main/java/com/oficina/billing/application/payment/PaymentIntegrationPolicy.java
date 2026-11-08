package com.oficina.billing.application.payment;

import java.time.Duration;

public record PaymentIntegrationPolicy(boolean enabled, boolean reconciliationEnabled, Duration leaseDuration,
                                       Duration syncInterval, int batchSize) {}
