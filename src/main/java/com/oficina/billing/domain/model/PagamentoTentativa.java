package com.oficina.billing.domain.model;

import java.time.Instant;
import java.util.UUID;
import com.oficina.billing.domain.enums.PagamentoStatus;
import static com.oficina.billing.domain.model.FinancialRules.*;

public record PagamentoTentativa(UUID id, String idempotencyKey, PagamentoStatus status,
                                String providerPaymentId, Instant createdAt, Instant updatedAt,
                                Instant confirmedAt, Instant failedAt, String failureReason) {
    public PagamentoTentativa {
        required(id, "id");
        text(idempotencyKey, 100, "idempotencyKey");
        required(status, "status");
        required(createdAt, "createdAt");
        required(updatedAt, "updatedAt");
        require(!updatedAt.isBefore(createdAt), "Tentativa anterior a criacao");
        optionalText(providerPaymentId, 100, "providerPaymentId");
        require((status == PagamentoStatus.CONFIRMED) == (confirmedAt != null), "confirmedAt inconsistente");
        require((status == PagamentoStatus.FAILED) == (failedAt != null), "failedAt inconsistente");
        require((status == PagamentoStatus.FAILED) == (failureReason != null), "failureReason inconsistente");
        require(status != PagamentoStatus.CONFIRMED || providerPaymentId != null, "Confirmacao sem identificador");
        require(confirmedAt == null || confirmedAt.equals(updatedAt), "Data de confirmacao inconsistente");
        require(failedAt == null || failedAt.equals(updatedAt), "Data de falha inconsistente");
        optionalText(failureReason, 500, "failureReason");
    }

    public static PagamentoTentativa criar(String key, Instant now) {
        return new PagamentoTentativa(UUID.randomUUID(), key, PagamentoStatus.PENDING, null, now, now, null, null, null);
    }

    public PagamentoTentativa processar(Instant now) {
        require(status == PagamentoStatus.PENDING, "Tentativa nao esta pendente");
        return new PagamentoTentativa(id, idempotencyKey, PagamentoStatus.PROCESSING, null,
                createdAt, now, null, null, null);
    }

    public PagamentoTentativa confirmar(String paymentId, Instant now) {
        text(paymentId, 100, "providerPaymentId");
        if (status == PagamentoStatus.CONFIRMED) {
            require(paymentId.equals(providerPaymentId), "Confirmacao conflitante");
            return this;
        }
        require(status == PagamentoStatus.PENDING || status == PagamentoStatus.PROCESSING, "Tentativa terminal");
        return new PagamentoTentativa(id, idempotencyKey, PagamentoStatus.CONFIRMED, paymentId,
                createdAt, now, now, null, null);
    }

    public PagamentoTentativa falhar(String reason, Instant now) {
        text(reason, 500, "failureReason");
        if (status == PagamentoStatus.FAILED) {
            require(reason.equals(failureReason), "Falha conflitante");
            return this;
        }
        require(status == PagamentoStatus.PENDING || status == PagamentoStatus.PROCESSING, "Tentativa terminal");
        return new PagamentoTentativa(id, idempotencyKey, PagamentoStatus.FAILED, providerPaymentId,
                createdAt, now, null, now, reason);
    }
}
