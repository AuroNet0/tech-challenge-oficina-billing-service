package com.oficina.billing.infrastructure.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.oficina.billing.domain.enums.PagamentoStatus;
import com.oficina.billing.domain.model.Pagamento;

public record PagamentoResponse(UUID id, UUID osId, UUID sagaId, UUID orcamentoId, PagamentoStatus status,
                                String valor, String moeda, String provider, Instant createdAt, Instant updatedAt,
                                Instant confirmedAt, Instant failedAt, List<TentativaResponse> tentativas) {
    public static PagamentoResponse from(Pagamento pagamento) {
        return new PagamentoResponse(pagamento.id(), pagamento.osId(), pagamento.sagaId(), pagamento.orcamentoId(),
                pagamento.status(), pagamento.valor().toPlainString(), pagamento.moeda(), pagamento.provider(),
                pagamento.createdAt(), pagamento.updatedAt(), pagamento.confirmedAt(), pagamento.failedAt(),
                pagamento.tentativas().stream().map(tentativa -> new TentativaResponse(tentativa.id(),
                        tentativa.status(), tentativa.createdAt(), tentativa.updatedAt(), tentativa.confirmedAt(),
                        tentativa.failedAt())).toList());
    }

    public record TentativaResponse(UUID id, PagamentoStatus status, Instant createdAt, Instant updatedAt,
                                    Instant confirmedAt, Instant failedAt) {
    }
}
