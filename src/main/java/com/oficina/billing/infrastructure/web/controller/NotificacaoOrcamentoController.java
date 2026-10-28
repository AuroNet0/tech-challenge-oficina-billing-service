package com.oficina.billing.infrastructure.web.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.oficina.billing.application.authorization.FinancialAccessPort.Action;
import com.oficina.billing.application.port.in.ConsultarNotificacoesPort;
import com.oficina.billing.infrastructure.web.dto.NotificacaoOrcamentoResponse;
import com.oficina.billing.infrastructure.web.security.FinancialAuthorization;

@RestController
@RequestMapping("/api/v1/notificacoes")
public class NotificacaoOrcamentoController {
    private final ConsultarNotificacoesPort consultas;
    private final FinancialAuthorization authorization;

    public NotificacaoOrcamentoController(ConsultarNotificacoesPort consultas, FinancialAuthorization authorization) {
        this.consultas = consultas;
        this.authorization = authorization;
    }

    @GetMapping
    public ResponseEntity<List<NotificacaoOrcamentoResponse>> porOs(@RequestParam UUID osId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        authorization.require(osId, Action.DECIDE);
        var notifications = consultas.porOsId(osId, page, size).stream()
                .map(NotificacaoOrcamentoResponse::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications);
    }
}
