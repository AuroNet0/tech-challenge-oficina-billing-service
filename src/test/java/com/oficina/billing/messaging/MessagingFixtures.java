package com.oficina.billing.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.oficina.billing.application.message.MessageEnvelope;
import com.oficina.billing.infrastructure.messaging.MessageCodec;
import tools.jackson.databind.json.JsonMapper;
import static com.oficina.billing.TestFixtures.NOW;

public final class MessagingFixtures {
    public static final MessageCodec CODEC = new MessageCodec(JsonMapper.builder().build());

    private MessagingFixtures() {
    }

    public static MessageEnvelope command(String type, UUID osId, UUID sagaId, Map<String, Object> payload) {
        return new MessageEnvelope(UUID.randomUUID(), type, NOW, UUID.randomUUID(), sagaId, osId, "os-service", payload);
    }

    public static MessageEnvelope quote() {
        return command("quote.create.v1", UUID.randomUUID(), UUID.randomUUID(), quotePayload("1.2500", "80.00"));
    }

    public static Map<String, Object> quotePayload(String quantity, String price) {
        return Map.of("items", List.of(Map.of("itemId", "diagnostic-1", "descricao", "Diagnostico tecnico",
                "tipo", "SERVICO", "quantidade", quantity, "unidadePreco", "HORA", "precoPrevisto", price)));
    }

    public static String body(MessageEnvelope command) {
        return CODEC.serialize(command);
    }
}
