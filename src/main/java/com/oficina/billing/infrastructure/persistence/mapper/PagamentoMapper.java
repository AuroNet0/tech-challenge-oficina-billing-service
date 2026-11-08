package com.oficina.billing.infrastructure.persistence.mapper;

import com.oficina.billing.domain.model.*;
import com.oficina.billing.infrastructure.persistence.entity.*;

public final class PagamentoMapper {
    private PagamentoMapper() {
    }

    public static PagamentoEntity toEntity(Pagamento domain) {
        var entity = new PagamentoEntity();
        entity.id = domain.id();
        entity.osId = domain.osId();
        entity.sagaId = domain.sagaId();
        entity.orcamentoId = domain.orcamentoId();
        entity.status = domain.status();
        entity.valor = domain.valor();
        entity.moeda = domain.moeda();
        entity.provider = domain.provider();
        entity.providerPaymentId = domain.providerPaymentId();
        entity.externalReference = domain.externalReference();
        entity.idempotencyKey = domain.idempotencyKey();
        entity.createdAt = domain.createdAt();
        entity.updatedAt = domain.updatedAt();
        entity.confirmedAt = domain.confirmedAt();
        entity.failedAt = domain.failedAt();
        entity.failureReason = domain.failureReason();
        entity.version = domain.version();
        for (int index = 0; index < domain.tentativas().size(); index++) {
            var child = domain.tentativas().get(index);
            var item = new PagamentoTentativaEntity();
            item.id = child.id();
            item.pagamentoId = domain.id();
            item.posicao = index;
            item.idempotencyKey = child.idempotencyKey();
            item.status = child.status();
            item.providerPaymentId = child.providerPaymentId();
            item.createdAt = child.createdAt();
            item.updatedAt = child.updatedAt();
            item.confirmedAt = child.confirmedAt();
            item.failedAt = child.failedAt();
            item.failureReason = child.failureReason();
            entity.tentativas.add(item);
        }
        return entity;
    }

    public static Pagamento toDomain(PagamentoEntity entity) {
        var tentativas = entity.tentativas.stream().map(item -> new PagamentoTentativa(
                item.id, item.idempotencyKey, item.status, item.providerPaymentId, item.createdAt, item.updatedAt,
                item.confirmedAt, item.failedAt, item.failureReason)).toList();
        var domain = Pagamento.restaurar(entity.id, entity.osId, entity.sagaId, entity.orcamentoId, entity.valor,
                entity.moeda, entity.provider, entity.externalReference, entity.createdAt, entity.version, tentativas);
        FinancialRules.require(domain.status() == entity.status && domain.idempotencyKey().equals(entity.idempotencyKey),
                "Tentativa atual inconsistente");
        return domain;
    }
}
