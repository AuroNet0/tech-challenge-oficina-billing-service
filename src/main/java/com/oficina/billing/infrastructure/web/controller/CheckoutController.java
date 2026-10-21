package com.oficina.billing.infrastructure.web.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.oficina.billing.application.port.in.CheckoutPort;
import com.oficina.billing.application.payment.ProviderData.CheckoutResponse;

@RestController
@RequestMapping("/api/v1/pagamentos")
public class CheckoutController {
    private final CheckoutPort checkout;
    public CheckoutController(CheckoutPort checkout) { this.checkout = checkout; }

    @PostMapping("/{id}/checkout")
    public ResponseEntity<CheckoutResponse> iniciar(@PathVariable UUID id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && !body.isEmpty()) throw new IllegalArgumentException("Checkout nao aceita parametros financeiros");
        CheckoutResponse response = checkout.iniciar(id);
        return ResponseEntity.status(response.checkoutStatus().equals("READY") ? 200 : 202).body(response);
    }
}
