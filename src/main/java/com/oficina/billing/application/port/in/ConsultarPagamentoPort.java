package com.oficina.billing.application.port.in;

import java.util.List;
import java.util.UUID;
import com.oficina.billing.domain.model.Pagamento;

public interface ConsultarPagamentoPort {
    Pagamento porId(UUID id);
    List<Pagamento> porOsId(UUID osId);
}
