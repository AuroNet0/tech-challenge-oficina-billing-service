package com.oficina.billing.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.oficina.billing.application.payment.ProviderData.*;

public interface ProviderWorkflowPort {
    UUID claim(UUID paymentId, Instant now, Duration duration);
    void lock(UUID paymentId, UUID token, Instant now);
    Checkout checkout(UUID paymentId, UUID attemptId, String key, Instant now);
    List<Checkout> checkouts(UUID paymentId);
    void saveCheckout(Checkout checkout);
    String reviewReason(UUID paymentId);
    void review(UUID paymentId, String reason);
    void release(UUID paymentId, UUID token, Instant now, Duration delay, String error);
    UUID nextDue(Instant now);
    UUID findPayment(String reference);
    String observe(UUID paymentId, UUID attemptId, ExternalPayment payment, Instant now);
    List<ExternalPayment> knownPayments(UUID paymentId);
    void prepareSettlement(UUID paymentId, String key, Instant now);
    void receive(String id, String resourceId, String requestId, Instant now);
    Notification claimNotification(Instant now, Duration duration);
    void finishNotification(Notification notification, String status, Instant now, Duration delay, String error);
}
