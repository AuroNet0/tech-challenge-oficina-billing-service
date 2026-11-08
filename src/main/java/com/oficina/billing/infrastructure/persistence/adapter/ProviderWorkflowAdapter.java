package com.oficina.billing.infrastructure.persistence.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import com.oficina.billing.application.payment.ProviderData.*;
import com.oficina.billing.application.port.out.ProviderWorkflowPort;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ProviderWorkflowAdapter implements ProviderWorkflowPort {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public ProviderWorkflowAdapter(JdbcTemplate jdbc, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    @Override
    public UUID claim(UUID paymentId, Instant now, Duration duration) {
        jdbc.update("INSERT INTO provider_sync(payment_id,next_sync_at) VALUES (?,?) ON CONFLICT DO NOTHING", paymentId, at(now));
        UUID token = UUID.randomUUID();
        return jdbc.update("""
                UPDATE provider_sync SET lease_token=?,locked_until=?,last_attempt_at=?,attempts=attempts+1
                WHERE payment_id=? AND (locked_until IS NULL OR locked_until<=?)
                """, token, at(now.plus(duration)), at(now), paymentId, at(now)) == 1 ? token : null;
    }

    @Override
    public void lock(UUID paymentId, UUID token, Instant now) {
        var matches = jdbc.queryForList("""
                SELECT payment_id FROM provider_sync WHERE payment_id=? AND lease_token=? AND locked_until>?
                FOR UPDATE
                """, UUID.class, paymentId, token, at(now));
        if (matches.isEmpty()) throw new ConcurrencyFailureException("Lease financeiro expirado");
    }

    @Override
    public Checkout checkout(UUID paymentId, UUID attemptId, String key, Instant now) {
        jdbc.update("""
                INSERT INTO provider_checkouts(attempt_id,payment_id,idempotency_key,state,created_at)
                VALUES (?,?,?,'NEW',?) ON CONFLICT DO NOTHING
                """, attemptId, paymentId, key, at(now));
        return checkouts(paymentId).stream().filter(checkout -> checkout.attemptId().equals(attemptId)).findFirst().orElseThrow();
    }

    @Override
    public List<Checkout> checkouts(UUID paymentId) {
        return jdbc.query("SELECT * FROM provider_checkouts WHERE payment_id=? ORDER BY created_at,attempt_id",
                (row, index) -> new Checkout(row.getObject("payment_id", UUID.class), row.getObject("attempt_id", UUID.class),
                        row.getString("idempotency_key"), row.getString("state"), row.getString("preference_id"),
                        row.getString("checkout_url")), paymentId);
    }

    @Override
    public void saveCheckout(Checkout checkout) {
        jdbc.update("UPDATE provider_checkouts SET state=?,preference_id=?,checkout_url=? WHERE attempt_id=? AND payment_id=?",
                checkout.state(), checkout.preferenceId(), checkout.checkoutUrl(), checkout.attemptId(), checkout.paymentId());
    }

    @Override
    public String reviewReason(UUID paymentId) {
        return jdbc.queryForObject("SELECT review_reason FROM provider_sync WHERE payment_id=?", String.class, paymentId);
    }

    @Override
    public void review(UUID paymentId, String reason) {
        jdbc.update("UPDATE provider_sync SET review_reason=? WHERE payment_id=?", reason, paymentId);
    }

    @Override
    public void release(UUID paymentId, UUID token, Instant now, Duration delay, String error) {
        jdbc.update("""
                UPDATE provider_sync SET lease_token=NULL,locked_until=NULL,
                    next_sync_at=CASE WHEN CAST(? AS varchar) IS NULL THEN CAST(? AS timestamptz) ELSE CAST(? AS timestamptz) + make_interval(secs =>
                        least(30 * power(2,least(greatest(attempts-1,0),8)),1800)::integer) END,
                    last_sync_at=?,last_error=?,
                    attempts=CASE WHEN CAST(? AS varchar) IS NULL THEN 0 ELSE attempts END
                WHERE payment_id=? AND lease_token=? AND locked_until>?
                """, error, at(now.plus(delay)), at(now), at(now), error, error, paymentId, token, at(now));
    }

    @Override
    public UUID nextDue(Instant now) {
        var values = jdbc.queryForList("""
                SELECT payment_id FROM provider_sync WHERE next_sync_at<=? AND (locked_until IS NULL OR locked_until<=?)
                ORDER BY next_sync_at,payment_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, UUID.class, at(now), at(now));
        return values.isEmpty() ? null : values.getFirst();
    }

    @Override
    public UUID findPayment(String reference) {
        var values = jdbc.queryForList("SELECT id FROM pagamentos WHERE provider='MERCADO_PAGO' AND external_reference=?",
                UUID.class, reference);
        return values.isEmpty() ? null : values.getFirst();
    }

    @Override
    public String observe(UUID paymentId, UUID attemptId, ExternalPayment payment, Instant now) {
        var rows = jdbc.query("SELECT payment_id,attempt_id,status,provider_updated_at FROM provider_payments WHERE external_id=? FOR UPDATE",
                (row, index) -> new Object[]{row.getObject("payment_id", UUID.class), row.getObject("attempt_id", UUID.class),
                        row.getString("status"), row.getTimestamp("provider_updated_at").toInstant()}, payment.id());
        String fingerprint = digest(payment.id() + ":" + payment.status() + ":" + payment.updatedAt() + ":" + paymentId + ":" + attemptId);
        jdbc.update("""
                INSERT INTO provider_observations(id,payment_id,attempt_id,external_id,status,provider_updated_at,observed_at,fingerprint)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(fingerprint) DO NOTHING
                """, UUID.randomUUID(), paymentId, attemptId, payment.id(), payment.status(), at(payment.updatedAt()), at(now), fingerprint);
        if (!rows.isEmpty()) {
            Object[] old = rows.getFirst();
            if (!old[0].equals(paymentId) || !old[1].equals(attemptId)) return "CONFLICT";
            Instant updatedAt = (Instant) old[3];
            if (payment.updatedAt().isBefore(updatedAt)) return "STALE";
            if (payment.updatedAt().equals(updatedAt) && !old[2].equals(payment.status())) return "CONFLICT";
        }
        jdbc.update("""
                INSERT INTO provider_payments(external_id,payment_id,attempt_id,status,provider_updated_at,observed_at)
                VALUES (?,?,?,?,?,?) ON CONFLICT(external_id) DO UPDATE
                SET status=excluded.status,provider_updated_at=excluded.provider_updated_at,observed_at=excluded.observed_at
                """, payment.id(), paymentId, attemptId, payment.status(), at(payment.updatedAt()), at(now));
        return "CURRENT";
    }

    @Override
    public List<ExternalPayment> knownPayments(UUID paymentId) {
        return jdbc.query("SELECT * FROM provider_payments WHERE payment_id=? ORDER BY provider_updated_at DESC,external_id",
                (row, index) -> new ExternalPayment(row.getString("external_id"), null, null,
                        row.getObject("attempt_id", UUID.class), null, null, row.getString("status"),
                        row.getTimestamp("provider_updated_at").toInstant(), false), paymentId);
    }

    @Override
    public void prepareSettlement(UUID paymentId, String key, Instant now) {
        jdbc.update("""
                UPDATE pagamentos_tentativas SET status='FAILED',updated_at=?,failed_at=?,
                    failure_reason='Tentativa encerrada por liquidacao anterior'
                WHERE pagamento_id=? AND idempotency_key<>? AND status IN ('PENDING','PROCESSING')
                """, at(now), at(now), paymentId, key);
        entityManager.clear();
    }

    @Override
    public void receive(String id, String resourceId, String requestId, Instant now) {
        jdbc.update("""
                INSERT INTO provider_notifications(id,resource_id,request_id,received_at,next_attempt_at)
                VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                """, id, resourceId, requestId, at(now), at(now));
    }

    @Override
    public Notification claimNotification(Instant now, Duration duration) {
        var rows = jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM provider_notifications WHERE status IN ('PENDING','PROCESSING')
                    AND next_attempt_at<=? AND (locked_until IS NULL OR locked_until<=?)
                    ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                ) UPDATE provider_notifications notification SET status='PROCESSING',lease_token=gen_random_uuid(),
                    locked_until=?,attempts=attempts+1 FROM candidate WHERE notification.id=candidate.id
                RETURNING notification.id,notification.resource_id,notification.lease_token
                """, (row, index) -> new Notification(row.getString("id"), row.getString("resource_id"),
                row.getObject("lease_token", UUID.class)), at(now), at(now), at(now.plus(duration)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public void finishNotification(Notification notification, String status, Instant now, Duration delay, String error) {
        int changed = jdbc.update("""
                UPDATE provider_notifications SET status=?,processed_at=CASE WHEN ? IN ('PROCESSED','REVIEW') THEN CAST(? AS timestamptz) ELSE NULL END,
                    next_attempt_at=CAST(? AS timestamptz) + make_interval(secs =>
                        least(30 * power(2,least(greatest(attempts-1,0),8)),1800)::integer),
                    last_error=?,lease_token=NULL,locked_until=NULL
                WHERE id=? AND lease_token=? AND locked_until>?
                """, status, status, at(now), at(now.plus(delay)), error, notification.id(), notification.token(), at(now));
        if (changed != 1) throw new ConcurrencyFailureException("Lease da notificacao expirado");
    }

    private Timestamp at(Instant instant) { return Timestamp.from(instant); }

    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
