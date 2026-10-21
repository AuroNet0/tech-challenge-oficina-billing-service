package com.oficina.billing.infrastructure.persistence.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.oficina.billing.infrastructure.persistence.entity.OutboxEventEntity;

public interface OutboxJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {
}
