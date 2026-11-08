package com.oficina.billing.infrastructure.web.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import com.oficina.billing.application.port.in.ConsultarPagamentoPort;
import com.oficina.billing.infrastructure.web.dto.PagamentoResponse;
import com.oficina.billing.infrastructure.web.dto.OperationalResponse;
import com.oficina.billing.infrastructure.web.security.FinancialAuthorization;
import com.oficina.billing.application.authorization.FinancialAccessPort.Action;

@RestController
@RequestMapping("/api/v1/pagamentos")
public class PagamentoController {
    private final ConsultarPagamentoPort consultas;
    private final FinancialAuthorization authorization;

    public PagamentoController(ConsultarPagamentoPort consultas, FinancialAuthorization authorization) {
        this.authorization = authorization;
        this.consultas = consultas;
    }

    @GetMapping("/{id}")
    public Object porId(@PathVariable UUID id) {
        var resource = consultas.porId(id);
        authorization.require(resource.osId(), Action.READ);
        return authorization.operational() ? OperationalResponse.payment(resource) : PagamentoResponse.from(resource);
    }

    @GetMapping
    public List<?> porOs(@RequestParam UUID osId) {
        var resources = consultas.porOsId(osId);
        if (resources.isEmpty()) authorization.require(osId, Action.READ);
        resources.forEach(resource -> authorization.require(resource.osId(), Action.READ));
        return authorization.operational() ? resources.stream().map(OperationalResponse::payment).toList()
                : resources.stream().map(PagamentoResponse::from).toList();
    }
}
