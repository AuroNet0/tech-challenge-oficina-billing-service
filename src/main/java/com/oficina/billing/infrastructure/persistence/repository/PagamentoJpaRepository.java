package com.oficina.billing.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.oficina.billing.infrastructure.persistence.entity.PagamentoEntity;

public interface PagamentoJpaRepository extends JpaRepository<PagamentoEntity, UUID> {
    Optional<PagamentoEntity> findByOrcamentoId(UUID orcamentoId);
    List<PagamentoEntity> findByOsId(UUID osId);
    List<PagamentoEntity> findBySagaId(UUID sagaId);
    @Query("select distinct pagamento from PagamentoEntity pagamento join pagamento.tentativas tentativa where tentativa.idempotencyKey = :key")
    Optional<PagamentoEntity> findByIdempotencyKey(@Param("key") String key);
}
