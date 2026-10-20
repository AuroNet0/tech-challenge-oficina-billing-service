package com.oficina.billing.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import com.oficina.billing.domain.enums.OrcamentoStatus;
import static com.oficina.billing.domain.model.FinancialRules.*;

public final class Orcamento {
    private final UUID id;
    private final UUID osId;
    private final UUID sagaId;
    private final List<OrcamentoItem> itens;
    private final BigDecimal valorTotal;
    private final Instant createdAt;
    private final Long version;
    private OrcamentoStatus status;
    private Instant updatedAt;
    private Instant approvedAt;
    private Instant rejectedAt;

    private Orcamento(UUID id, UUID osId, UUID sagaId, List<OrcamentoItem> itens, OrcamentoStatus status,
                      Instant createdAt, Instant updatedAt, Instant approvedAt, Instant rejectedAt, Long version) {
        this.id = required(id, "id");
        this.osId = required(osId, "osId");
        this.sagaId = required(sagaId, "sagaId");
        required(itens, "itens");
        require(!itens.isEmpty() && itens.stream().noneMatch(java.util.Objects::isNull), "Itens obrigatorios");
        this.itens = List.copyOf(itens);
        var itemIds = new HashSet<String>();
        var ids = new HashSet<UUID>();
        for (var item : itens) {
            require(itemIds.add(item.itemId()) && ids.add(item.id()), "Item duplicado");
        }
        this.valorTotal = money(itens.stream().map(OrcamentoItem::valorTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        this.status = required(status, "status");
        this.createdAt = required(createdAt, "createdAt");
        this.updatedAt = required(updatedAt, "updatedAt");
        require(!updatedAt.isBefore(createdAt), "updatedAt anterior a criacao");
        require((status == OrcamentoStatus.APPROVED) == (approvedAt != null), "approvedAt inconsistente");
        require((status == OrcamentoStatus.REJECTED) == (rejectedAt != null), "rejectedAt inconsistente");
        require(approvedAt == null || approvedAt.equals(updatedAt), "Data de aprovacao inconsistente");
        require(rejectedAt == null || rejectedAt.equals(updatedAt), "Data de rejeicao inconsistente");
        require(version == null || version >= 0, "Version invalida");
        this.approvedAt = approvedAt;
        this.rejectedAt = rejectedAt;
        this.version = version;
    }

    public static Orcamento criar(UUID osId, UUID sagaId, List<OrcamentoItem> itens, Instant now) {
        return new Orcamento(UUID.randomUUID(), osId, sagaId, itens, OrcamentoStatus.PENDING_APPROVAL,
                now, now, null, null, null);
    }

    public static Orcamento restaurar(UUID id, UUID osId, UUID sagaId, List<OrcamentoItem> itens,
                                     OrcamentoStatus status, Instant createdAt, Instant updatedAt,
                                     Instant approvedAt, Instant rejectedAt, Long version) {
        return new Orcamento(id, osId, sagaId, itens, status, createdAt, updatedAt, approvedAt, rejectedAt, version);
    }

    public void aprovar(Instant now) {
        decidir(OrcamentoStatus.APPROVED, now);
        approvedAt = now;
    }

    public void rejeitar(Instant now) {
        decidir(OrcamentoStatus.REJECTED, now);
        rejectedAt = now;
    }

    public void cancelar(Instant now) {
        decidir(OrcamentoStatus.CANCELLED, now);
    }

    private void decidir(OrcamentoStatus decision, Instant now) {
        require(status == OrcamentoStatus.PENDING_APPROVAL, "Orcamento ja possui decisao terminal");
        required(now, "decisionAt");
        require(!now.isBefore(updatedAt), "Decisao anterior ao orcamento");
        status = decision;
        updatedAt = now;
    }

    public UUID id() { return id; }
    public UUID osId() { return osId; }
    public UUID sagaId() { return sagaId; }
    public List<OrcamentoItem> itens() { return itens; }
    public BigDecimal valorTotal() { return valorTotal; }
    public OrcamentoStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant approvedAt() { return approvedAt; }
    public Instant rejectedAt() { return rejectedAt; }
    public Long version() { return version; }
}
