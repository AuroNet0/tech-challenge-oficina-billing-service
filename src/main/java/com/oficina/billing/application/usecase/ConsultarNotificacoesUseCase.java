package com.oficina.billing.application.usecase;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.billing.application.port.in.ConsultarNotificacoesPort;
import com.oficina.billing.application.port.out.NotificacaoOrcamentoRepositoryPort;
import com.oficina.billing.domain.model.NotificacaoOrcamento;

@Service
@Transactional(readOnly = true)
public class ConsultarNotificacoesUseCase implements ConsultarNotificacoesPort {
    private final NotificacaoOrcamentoRepositoryPort notificacoes;

    public ConsultarNotificacoesUseCase(NotificacaoOrcamentoRepositoryPort notificacoes) {
        this.notificacoes = notificacoes;
    }

    @Override
    public List<NotificacaoOrcamento> porOsId(UUID osId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Paginacao invalida");
        return notificacoes.findByOsId(osId, page, size);
    }
}
