package com.oficina.billing.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;
import com.oficina.billing.domain.enums.*;

@Entity
@Table(name = "pagamentos")
public class PagamentoEntity {
    @Id
    public UUID id;

    public UUID osId;

    public UUID sagaId;

    public UUID orcamentoId;

    @Enumerated(EnumType.STRING)
    public PagamentoStatus status;

    @Column(precision = 19, scale = 2)
    public BigDecimal valor;

    public String moeda;

    public String provider;

    public String providerPaymentId;

    public String externalReference;

    public String idempotencyKey;

    public Instant createdAt;

    public Instant updatedAt;

    public Instant confirmedAt;

    public Instant failedAt;

    public String failureReason;

    @Version
    public Long version;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "pagamento_id", insertable = false, updatable = false)
    @OrderBy("posicao ASC")
    public List<PagamentoTentativaEntity> tentativas = new ArrayList<>();
}
