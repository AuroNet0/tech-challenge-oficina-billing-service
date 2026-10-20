package com.oficina.billing.infrastructure.persistence.mapper;

import com.oficina.billing.domain.model.*;
import com.oficina.billing.infrastructure.persistence.entity.*;

public final class OrcamentoMapper {
    private OrcamentoMapper() {
    }

    public static OrcamentoEntity toEntity(Orcamento domain) {
        var entity = new OrcamentoEntity();
        entity.id = domain.id();
        entity.osId = domain.osId();
        entity.sagaId = domain.sagaId();
        entity.status = domain.status();
        entity.valorTotal = domain.valorTotal();
        entity.createdAt = domain.createdAt();
        entity.updatedAt = domain.updatedAt();
        entity.approvedAt = domain.approvedAt();
        entity.rejectedAt = domain.rejectedAt();
        entity.version = domain.version();
        for (int index = 0; index < domain.itens().size(); index++) {
            var child = domain.itens().get(index);
            var item = new OrcamentoItemEntity();
            item.id = child.id();
            item.orcamentoId = domain.id();
            item.posicao = index;
            item.itemId = child.itemId();
            item.descricao = child.descricao();
            item.tipo = child.tipo();
            item.quantidade = child.quantidade();
            item.quantidadeEscala = child.quantidade().scale();
            item.unidadePreco = child.unidadePreco();
            item.precoUnitario = child.precoUnitario();
            item.valorTotal = child.valorTotal();
            entity.itens.add(item);
        }
        return entity;
    }

    public static Orcamento toDomain(OrcamentoEntity entity) {
        var itens = entity.itens.stream().map(item -> new OrcamentoItem(item.id, item.itemId,
                item.descricao, item.tipo, item.quantidade.setScale(item.quantidadeEscala),
                item.unidadePreco, item.precoUnitario)).toList();
        var domain = Orcamento.restaurar(entity.id, entity.osId, entity.sagaId, itens, entity.status,
                entity.createdAt, entity.updatedAt, entity.approvedAt, entity.rejectedAt, entity.version);
        FinancialRules.require(domain.valorTotal().compareTo(entity.valorTotal) == 0, "Total persistido inconsistente");
        return domain;
    }
}
