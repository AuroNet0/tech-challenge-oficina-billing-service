CREATE TABLE notificacoes_orcamento (
    id UUID PRIMARY KEY,
    orcamento_id UUID NOT NULL UNIQUE,
    os_id UUID NOT NULL,
    tipo VARCHAR(40) NOT NULL CHECK (tipo = 'ORCAMENTO_DISPONIVEL'),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (orcamento_id, os_id) REFERENCES orcamentos(id, os_id)
);
CREATE INDEX ix_notificacoes_orcamento_os ON notificacoes_orcamento(os_id, created_at DESC, id DESC);

INSERT INTO notificacoes_orcamento (id, orcamento_id, os_id, tipo, created_at)
SELECT gen_random_uuid(), id, os_id, 'ORCAMENTO_DISPONIVEL', CURRENT_TIMESTAMP
FROM orcamentos WHERE status = 'PENDING_APPROVAL';
