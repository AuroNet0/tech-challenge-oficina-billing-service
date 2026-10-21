package com.oficina.billing.application.usecase;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.billing.application.exception.ResourceNotFoundException;
import com.oficina.billing.application.port.in.DecidirOrcamentoPort;
import com.oficina.billing.application.port.out.AprovacaoRepositoryPort;
import com.oficina.billing.application.port.out.OrcamentoRepositoryPort;
import com.oficina.billing.domain.exception.DomainException;
import com.oficina.billing.domain.model.Aprovacao;

@Service
public class DecidirOrcamentoUseCase implements DecidirOrcamentoPort {
    private final OrcamentoRepositoryPort orcamentos;
    private final AprovacaoRepositoryPort aprovacoes;
    private final Clock clock;
    private final EventRecorder events;

    public DecidirOrcamentoUseCase(OrcamentoRepositoryPort orcamentos, AprovacaoRepositoryPort aprovacoes,
                                 Clock clock, EventRecorder events) {
        this.orcamentos = orcamentos;
        this.aprovacoes = aprovacoes;
        this.clock = clock;
        this.events = events;
    }

    @Override
    @Transactional
    public Aprovacao aprovar(DecisaoCommand command) {
        return decidir(command, true);
    }

    @Override
    @Transactional
    public Aprovacao rejeitar(DecisaoCommand command) {
        return decidir(command, false);
    }

    private Aprovacao decidir(DecisaoCommand command, boolean approve) {
        var orcamento = orcamentos.findById(command.orcamentoId()).orElseThrow(ResourceNotFoundException::new);
        if (aprovacoes.findByOrcamentoId(orcamento.id()).isPresent()) {
            throw new DomainException("Orcamento ja possui decisao");
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (approve) {
            orcamento.aprovar(now);
        } else {
            orcamento.rejeitar(now);
        }
        orcamentos.save(orcamento);
        var correlation = events.correlation(orcamento.osId(), orcamento.sagaId(), command.correlationId());
        var decisao = aprovacoes.save(Aprovacao.registrar(orcamento, command.responsavelId(),
                approve ? null : command.motivo(), correlation, now));
        String reason = command.motivo() == null ? "Orcamento recusado pelo solicitante." : command.motivo();
        events.record(orcamento.id(), "Orcamento", orcamento.osId(), orcamento.sagaId(), correlation,
                approve ? "quote.approved.v1" : "quote.rejected.v1",
                approve ? Map.of() : Map.of("reason", reason), now);
        return decisao;
    }
}
