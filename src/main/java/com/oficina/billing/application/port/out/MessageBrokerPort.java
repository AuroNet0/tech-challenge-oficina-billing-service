package com.oficina.billing.application.port.out;

import com.oficina.billing.domain.model.OutboxEvent;

public interface MessageBrokerPort {
    void publish(OutboxEvent event);
}
