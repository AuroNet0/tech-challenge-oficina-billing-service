package com.oficina.billing.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import com.oficina.billing.domain.enums.*;

@Entity
@Table(name = "aprovacoes")
public class AprovacaoEntity {
    @Id
    public UUID id;

    public UUID orcamentoId;

    public UUID osId;

    @Enumerated(EnumType.STRING)
    public AprovacaoStatus status;

    public Instant decisionAt;

    public String responsavelId;

    public String motivo;

    public UUID correlationId;

    public Instant createdAt;
}
