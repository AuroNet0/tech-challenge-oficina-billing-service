package com.oficina.billing.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import com.oficina.billing.domain.enums.*;

@Entity
@Table(name = "pagamentos_tentativas")
public class PagamentoTentativaEntity {
    @Id
    public UUID id;

    @Column(name = "pagamento_id")
    public UUID pagamentoId;

    public int posicao;

    public String idempotencyKey;

    @Enumerated(EnumType.STRING)
    public PagamentoStatus status;

    public String providerPaymentId;

    public Instant createdAt;

    public Instant updatedAt;

    public Instant confirmedAt;

    public Instant failedAt;

    public String failureReason;
}
