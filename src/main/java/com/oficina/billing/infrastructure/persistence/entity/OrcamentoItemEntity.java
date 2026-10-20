package com.oficina.billing.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import com.oficina.billing.domain.enums.*;

@Entity
@Table(name = "orcamento_itens")
public class OrcamentoItemEntity {
    @Id
    public UUID id;

    @Column(name = "orcamento_id")
    public UUID orcamentoId;

    public int posicao;

    public String itemId;

    public String descricao;

    @Enumerated(EnumType.STRING)
    public OrcamentoItemType tipo;

    @Column(precision = 19, scale = 6)
    public BigDecimal quantidade;

    public int quantidadeEscala;

    @Enumerated(EnumType.STRING)
    public PriceUnit unidadePreco;

    @Column(precision = 19, scale = 6)
    public BigDecimal precoUnitario;

    @Column(precision = 19, scale = 2)
    public BigDecimal valorTotal;
}
