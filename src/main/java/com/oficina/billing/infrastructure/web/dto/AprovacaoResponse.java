package com.oficina.billing.infrastructure.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.oficina.billing.domain.enums.AprovacaoStatus;
import com.oficina.billing.domain.model.Aprovacao;

public record AprovacaoResponse(UUID id, UUID orcamentoId, UUID osId, AprovacaoStatus status,
                                Instant decisionAt, String responsavelId, String motivo,
                                UUID correlationId, Instant createdAt) {
    public static AprovacaoResponse from(Aprovacao aprovacao) {
        return new AprovacaoResponse(aprovacao.id(), aprovacao.orcamentoId(), aprovacao.osId(),
                aprovacao.status(), aprovacao.decisionAt(), aprovacao.responsavelId(), aprovacao.motivo(),
                aprovacao.correlationId(), aprovacao.createdAt());
    }
}
