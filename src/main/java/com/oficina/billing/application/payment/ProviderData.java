package com.oficina.billing.application.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ProviderData {
    private ProviderData() {}

    public record CheckoutRequest(UUID paymentId, UUID attemptId, UUID osId, UUID sagaId,
                                  String reference, String key, BigDecimal amount, String currency, String paymentStatus) {}
    public record Preference(String id, String reference, UUID attemptId, BigDecimal amount,
                             String currency, String checkoutUrl) {}
    public record ExternalPayment(String id, String reference, String preferenceId, UUID attemptId,
                                  BigDecimal amount, String currency, String status, Instant updatedAt,
                                  boolean liveMode) {}
    public record Checkout(UUID paymentId, UUID attemptId, String key, String state,
                           String preferenceId, String checkoutUrl) {}
    public record Lease(UUID paymentId, UUID token, CheckoutRequest request, Checkout checkout, boolean create) {}
    public record Notification(String id, String resourceId, UUID token) {}
    public record CheckoutResponse(UUID pagamentoId, String provider, String preferenceId, String checkoutUrl,
                                   String status, String checkoutStatus) {}
}
