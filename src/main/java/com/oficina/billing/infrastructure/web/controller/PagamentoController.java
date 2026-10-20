package com.oficina.billing.infrastructure.web.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import com.oficina.billing.application.port.in.ConsultarPagamentoPort;
import com.oficina.billing.infrastructure.web.dto.PagamentoResponse;

@RestController
@RequestMapping("/api/v1/pagamentos")
public class PagamentoController {
    private final ConsultarPagamentoPort consultas;

    public PagamentoController(ConsultarPagamentoPort consultas) {
        this.consultas = consultas;
    }

    @GetMapping("/{id}")
    public PagamentoResponse porId(@PathVariable UUID id) {
        return PagamentoResponse.from(consultas.porId(id));
    }

    @GetMapping
    public List<PagamentoResponse> porOs(@RequestParam UUID osId) {
        return consultas.porOsId(osId).stream().map(PagamentoResponse::from).toList();
    }
}
