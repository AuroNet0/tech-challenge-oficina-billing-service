package com.oficina.billing.application.port.in;

import java.util.List;
import java.util.UUID;
import com.oficina.billing.domain.model.NotificacaoOrcamento;

public interface ConsultarNotificacoesPort {
    List<NotificacaoOrcamento> porOsId(UUID osId, int page, int size);
}
