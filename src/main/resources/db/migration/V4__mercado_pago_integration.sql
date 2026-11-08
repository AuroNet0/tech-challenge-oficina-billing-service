CREATE TABLE provider_sync (
    payment_id UUID PRIMARY KEY REFERENCES pagamentos(id),
    lease_token UUID,
    locked_until TIMESTAMPTZ,
    next_sync_at TIMESTAMPTZ NOT NULL,
    last_sync_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error VARCHAR(100),
    review_reason VARCHAR(100),
    CHECK ((lease_token IS NULL) = (locked_until IS NULL))
);
CREATE INDEX ix_provider_sync_due ON provider_sync(next_sync_at, locked_until);

ALTER TABLE pagamentos_tentativas ADD CONSTRAINT uq_provider_attempt_identity UNIQUE(id,pagamento_id,idempotency_key);
CREATE TABLE provider_checkouts (
    attempt_id UUID PRIMARY KEY REFERENCES pagamentos_tentativas(id),
    payment_id UUID NOT NULL REFERENCES pagamentos(id),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    state VARCHAR(20) NOT NULL CHECK (state IN ('NEW','CREATING','UNKNOWN','READY','REVIEW','CLOSING','CLOSED')),
    preference_id VARCHAR(150) UNIQUE,
    checkout_url VARCHAR(2048),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (attempt_id,payment_id,idempotency_key) REFERENCES pagamentos_tentativas(id,pagamento_id,idempotency_key),
    CHECK (state NOT IN ('READY','CLOSING','CLOSED') OR preference_id IS NOT NULL),
    CHECK ((preference_id IS NULL) = (checkout_url IS NULL)),
    UNIQUE (attempt_id, payment_id)
);
CREATE INDEX ix_provider_checkouts_payment ON provider_checkouts(payment_id);
CREATE FUNCTION protect_checkout_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.attempt_id <> OLD.attempt_id OR NEW.payment_id <> OLD.payment_id OR NEW.idempotency_key <> OLD.idempotency_key
        OR (OLD.preference_id IS NOT NULL AND (NEW.preference_id IS DISTINCT FROM OLD.preference_id
            OR NEW.checkout_url IS DISTINCT FROM OLD.checkout_url)) THEN
        RAISE EXCEPTION 'Identidade do checkout imutavel' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tr_checkout_identity BEFORE UPDATE ON provider_checkouts
    FOR EACH ROW EXECUTE FUNCTION protect_checkout_identity();

CREATE TABLE provider_notifications (
    id VARCHAR(64) PRIMARY KEY,
    resource_id VARCHAR(30) NOT NULL,
    request_id VARCHAR(120) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','PROCESSED','REVIEW')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(100),
    CHECK ((lease_token IS NULL) = (locked_until IS NULL))
);
CREATE INDEX ix_provider_notifications_due ON provider_notifications(status, next_attempt_at, locked_until);

CREATE TABLE provider_payments (
    external_id VARCHAR(100) PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES pagamentos(id),
    attempt_id UUID NOT NULL,
    status VARCHAR(64) NOT NULL,
    provider_updated_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (attempt_id, payment_id) REFERENCES provider_checkouts(attempt_id, payment_id)
);
CREATE INDEX ix_provider_payments_payment ON provider_payments(payment_id, status);

CREATE TABLE provider_observations (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES pagamentos(id),
    attempt_id UUID REFERENCES pagamentos_tentativas(id),
    external_id VARCHAR(100),
    status VARCHAR(64) NOT NULL,
    provider_updated_at TIMESTAMPTZ,
    observed_at TIMESTAMPTZ NOT NULL,
    previous_attempt JSONB,
    fingerprint VARCHAR(64) NOT NULL UNIQUE
);
CREATE INDEX ix_provider_observations_payment ON provider_observations(payment_id, observed_at);
CREATE FUNCTION immutable_provider_observation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Historico de conciliacao imutavel' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER tr_provider_observation_immutable BEFORE UPDATE OR DELETE ON provider_observations
    FOR EACH ROW EXECUTE FUNCTION immutable_provider_observation();

CREATE UNIQUE INDEX uq_payment_confirmed_outbox ON outbox_events(aggregate_id)
    WHERE aggregate_type = 'PAYMENT' AND event_type = 'payment.confirmed.v1';

CREATE OR REPLACE FUNCTION protect_financial_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'aprovacoes' THEN
        RAISE EXCEPTION 'Decisao imutavel' USING ERRCODE = '23514';
    ELSIF TG_TABLE_NAME = 'orcamentos' AND OLD.status <> 'PENDING_APPROVAL' THEN
        IF (to_jsonb(NEW) - 'version') IS DISTINCT FROM (to_jsonb(OLD) - 'version') THEN
            RAISE EXCEPTION 'Orcamento terminal imutavel' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'pagamentos_tentativas' AND OLD.status IN ('FAILED', 'CONFIRMED') THEN
        IF TG_OP <> 'DELETE' AND OLD.status = 'FAILED' AND NEW.status = 'CONFIRMED'
            AND NEW.id = OLD.id AND NEW.pagamento_id = OLD.pagamento_id
            AND NEW.idempotency_key = OLD.idempotency_key AND NEW.posicao = OLD.posicao
            AND NEW.created_at = OLD.created_at AND NEW.updated_at >= OLD.updated_at
            AND EXISTS (SELECT 1 FROM provider_payments WHERE attempt_id = OLD.id
                AND payment_id = OLD.pagamento_id AND status = 'approved' AND external_id = NEW.provider_payment_id) THEN
            INSERT INTO provider_observations(id, payment_id, attempt_id, external_id, status,
                observed_at, previous_attempt, fingerprint)
            VALUES (gen_random_uuid(), OLD.pagamento_id, OLD.id, NEW.provider_payment_id, 'LATE_SETTLEMENT',
                NEW.updated_at, to_jsonb(OLD), 'late:' || OLD.id);
        ELSE
            RAISE EXCEPTION 'Tentativa terminal imutavel' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'pagamentos' THEN
        IF (OLD.status = 'CONFIRMED' AND (to_jsonb(NEW) - 'version') IS DISTINCT FROM (to_jsonb(OLD) - 'version'))
            OR NEW.valor <> OLD.valor OR NEW.orcamento_id <> OLD.orcamento_id
            OR NEW.os_id <> OLD.os_id OR NEW.saga_id <> OLD.saga_id
            OR NEW.provider <> OLD.provider OR NEW.external_reference <> OLD.external_reference THEN
            RAISE EXCEPTION 'Pagamento confirmado ou identidade financeira imutavel' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION validate_payment_attempts() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    payment_id UUID;
    payment_row pagamentos%ROWTYPE;
    attempt_row pagamentos_tentativas%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'pagamentos' THEN payment_id := NEW.id;
    ELSE payment_id := COALESCE(NEW.pagamento_id, OLD.pagamento_id); END IF;
    SELECT * INTO payment_row FROM pagamentos WHERE id = payment_id;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT * INTO attempt_row FROM pagamentos_tentativas WHERE pagamento_id = payment_id
        ORDER BY (status = 'CONFIRMED') DESC, posicao DESC LIMIT 1;
    IF NOT FOUND OR payment_row.idempotency_key <> attempt_row.idempotency_key
        OR payment_row.status <> attempt_row.status OR payment_row.updated_at <> attempt_row.updated_at
        OR payment_row.provider_payment_id IS DISTINCT FROM attempt_row.provider_payment_id
        OR payment_row.confirmed_at IS DISTINCT FROM attempt_row.confirmed_at
        OR payment_row.failed_at IS DISTINCT FROM attempt_row.failed_at
        OR payment_row.failure_reason IS DISTINCT FROM attempt_row.failure_reason THEN
        RAISE EXCEPTION 'Resumo do pagamento e tentativa efetiva inconsistentes' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
