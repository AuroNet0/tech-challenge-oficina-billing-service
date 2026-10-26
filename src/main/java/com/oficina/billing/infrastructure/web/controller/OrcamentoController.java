package com.oficina.billing.infrastructure.web.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.oficina.billing.application.port.in.ConsultarOrcamentoPort;
import com.oficina.billing.application.port.in.DecidirOrcamentoPort;
import com.oficina.billing.application.port.in.DecidirOrcamentoPort.DecisaoCommand;
import com.oficina.billing.infrastructure.web.dto.*;
import com.oficina.billing.infrastructure.web.security.FinancialAuthorization;
import com.oficina.billing.application.authorization.FinancialAccessPort.Action;

@RestController
@RequestMapping("/api/v1/orcamentos")
public class OrcamentoController {
    private final FinancialAuthorization authorization;
    private final ConsultarOrcamentoPort consultas;
    private final DecidirOrcamentoPort decisoes;

    public OrcamentoController(ConsultarOrcamentoPort consultas, DecidirOrcamentoPort decisoes,
            FinancialAuthorization authorization) {
        this.authorization = authorization;
        this.consultas = consultas;
        this.decisoes = decisoes;
    }

    @GetMapping("/{id}")
    public Object porId(@PathVariable UUID id) {
        var resource = consultas.porId(id);
        authorization.require(resource.osId(), Action.READ);
        return authorization.operational() ? OperationalResponse.quote(resource) : OrcamentoResponse.from(resource);
    }

    @GetMapping
    public Object porOs(@RequestParam UUID osId) {
        var resource = consultas.porOsId(osId);
        authorization.require(resource.osId(), Action.READ);
        return authorization.operational() ? OperationalResponse.quote(resource) : OrcamentoResponse.from(resource);
    }

    @GetMapping("/{id}/aprovacao")
    public Object aprovacao(@PathVariable UUID id) {
        var resource = consultas.porId(id);
        authorization.require(resource.osId(), Action.READ);
        var decision = consultas.aprovacao(id);
        return authorization.operational() ? new OperationalResponse(resource.id(), resource.osId(), decision.status().name())
                : AprovacaoResponse.from(decision);
    }

    @PostMapping(value = "/{id}/aprovar", consumes = "application/json")
    public AprovacaoResponse aprovar(@PathVariable UUID id, @Valid @RequestBody AprovarOrcamentoRequest request,
                                    @RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
        String actor = authorization.decisionActor(consultas.porId(id).osId(), request.responsavelId());
        return AprovacaoResponse.from(decisoes.aprovar(new DecisaoCommand(id, actor, null, correlationId)));
    }

    @PostMapping(value = "/{id}/rejeitar", consumes = "application/json")
    public AprovacaoResponse rejeitar(@PathVariable UUID id, @Valid @RequestBody RejeitarOrcamentoRequest request,
                                     @RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
        String actor = authorization.decisionActor(consultas.porId(id).osId(), request.responsavelId());
        return AprovacaoResponse.from(decisoes.rejeitar(
                new DecisaoCommand(id, actor, request.motivo(), correlationId)));
    }
}
