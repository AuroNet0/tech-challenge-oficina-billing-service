package com.oficina.billing.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.oficina.billing.domain.model.Orcamento;

public interface OrcamentoRepositoryPort {
    Orcamento save(Orcamento orcamento);
    Optional<Orcamento> findById(UUID id);
    Optional<Orcamento> findByOsId(UUID osId);
    List<Orcamento> findBySagaId(UUID sagaId);
    boolean existsByOsId(UUID osId);
}
