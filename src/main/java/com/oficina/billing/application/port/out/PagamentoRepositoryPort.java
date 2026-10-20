package com.oficina.billing.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.oficina.billing.domain.model.Pagamento;

public interface PagamentoRepositoryPort {
    Pagamento save(Pagamento pagamento);
    Optional<Pagamento> findById(UUID id);
    List<Pagamento> findByOsId(UUID osId);
    List<Pagamento> findBySagaId(UUID sagaId);
    Optional<Pagamento> findByIdempotencyKey(String key);
}
