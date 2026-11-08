package com.oficina.billing.infrastructure.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.oficina.billing.application.exception.InvalidCommandException;
import com.oficina.billing.application.message.MessageEnvelope;
import com.oficina.billing.application.port.out.MessageCodecPort;

@Component
public class MessageCodec implements MessageCodecPort {
    private final ObjectMapper mapper;

    public MessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MessageEnvelope readCommand(String body, String routingKey) {
        try {
            var root = mapper.readTree(body);
            String type = text(root, "eventType");
            if (!type.equals(routingKey) || !Set.of("quote.create.v1", "payment.request.v1").contains(type)
                    || !"os-service".equals(text(root, "source")))
                throw new InvalidCommandException("Origem ou routing key incompativel");
            var payload = root.get("payload");
            if (payload == null || !payload.isObject()) throw new InvalidCommandException("payload deve ser objeto");
            Map<String, Object> values = mapper.convertValue(payload, new TypeReference<>() { });
            return new MessageEnvelope(uuid(root, "eventId"), type, Instant.parse(text(root, "occurredAt")),
                    uuid(root, "correlationId"), uuid(root, "sagaId"), uuid(root, "osId"), "os-service", values);
        } catch (InvalidCommandException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidCommandException("Envelope invalido");
        }
    }

    @Override
    public String serialize(MessageEnvelope envelope) {
        var root = mapper.createObjectNode();
        root.put("eventId", envelope.eventId().toString());
        root.put("eventType", envelope.eventType());
        root.put("occurredAt", envelope.occurredAt().toString());
        root.put("correlationId", envelope.correlationId().toString());
        root.put("sagaId", envelope.sagaId().toString());
        root.put("osId", envelope.osId().toString());
        root.put("source", envelope.source());
        root.set("payload", mapper.valueToTree(envelope.payload()));
        return mapper.writeValueAsString(root);
    }

    @Override
    public boolean equivalent(String first, String second) {
        return mapper.readTree(first).equals(mapper.readTree(second));
    }

    private String text(JsonNode root, String field) {
        if (root == null || !root.isObject() || !root.hasNonNull(field)
                || !root.get(field).isString() || root.get(field).asString().isBlank())
            throw new InvalidCommandException("Campo obrigatorio: " + field);
        return root.get(field).asString();
    }

    private UUID uuid(JsonNode root, String field) {
        String value = text(root, field);
        var result = UUID.fromString(value);
        if (!result.toString().equalsIgnoreCase(value)) throw new InvalidCommandException("UUID invalido");
        return result;
    }
}
