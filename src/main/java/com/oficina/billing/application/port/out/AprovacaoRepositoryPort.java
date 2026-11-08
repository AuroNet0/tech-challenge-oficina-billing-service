package com.oficina.billing.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.oficina.billing.domain.model.Aprovacao;

public interface AprovacaoRepositoryPort {
    Aprovacao save(Aprovacao aprovacao);
    Optional<Aprovacao> findByOrcamentoId(UUID orcamentoId);
}
