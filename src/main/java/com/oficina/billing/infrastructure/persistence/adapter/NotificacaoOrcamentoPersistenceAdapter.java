package com.oficina.billing.infrastructure.persistence.adapter;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.billing.application.port.out.NotificacaoOrcamentoRepositoryPort;
import com.oficina.billing.domain.enums.OrcamentoStatus;
import com.oficina.billing.domain.model.NotificacaoOrcamento;
import com.oficina.billing.domain.model.Orcamento;

@Repository
@Transactional
public class NotificacaoOrcamentoPersistenceAdapter implements NotificacaoOrcamentoRepositoryPort {
    private final JdbcTemplate jdbc;

    public NotificacaoOrcamentoPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void registrar(Orcamento orcamento) {
        jdbc.update("""
                INSERT INTO notificacoes_orcamento (id, orcamento_id, os_id, tipo, created_at)
                SELECT ?, id, os_id, 'ORCAMENTO_DISPONIVEL', created_at FROM orcamentos
                WHERE id = ? AND status = 'PENDING_APPROVAL'
                ON CONFLICT (orcamento_id) DO NOTHING
                """, UUID.randomUUID(), orcamento.id());
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificacaoOrcamento> findByOsId(UUID osId, int page, int size) {
        return jdbc.query("""
                SELECT notification.id, notification.orcamento_id, notification.os_id,
                       notification.tipo, notification.created_at, quote.status
                FROM notificacoes_orcamento notification
                JOIN orcamentos quote ON quote.id = notification.orcamento_id AND quote.os_id = notification.os_id
                WHERE notification.os_id = ?
                ORDER BY notification.created_at DESC, notification.id DESC LIMIT ? OFFSET ?
                """, (result, rowNumber) -> new NotificacaoOrcamento(result.getObject("id", UUID.class),
                result.getObject("orcamento_id", UUID.class), result.getObject("os_id", UUID.class),
                result.getString("tipo"), result.getTimestamp("created_at").toInstant(),
                OrcamentoStatus.valueOf(result.getString("status"))), osId, size, (long) page * size);
    }
}
