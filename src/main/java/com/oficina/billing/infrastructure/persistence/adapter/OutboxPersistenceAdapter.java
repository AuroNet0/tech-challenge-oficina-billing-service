package com.oficina.billing.infrastructure.persistence.adapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import com.oficina.billing.application.port.out.OutboxRepositoryPort;
import com.oficina.billing.domain.enums.OutboxStatus;
import com.oficina.billing.domain.model.OutboxEvent;
import com.oficina.billing.infrastructure.persistence.entity.OutboxEventEntity;
import com.oficina.billing.infrastructure.persistence.repository.OutboxJpaRepository;

@Repository
public class OutboxPersistenceAdapter implements OutboxRepositoryPort {
    private final JdbcTemplate jdbc;
    private final OutboxJpaRepository repository;

    public OutboxPersistenceAdapter(JdbcTemplate jdbc, OutboxJpaRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(OutboxEvent event) {
        var entity = new OutboxEventEntity();
        entity.eventId = event.eventId();
        entity.aggregateId = event.aggregateId();
        entity.aggregateType = event.aggregateType();
        entity.eventType = event.eventType();
        entity.osId = event.osId();
        entity.sagaId = event.sagaId();
        entity.correlationId = event.correlationId();
        entity.payload = event.payload();
        entity.status = event.status();
        entity.attempts = event.attempts();
        entity.createdAt = event.createdAt();
        entity.publishedAt = event.publishedAt();
        entity.nextAttemptAt = event.nextAttemptAt();
        entity.lockedUntil = event.lockedUntil();
        entity.leaseToken = event.leaseToken();
        entity.lastError = event.lastError();
        repository.saveAndFlush(entity);
    }

    @Override
    @Transactional
    public List<OutboxEvent> acquire(int limit, Instant now, Duration lease, int maxAttempts) {
        jdbc.update("""
                UPDATE outbox_events SET status = 'FAILED', locked_until = NULL, lease_token = NULL,
                    last_error = 'Lease expirado na ultima tentativa'
                WHERE status = 'PROCESSING' AND locked_until <= ? AND attempts >= ?
                """, Timestamp.from(now), maxAttempts);
        return jdbc.query("""
                WITH candidates AS (
                    SELECT current.event_id FROM outbox_events current
                    WHERE ((current.status = 'PENDING' AND current.next_attempt_at <= ?)
                        OR (current.status = 'PROCESSING' AND current.locked_until <= ?))
                        AND current.attempts < ?
                        AND NOT EXISTS (SELECT 1 FROM outbox_events previous WHERE previous.os_id = current.os_id
                            AND previous.sequence < current.sequence AND previous.status <> 'PUBLISHED')
                    ORDER BY current.sequence LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE outbox_events event SET status = 'PROCESSING', attempts = event.attempts + 1,
                    locked_until = ?, lease_token = gen_random_uuid()
                FROM candidates WHERE event.event_id = candidates.event_id RETURNING event.*
                """, this::toDomain, Timestamp.from(now), Timestamp.from(now), maxAttempts, limit,
                Timestamp.from(now.plus(lease)));
    }

    @Override
    @Transactional
    public boolean markPublished(UUID eventId, UUID token, Instant now) {
        return jdbc.update("""
                UPDATE outbox_events SET status = 'PUBLISHED', published_at = ?, locked_until = NULL,
                    lease_token = NULL, last_error = NULL
                WHERE event_id = ? AND lease_token = ? AND status = 'PROCESSING' AND locked_until > ?
                """, Timestamp.from(now), eventId, token, Timestamp.from(now)) == 1;
    }

    @Override
    @Transactional
    public boolean markAttemptFailed(OutboxEvent event, Instant now, int maxAttempts, Duration backoff, String error) {
        return jdbc.update("""
                UPDATE outbox_events SET status = ?, next_attempt_at = ?, locked_until = NULL,
                    lease_token = NULL, last_error = ?
                WHERE event_id = ? AND lease_token = ? AND status = 'PROCESSING' AND locked_until > ?
                """, event.attempts() >= maxAttempts ? "FAILED" : "PENDING", Timestamp.from(now.plus(backoff)),
                error, event.eventId(), event.leaseToken(), Timestamp.from(now)) == 1;
    }

    private OutboxEvent toDomain(ResultSet row, int index) throws SQLException {
        return new OutboxEvent(row.getObject("event_id", UUID.class), row.getObject("aggregate_id", UUID.class),
                row.getString("aggregate_type"), row.getString("event_type"), row.getObject("os_id", UUID.class),
                row.getObject("saga_id", UUID.class), row.getObject("correlation_id", UUID.class), row.getString("payload"),
                OutboxStatus.valueOf(row.getString("status")), row.getInt("attempts"), instant(row, "created_at"),
                instant(row, "published_at"), instant(row, "next_attempt_at"), instant(row, "locked_until"),
                row.getObject("lease_token", UUID.class), row.getString("last_error"), row.getLong("sequence"));
    }

    private Instant instant(ResultSet row, String field) throws SQLException {
        var value = row.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
}
