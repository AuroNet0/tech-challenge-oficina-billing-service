package com.oficina.billing.domain.model;

import java.time.Instant;
import java.util.UUID;
import com.oficina.billing.domain.enums.AprovacaoStatus;
import static com.oficina.billing.domain.model.FinancialRules.*;

public record Aprovacao(UUID id, UUID orcamentoId, UUID osId, AprovacaoStatus status, Instant decisionAt,
                        String responsavelId, String motivo, UUID correlationId, Instant createdAt) {
    public Aprovacao {
        required(id, "id");
        required(orcamentoId, "orcamentoId");
        required(osId, "osId");
        required(status, "status");
        required(decisionAt, "decisionAt");
        required(createdAt, "createdAt");
        require(!createdAt.isBefore(decisionAt), "Registro anterior a decisao");
        optionalText(responsavelId, 100, "responsavelId");
        optionalText(motivo, 500, "motivo");
    }

    public static Aprovacao registrar(Orcamento orcamento, String responsavelId, String motivo,
                                     UUID correlationId, Instant now) {
        required(orcamento, "orcamento");
        require(orcamento.approvedAt() != null || orcamento.rejectedAt() != null, "Orcamento sem decisao");
        return new Aprovacao(UUID.randomUUID(), orcamento.id(), orcamento.osId(),
                AprovacaoStatus.valueOf(orcamento.status().name()), orcamento.updatedAt(),
                responsavelId, motivo, correlationId, now);
    }
}
