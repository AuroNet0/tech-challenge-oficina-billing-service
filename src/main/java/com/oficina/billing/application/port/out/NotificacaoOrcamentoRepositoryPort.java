package com.oficina.billing.application.port.out;

import java.util.List;
import java.util.UUID;
import com.oficina.billing.domain.model.NotificacaoOrcamento;
import com.oficina.billing.domain.model.Orcamento;

public interface NotificacaoOrcamentoRepositoryPort {
    void registrar(Orcamento orcamento);
    List<NotificacaoOrcamento> findByOsId(UUID osId, int page, int size);
}
