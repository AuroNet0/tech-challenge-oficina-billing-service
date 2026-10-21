package com.oficina.billing.infrastructure.persistence.repository;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import com.oficina.billing.domain.enums.InboxStatus;
import com.oficina.billing.infrastructure.persistence.entity.InboxMessageEntity;

public interface InboxJpaRepository extends JpaRepository<InboxMessageEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select message from InboxMessageEntity message where message.eventId = :id")
    Optional<InboxMessageEntity> findLocked(@Param("id") UUID id);

    Optional<InboxMessageEntity> findFirstByOsIdAndSagaIdAndEventTypeAndStatusOrderByReceivedOrderAsc(
            UUID osId, UUID sagaId, String eventType, InboxStatus status);
}
