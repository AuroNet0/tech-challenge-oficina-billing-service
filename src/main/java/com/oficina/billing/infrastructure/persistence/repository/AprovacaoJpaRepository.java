package com.oficina.billing.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.oficina.billing.infrastructure.persistence.entity.AprovacaoEntity;

public interface AprovacaoJpaRepository extends JpaRepository<AprovacaoEntity, UUID> {
    Optional<AprovacaoEntity> findByOrcamentoId(UUID orcamentoId);
}
