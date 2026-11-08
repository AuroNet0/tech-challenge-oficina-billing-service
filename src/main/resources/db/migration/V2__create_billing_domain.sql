CREATE TABLE orcamentos (
    id UUID PRIMARY KEY,
    os_id UUID NOT NULL UNIQUE,
    saga_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED')),
    valor_total NUMERIC(19,2) NOT NULL CHECK (valor_total >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
    approved_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (id, os_id),
    UNIQUE (id, os_id, saga_id),
    CHECK ((status = 'APPROVED') = (approved_at IS NOT NULL)),
    CHECK ((status = 'REJECTED') = (rejected_at IS NOT NULL)),
    CHECK (approved_at IS NULL OR approved_at = updated_at),
    CHECK (rejected_at IS NULL OR rejected_at = updated_at)
);
CREATE INDEX ix_orcamentos_saga ON orcamentos(saga_id);
CREATE INDEX ix_orcamentos_status ON orcamentos(status);

CREATE TABLE orcamento_itens (
    id UUID PRIMARY KEY,
    orcamento_id UUID NOT NULL REFERENCES orcamentos(id),
    posicao INTEGER NOT NULL CHECK (posicao >= 0),
    item_id VARCHAR(100) NOT NULL CHECK (btrim(item_id) <> ''),
    descricao VARCHAR(500) NOT NULL CHECK (btrim(descricao) <> ''),
    tipo VARCHAR(20) NOT NULL CHECK (tipo IN ('SERVICO', 'PECA', 'MATERIAL')),
    quantidade NUMERIC(19,6) NOT NULL CHECK (quantidade > 0),
    quantidade_escala INTEGER NOT NULL CHECK (quantidade_escala BETWEEN 0 AND 6),
    unidade_preco VARCHAR(20) NOT NULL CHECK (unidade_preco IN ('UNIDADE', 'HORA', 'KIT')),
    preco_unitario NUMERIC(19,6) NOT NULL CHECK (preco_unitario >= 0),
    valor_total NUMERIC(19,2) NOT NULL CHECK (valor_total >= 0),
    UNIQUE (orcamento_id, item_id),
    UNIQUE (orcamento_id, posicao),
    CHECK (quantidade = trunc(quantidade, quantidade_escala)),
    CHECK (valor_total = quantidade * preco_unitario)
);

CREATE TABLE aprovacoes (
    id UUID PRIMARY KEY,
    orcamento_id UUID NOT NULL UNIQUE,
    os_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('APPROVED', 'REJECTED')),
    decision_at TIMESTAMPTZ NOT NULL,
    responsavel_id VARCHAR(100) CHECK (btrim(responsavel_id) <> ''),
    motivo VARCHAR(500) CHECK (btrim(motivo) <> ''),
    correlation_id UUID,
    created_at TIMESTAMPTZ NOT NULL CHECK (created_at >= decision_at),
    FOREIGN KEY (orcamento_id, os_id) REFERENCES orcamentos(id, os_id)
);
CREATE INDEX ix_aprovacoes_os ON aprovacoes(os_id);
CREATE INDEX ix_aprovacoes_status ON aprovacoes(status);

CREATE TABLE pagamentos (
    id UUID PRIMARY KEY,
    os_id UUID NOT NULL,
    saga_id UUID NOT NULL,
    orcamento_id UUID NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'CONFIRMED', 'FAILED')),
    valor NUMERIC(19,2) NOT NULL CHECK (valor >= 0),
    moeda VARCHAR(3) NOT NULL CHECK (moeda = 'BRL'),
    provider VARCHAR(50) NOT NULL CHECK (btrim(provider) <> ''),
    provider_payment_id VARCHAR(100),
    external_reference VARCHAR(100) NOT NULL CHECK (btrim(external_reference) <> ''),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE CHECK (btrim(idempotency_key) <> ''),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
    confirmed_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    failure_reason VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (orcamento_id, os_id, saga_id) REFERENCES orcamentos(id, os_id, saga_id),
    UNIQUE (provider, external_reference),
    UNIQUE (provider, provider_payment_id),
    CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    CHECK ((status = 'FAILED') = (failed_at IS NOT NULL)),
    CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CHECK (status <> 'CONFIRMED' OR provider_payment_id IS NOT NULL)
);
CREATE INDEX ix_pagamentos_os ON pagamentos(os_id);
CREATE INDEX ix_pagamentos_saga ON pagamentos(saga_id);
CREATE INDEX ix_pagamentos_status ON pagamentos(status);

CREATE TABLE pagamentos_tentativas (
    id UUID PRIMARY KEY,
    pagamento_id UUID NOT NULL REFERENCES pagamentos(id),
    posicao INTEGER NOT NULL CHECK (posicao >= 0),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE CHECK (btrim(idempotency_key) <> ''),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'CONFIRMED', 'FAILED')),
    provider_payment_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (updated_at >= created_at),
    confirmed_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    failure_reason VARCHAR(500) CHECK (btrim(failure_reason) <> ''),
    UNIQUE (pagamento_id, posicao),
    UNIQUE (pagamento_id, idempotency_key),
    CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    CHECK ((status = 'FAILED') = (failed_at IS NOT NULL)),
    CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CHECK (status <> 'CONFIRMED' OR provider_payment_id IS NOT NULL),
    CHECK (confirmed_at IS NULL OR confirmed_at = updated_at),
    CHECK (failed_at IS NULL OR failed_at = updated_at)
);
CREATE INDEX ix_tentativas_status ON pagamentos_tentativas(status);
CREATE UNIQUE INDEX uq_tentativa_ativa ON pagamentos_tentativas(pagamento_id) WHERE status <> 'FAILED';
ALTER TABLE pagamentos ADD CONSTRAINT fk_pagamento_tentativa_atual
    FOREIGN KEY (id, idempotency_key) REFERENCES pagamentos_tentativas(pagamento_id, idempotency_key)
    DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION validate_quote_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    quote_id UUID;
    quote_row orcamentos%ROWTYPE;
    item_count BIGINT;
    item_total NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'orcamentos' THEN
        quote_id := COALESCE(NEW.id, OLD.id);
    ELSE
        quote_id := COALESCE(NEW.orcamento_id, OLD.orcamento_id);
    END IF;
    SELECT * INTO quote_row FROM orcamentos WHERE id = quote_id;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT count(*), sum(valor_total) INTO item_count, item_total FROM orcamento_itens WHERE orcamento_id = quote_id;
    IF item_count = 0 OR item_total <> quote_row.valor_total THEN
        RAISE EXCEPTION 'Orcamento sem itens ou total inconsistente' USING ERRCODE = '23514';
    END IF;
    IF quote_row.status IN ('APPROVED', 'REJECTED') THEN
        IF NOT EXISTS (SELECT 1 FROM aprovacoes WHERE orcamento_id = quote_id AND status = quote_row.status
            AND decision_at = quote_row.updated_at) THEN
            RAISE EXCEPTION 'Decisao e orcamento devem ser persistidos atomicamente' USING ERRCODE = '23514';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM aprovacoes WHERE orcamento_id = quote_id) THEN
        RAISE EXCEPTION 'Decisao incompatível com estado do orcamento' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ct_orcamento_consistency AFTER INSERT OR UPDATE ON orcamentos
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_quote_consistency();
CREATE CONSTRAINT TRIGGER ct_itens_consistency AFTER INSERT OR UPDATE OR DELETE ON orcamento_itens
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_quote_consistency();
CREATE CONSTRAINT TRIGGER ct_aprovacao_consistency AFTER INSERT ON aprovacoes
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_quote_consistency();

CREATE FUNCTION protect_financial_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'aprovacoes' THEN
        RAISE EXCEPTION 'Decisao imutavel' USING ERRCODE = '23514';
    ELSIF TG_TABLE_NAME = 'orcamentos' AND OLD.status <> 'PENDING_APPROVAL' THEN
        IF (to_jsonb(NEW) - 'version') IS DISTINCT FROM (to_jsonb(OLD) - 'version') THEN
            RAISE EXCEPTION 'Orcamento terminal imutavel' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'pagamentos_tentativas' AND OLD.status IN ('FAILED', 'CONFIRMED') THEN
        RAISE EXCEPTION 'Tentativa terminal imutavel' USING ERRCODE = '23514';
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
CREATE TRIGGER tr_aprovacao_immutable BEFORE UPDATE OR DELETE ON aprovacoes
    FOR EACH ROW EXECUTE FUNCTION protect_financial_history();
CREATE TRIGGER tr_orcamento_terminal BEFORE UPDATE ON orcamentos
    FOR EACH ROW EXECUTE FUNCTION protect_financial_history();
CREATE TRIGGER tr_tentativa_terminal BEFORE UPDATE OR DELETE ON pagamentos_tentativas
    FOR EACH ROW EXECUTE FUNCTION protect_financial_history();
CREATE TRIGGER tr_pagamento_terminal BEFORE UPDATE ON pagamentos
    FOR EACH ROW EXECUTE FUNCTION protect_financial_history();

CREATE FUNCTION protect_quote_items() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    quote_id UUID;
    quote_status VARCHAR(30);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.orcamento_id <> OLD.orcamento_id THEN
        RAISE EXCEPTION 'Item nao pode mudar de orcamento' USING ERRCODE = '23514';
    END IF;
    quote_id := COALESCE(OLD.orcamento_id, NEW.orcamento_id);
    SELECT status INTO quote_status FROM orcamentos WHERE id = quote_id FOR UPDATE;
    IF quote_status <> 'PENDING_APPROVAL' THEN
        RAISE EXCEPTION 'Itens de orcamento terminal imutaveis' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tr_quote_items_immutable BEFORE INSERT OR UPDATE OR DELETE ON orcamento_itens
    FOR EACH ROW EXECUTE FUNCTION protect_quote_items();

CREATE FUNCTION validate_payment_quote() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM orcamentos WHERE id = NEW.orcamento_id AND os_id = NEW.os_id
        AND saga_id = NEW.saga_id AND status = 'APPROVED' AND valor_total = NEW.valor) THEN
        RAISE EXCEPTION 'Pagamento exige valor e identidade do orcamento aprovado' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tr_payment_quote BEFORE INSERT OR UPDATE ON pagamentos
    FOR EACH ROW EXECUTE FUNCTION validate_payment_quote();

CREATE FUNCTION validate_payment_attempts() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    payment_id UUID;
    payment_row pagamentos%ROWTYPE;
    attempt_row pagamentos_tentativas%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'pagamentos' THEN payment_id := NEW.id;
    ELSE payment_id := COALESCE(NEW.pagamento_id, OLD.pagamento_id); END IF;
    SELECT * INTO payment_row FROM pagamentos WHERE id = payment_id;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT * INTO attempt_row FROM pagamentos_tentativas WHERE pagamento_id = payment_id ORDER BY posicao DESC LIMIT 1;
    IF NOT FOUND OR payment_row.idempotency_key <> attempt_row.idempotency_key
        OR payment_row.status <> attempt_row.status OR payment_row.updated_at <> attempt_row.updated_at
        OR payment_row.provider_payment_id IS DISTINCT FROM attempt_row.provider_payment_id
        OR payment_row.confirmed_at IS DISTINCT FROM attempt_row.confirmed_at
        OR payment_row.failed_at IS DISTINCT FROM attempt_row.failed_at
        OR payment_row.failure_reason IS DISTINCT FROM attempt_row.failure_reason THEN
        RAISE EXCEPTION 'Resumo do pagamento e tentativa atual inconsistentes' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER ct_payment_attempts AFTER INSERT OR UPDATE ON pagamentos
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_payment_attempts();
CREATE CONSTRAINT TRIGGER ct_attempts_payment AFTER INSERT OR UPDATE OR DELETE ON pagamentos_tentativas
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_payment_attempts();
