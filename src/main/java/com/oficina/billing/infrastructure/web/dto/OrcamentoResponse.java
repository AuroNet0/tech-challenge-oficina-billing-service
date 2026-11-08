package com.oficina.billing.infrastructure.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.oficina.billing.domain.enums.OrcamentoItemType;
import com.oficina.billing.domain.enums.OrcamentoStatus;
import com.oficina.billing.domain.enums.PriceUnit;
import com.oficina.billing.domain.model.Orcamento;

public record OrcamentoResponse(UUID id, UUID osId, UUID sagaId, OrcamentoStatus status,
                                List<ItemResponse> itens, String valorTotal, Instant createdAt,
                                Instant updatedAt, Instant approvedAt, Instant rejectedAt) {
    public static OrcamentoResponse from(Orcamento orcamento) {
        return new OrcamentoResponse(orcamento.id(), orcamento.osId(), orcamento.sagaId(), orcamento.status(),
                orcamento.itens().stream().map(item -> new ItemResponse(item.id(), item.itemId(), item.descricao(),
                        item.tipo(), item.quantidade().toPlainString(), item.unidadePreco(),
                        item.precoUnitario().toPlainString(), item.valorTotal().toPlainString())).toList(),
                orcamento.valorTotal().toPlainString(), orcamento.createdAt(), orcamento.updatedAt(),
                orcamento.approvedAt(), orcamento.rejectedAt());
    }

    public record ItemResponse(UUID id, String itemId, String descricao, OrcamentoItemType tipo,
                               String quantidade, PriceUnit unidadePreco, String precoUnitario, String valorTotal) {
    }
}
