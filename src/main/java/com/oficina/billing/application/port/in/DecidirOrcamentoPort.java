package com.oficina.billing.application.port.in;

import java.util.UUID;
import com.oficina.billing.domain.model.Aprovacao;

public interface DecidirOrcamentoPort {
    Aprovacao aprovar(DecisaoCommand command);
    Aprovacao rejeitar(DecisaoCommand command);

    record DecisaoCommand(UUID orcamentoId, String responsavelId, String motivo, UUID correlationId) {
    }
}
