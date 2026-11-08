package com.oficina.billing.application.port.in;

import java.util.UUID;
import com.oficina.billing.domain.model.Aprovacao;
import com.oficina.billing.domain.model.Orcamento;

public interface ConsultarOrcamentoPort {
    Orcamento porId(UUID id);
    Orcamento porOsId(UUID osId);
    Aprovacao aprovacao(UUID orcamentoId);
}
