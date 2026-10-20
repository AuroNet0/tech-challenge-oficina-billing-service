package com.oficina.billing.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.oficina.billing.infrastructure.persistence.entity.OrcamentoEntity;

public interface OrcamentoJpaRepository extends JpaRepository<OrcamentoEntity, UUID> {
    Optional<OrcamentoEntity> findByOsId(UUID osId);
    List<OrcamentoEntity> findBySagaId(UUID sagaId);
    boolean existsByOsId(UUID osId);
}
