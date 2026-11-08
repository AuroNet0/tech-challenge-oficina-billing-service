package com.oficina.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.oficina.billing.domain.enums.*;
import com.oficina.billing.domain.model.*;

public final class TestFixtures {
    public static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private TestFixtures() {
    }

    public static OrcamentoItem item(String itemId, String quantidade, String preco) {
        return OrcamentoItem.criar(itemId, "Diagnostico tecnico", OrcamentoItemType.SERVICO,
                new BigDecimal(quantidade), PriceUnit.HORA, new BigDecimal(preco));
    }

    public static Orcamento orcamento() {
        return Orcamento.criar(UUID.randomUUID(), UUID.randomUUID(),
                List.of(item("servico", "1.2500", "80.00"), item("peca", "2", "25.00")), NOW);
    }

    public static Orcamento aprovado() {
        var orcamento = orcamento();
        orcamento.aprovar(NOW.plusSeconds(1));
        return orcamento;
    }

    public static Pagamento pagamento() {
        return Pagamento.criar(aprovado(), "MERCADO_PAGO", UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), NOW.plusSeconds(2));
    }
}
