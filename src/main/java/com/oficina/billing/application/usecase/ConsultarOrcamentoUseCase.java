package com.oficina.billing.application.usecase;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.billing.application.exception.ResourceNotFoundException;
import com.oficina.billing.application.port.in.ConsultarOrcamentoPort;
import com.oficina.billing.application.port.out.AprovacaoRepositoryPort;
import com.oficina.billing.application.port.out.OrcamentoRepositoryPort;
import com.oficina.billing.domain.model.Aprovacao;
import com.oficina.billing.domain.model.Orcamento;

@Service
@Transactional(readOnly = true)
public class ConsultarOrcamentoUseCase implements ConsultarOrcamentoPort {
    private final OrcamentoRepositoryPort orcamentos;
    private final AprovacaoRepositoryPort aprovacoes;

    public ConsultarOrcamentoUseCase(OrcamentoRepositoryPort orcamentos, AprovacaoRepositoryPort aprovacoes) {
        this.orcamentos = orcamentos;
        this.aprovacoes = aprovacoes;
    }

    @Override
    public Orcamento porId(UUID id) {
        return orcamentos.findById(id).orElseThrow(ResourceNotFoundException::new);
    }

    @Override
    public Orcamento porOsId(UUID osId) {
        return orcamentos.findByOsId(osId).orElseThrow(ResourceNotFoundException::new);
    }

    @Override
    public Aprovacao aprovacao(UUID orcamentoId) {
        porId(orcamentoId);
        return aprovacoes.findByOrcamentoId(orcamentoId).orElseThrow(ResourceNotFoundException::new);
    }
}
