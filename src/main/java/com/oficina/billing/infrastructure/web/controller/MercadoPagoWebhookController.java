package com.oficina.billing.infrastructure.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.oficina.billing.application.port.in.PaymentNotificationPort;
import com.oficina.billing.infrastructure.payment.MercadoPagoSignatureVerifier;

@RestController
public class MercadoPagoWebhookController {
    private final MercadoPagoSignatureVerifier signatures;
    private final PaymentNotificationPort notifications;
    private final ObjectMapper mapper;

    public MercadoPagoWebhookController(MercadoPagoSignatureVerifier signatures, PaymentNotificationPort notifications,
                                        ObjectMapper mapper) {
        this.signatures = signatures;
        this.notifications = notifications;
        this.mapper = mapper;
    }

    @PostMapping(value = "/api/v1/webhooks/mercado-pago", consumes = "application/json")
    public ResponseEntity<Void> receber(@RequestParam(name = "data.id", required = false) String resourceId,
                                        @RequestHeader(name = "x-request-id", required = false) String requestId,
                                        @RequestHeader(name = "x-signature", required = false) String signature,
                                        @RequestBody String body) {
        String deduplicationId = signatures.verify(resourceId, requestId, signature);
        if (body.length() > 16384) throw new IllegalArgumentException("Notificacao excede limite");
        JsonNode node;
        try { node = mapper.readTree(body); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("JSON invalido"); }
        if (!node.isObject() || !node.hasNonNull("type") || !"payment".equals(node.get("type").asString())
                || !node.hasNonNull("data") || !node.get("data").hasNonNull("id")
                || !resourceId.equals(node.get("data").get("id").asString())) {
            throw new IllegalArgumentException("Identificadores da notificacao divergentes");
        }
        notifications.receive(deduplicationId, resourceId, requestId);
        return ResponseEntity.ok().build();
    }
}
