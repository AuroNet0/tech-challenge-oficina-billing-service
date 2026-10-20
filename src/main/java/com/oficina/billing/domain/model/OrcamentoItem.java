package com.oficina.billing.domain.model;

import java.math.BigDecimal;
import java.util.UUID;
import com.oficina.billing.domain.enums.OrcamentoItemType;
import com.oficina.billing.domain.enums.PriceUnit;
import static com.oficina.billing.domain.model.FinancialRules.*;

public record OrcamentoItem(UUID id, String itemId, String descricao, OrcamentoItemType tipo,
                            BigDecimal quantidade, PriceUnit unidadePreco, BigDecimal precoUnitario) {
    public OrcamentoItem {
        required(id, "id");
        text(itemId, 100, "itemId");
        text(descricao, 500, "descricao");
        required(tipo, "tipo");
        required(unidadePreco, "unidadePreco");
        decimal(quantidade, 19, 6, "quantidade");
        require(quantidade.signum() > 0, "Quantidade deve ser positiva");
        decimal(precoUnitario, 19, 6, "precoUnitario");
        money(quantidade.multiply(precoUnitario));
    }

    public static OrcamentoItem criar(String itemId, String descricao, OrcamentoItemType tipo,
                                     BigDecimal quantidade, PriceUnit unidadePreco, BigDecimal precoUnitario) {
        return new OrcamentoItem(UUID.randomUUID(), itemId, descricao, tipo, quantidade, unidadePreco, precoUnitario);
    }

    public BigDecimal valorTotal() {
        return money(quantidade.multiply(precoUnitario));
    }
}
