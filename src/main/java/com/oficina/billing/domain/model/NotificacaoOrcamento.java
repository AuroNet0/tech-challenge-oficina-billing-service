package com.oficina.billing.domain.model;

import java.time.Instant;
import java.util.UUID;
import com.oficina.billing.domain.enums.OrcamentoStatus;

public record NotificacaoOrcamento(UUID id, UUID orcamentoId, UUID osId, String tipo,
                                  Instant createdAt, OrcamentoStatus statusOrcamento) {
}
