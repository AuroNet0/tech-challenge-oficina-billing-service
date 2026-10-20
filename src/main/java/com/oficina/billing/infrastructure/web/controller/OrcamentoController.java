package com.oficina.billing.infrastructure.web.controller;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.oficina.billing.application.port.in.ConsultarOrcamentoPort;
import com.oficina.billing.application.port.in.DecidirOrcamentoPort;
import com.oficina.billing.application.port.in.DecidirOrcamentoPort.DecisaoCommand;
import com.oficina.billing.infrastructure.web.dto.*;

@RestController
@RequestMapping("/api/v1/orcamentos")
public class OrcamentoController {
    private final ConsultarOrcamentoPort consultas;
    private final DecidirOrcamentoPort decisoes;

    public OrcamentoController(ConsultarOrcamentoPort consultas, DecidirOrcamentoPort decisoes) {
        this.consultas = consultas;
        this.decisoes = decisoes;
    }

    @GetMapping("/{id}")
    public OrcamentoResponse porId(@PathVariable UUID id) {
        return OrcamentoResponse.from(consultas.porId(id));
    }

    @GetMapping
    public OrcamentoResponse porOs(@RequestParam UUID osId) {
        return OrcamentoResponse.from(consultas.porOsId(osId));
    }

    @GetMapping("/{id}/aprovacao")
    public AprovacaoResponse aprovacao(@PathVariable UUID id) {
        return AprovacaoResponse.from(consultas.aprovacao(id));
    }

    @PostMapping(value = "/{id}/aprovar", consumes = "application/json")
    @Operation(parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            schema = @Schema(type = "string"), description = "Token de um GET financeiro na mesma sessao"))
    public AprovacaoResponse aprovar(@PathVariable UUID id, @Valid @RequestBody AprovarOrcamentoRequest request,
                                    @RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
        return AprovacaoResponse.from(decisoes.aprovar(new DecisaoCommand(id, request.responsavelId(), null, correlationId)));
    }

    @PostMapping(value = "/{id}/rejeitar", consumes = "application/json")
    @Operation(parameters = @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            schema = @Schema(type = "string"), description = "Token de um GET financeiro na mesma sessao"))
    public AprovacaoResponse rejeitar(@PathVariable UUID id, @Valid @RequestBody RejeitarOrcamentoRequest request,
                                     @RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
        return AprovacaoResponse.from(decisoes.rejeitar(
                new DecisaoCommand(id, request.responsavelId(), request.motivo(), correlationId)));
    }
}
