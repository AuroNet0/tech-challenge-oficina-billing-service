package com.oficina.billing.infrastructure.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.oficina.billing.domain.enums.OrcamentoStatus;
import com.oficina.billing.domain.model.NotificacaoOrcamento;

public record NotificacaoOrcamentoResponse(UUID id, UUID orcamentoId, UUID osId, String tipo,
                                          Instant createdAt, String mensagem, OrcamentoStatus statusOrcamento) {
    public static NotificacaoOrcamentoResponse from(NotificacaoOrcamento notification) {
        String message = notification.statusOrcamento() == OrcamentoStatus.PENDING_APPROVAL
                ? "Existe um orcamento aguardando sua aprovacao."
                : "Orcamento disponibilizado anteriormente; consulte a situacao atual.";
        return new NotificacaoOrcamentoResponse(notification.id(), notification.orcamentoId(), notification.osId(),
                notification.tipo(), notification.createdAt(), message, notification.statusOrcamento());
    }
}
