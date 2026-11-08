package com.oficina.billing.application.port.in;

public interface PaymentNotificationPort {
    void receive(String deduplicationId, String resourceId, String requestId);
}
