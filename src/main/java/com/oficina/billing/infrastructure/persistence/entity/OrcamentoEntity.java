package com.oficina.billing.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;
import com.oficina.billing.domain.enums.*;

@Entity
@Table(name = "orcamentos")
public class OrcamentoEntity {
    @Id
    public UUID id;

    public UUID osId;

    public UUID sagaId;

    @Enumerated(EnumType.STRING)
    public OrcamentoStatus status;

    @Column(precision = 19, scale = 2)
    public BigDecimal valorTotal;

    public Instant createdAt;

    public Instant updatedAt;

    public Instant approvedAt;

    public Instant rejectedAt;

    @Version
    public Long version;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "orcamento_id", insertable = false, updatable = false)
    @OrderBy("posicao ASC")
    public List<OrcamentoItemEntity> itens = new ArrayList<>();
}
