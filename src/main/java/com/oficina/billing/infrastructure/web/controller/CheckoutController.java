package com.oficina.billing.infrastructure.web.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.oficina.billing.application.port.in.CheckoutPort;
import com.oficina.billing.application.port.in.ConsultarPagamentoPort;
import com.oficina.billing.application.authorization.FinancialAccessPort.Action;
import com.oficina.billing.infrastructure.web.security.FinancialAuthorization;
import com.oficina.billing.application.payment.ProviderData.CheckoutResponse;

@RestController
@RequestMapping("/api/v1/pagamentos")
public class CheckoutController {
    private final CheckoutPort checkout;
    private final ConsultarPagamentoPort payments;
    private final FinancialAuthorization authorization;
    public CheckoutController(CheckoutPort checkout, ConsultarPagamentoPort payments, FinancialAuthorization authorization) {
        this.checkout = checkout;
        this.payments = payments;
        this.authorization = authorization;
    }

    @PostMapping("/{id}/checkout")
    public ResponseEntity<CheckoutResponse> iniciar(@PathVariable UUID id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && !body.isEmpty()) throw new IllegalArgumentException("Checkout nao aceita parametros financeiros");
        authorization.require(payments.porId(id).osId(), Action.CHECKOUT);
        CheckoutResponse response = checkout.iniciar(id);
        return ResponseEntity.status(response.checkoutStatus().equals("READY") ? 200 : 202).body(response);
    }
}
