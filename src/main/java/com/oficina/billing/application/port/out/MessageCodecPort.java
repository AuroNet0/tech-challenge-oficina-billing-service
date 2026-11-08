package com.oficina.billing.application.port.out;

import com.oficina.billing.application.message.MessageEnvelope;

public interface MessageCodecPort {
    MessageEnvelope readCommand(String body, String routingKey);
    String serialize(MessageEnvelope envelope);
    boolean equivalent(String first, String second);
}
