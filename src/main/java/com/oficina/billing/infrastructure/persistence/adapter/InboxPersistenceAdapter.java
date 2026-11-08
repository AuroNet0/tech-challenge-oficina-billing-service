package com.oficina.billing.infrastructure.persistence.adapter;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import com.oficina.billing.application.port.out.InboxRepositoryPort;
import com.oficina.billing.domain.enums.InboxStatus;
import com.oficina.billing.domain.model.InboxMessage;
import com.oficina.billing.infrastructure.persistence.entity.InboxMessageEntity;
import com.oficina.billing.infrastructure.persistence.repository.InboxJpaRepository;

@Repository
public class InboxPersistenceAdapter implements InboxRepositoryPort {
    private final JdbcTemplate jdbc;
    private final InboxJpaRepository repository;

    public InboxPersistenceAdapter(JdbcTemplate jdbc, InboxJpaRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOs(UUID osId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", osId.toString());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public InboxMessage claim(InboxMessage message) {
        jdbc.update("""
                INSERT INTO inbox_messages(event_id, event_type, os_id, saga_id, correlation_id, payload, status, received_at)
                VALUES (?, ?, ?, ?, ?, ?, 'RECEIVED', ?) ON CONFLICT (event_id) DO NOTHING
                """, message.eventId(), message.eventType(), message.osId(), message.sagaId(),
                message.correlationId(), message.payload(), Timestamp.from(message.receivedAt()));
        return toDomain(repository.findLocked(message.eventId()).orElseThrow());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(UUID eventId, InboxStatus status, Instant now, String error) {
        var entity = repository.findById(eventId).orElseThrow();
        entity.status = status;
        entity.processedAt = now;
        entity.lastError = error;
        repository.saveAndFlush(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InboxMessage> findOrigin(UUID osId, UUID sagaId, String eventType) {
        return repository.findFirstByOsIdAndSagaIdAndEventTypeAndStatusOrderByReceivedOrderAsc(
                osId, sagaId, eventType, InboxStatus.PROCESSED).map(this::toDomain);
    }

    private InboxMessage toDomain(InboxMessageEntity entity) {
        return new InboxMessage(entity.eventId, entity.eventType, entity.osId, entity.sagaId, entity.correlationId,
                entity.payload, entity.status, entity.receivedAt, entity.processedAt, entity.lastError);
    }
}
