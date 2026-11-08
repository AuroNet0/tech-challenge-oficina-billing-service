CREATE TABLE inbox_messages (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    os_id UUID NOT NULL,
    saga_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    payload TEXT NOT NULL CHECK (jsonb_typeof(payload::jsonb) = 'object'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'REJECTED')),
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    received_order BIGSERIAL NOT NULL UNIQUE,
    CHECK ((status = 'RECEIVED') = (processed_at IS NULL))
);
CREATE INDEX ix_inbox_origin ON inbox_messages(os_id, saga_id, event_type, received_order);
CREATE INDEX ix_inbox_status ON inbox_messages(status, received_at);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    os_id UUID NOT NULL,
    saga_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    payload TEXT NOT NULL CHECK (jsonb_typeof(payload::jsonb) = 'object'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    locked_until TIMESTAMPTZ,
    lease_token UUID,
    last_error VARCHAR(500),
    sequence BIGSERIAL NOT NULL UNIQUE,
    CHECK ((status = 'PROCESSING') = (locked_until IS NOT NULL AND lease_token IS NOT NULL)),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX ix_outbox_pending ON outbox_events(status, next_attempt_at, sequence);
CREATE INDEX ix_outbox_expired ON outbox_events(status, locked_until);
CREATE INDEX ix_outbox_order ON outbox_events(os_id, sequence) WHERE status <> 'PUBLISHED';
CREATE INDEX ix_outbox_saga ON outbox_events(saga_id);
CREATE INDEX ix_outbox_aggregate ON outbox_events(aggregate_id);
