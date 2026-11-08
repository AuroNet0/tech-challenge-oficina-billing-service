package com.oficina.billing.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.oficina.billing.application.port.out.*;
import com.oficina.billing.domain.enums.*;
import com.oficina.billing.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static com.oficina.billing.TestFixtures.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = {
        "spring.security.user.name=billing-test", "spring.security.user.password=test-password"
})
class FinancialPersistenceTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("oficina_billing").withUsername("billing").withPassword("test-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private OrcamentoRepositoryPort orcamentos;
    @Autowired private AprovacaoRepositoryPort aprovacoes;
    @Autowired private PagamentoRepositoryPort pagamentos;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    private TransactionTemplate transaction;

    @BeforeEach
    void prepareDatabase() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.execute("TRUNCATE pagamentos_tentativas, pagamentos, aprovacoes, orcamento_itens, orcamentos CASCADE");
    }

    @Test
    void persistsBudgetWithItemsExactTotalsAndOriginalQuantityScale() {
        var original = orcamento();
        var saved = orcamentos.save(original);
        var found = orcamentos.findById(saved.id()).orElseThrow();
        assertThat(found.version()).isZero();
        assertThat(found.osId()).isEqualTo(original.osId());
        assertThat(found.sagaId()).isEqualTo(original.sagaId());
        assertThat(found.createdAt()).isEqualTo(NOW);
        assertThat(found.updatedAt()).isEqualTo(NOW);
        assertThat(found.itens()).hasSize(2);
        assertThat(found.itens().getFirst().quantidade()).isEqualTo(new BigDecimal("1.2500"));
        assertThat(found.itens().getFirst().precoUnitario()).isEqualByComparingTo("80");
        assertThat(found.itens().getFirst().valorTotal()).isEqualByComparingTo("100");
        assertThat(found.valorTotal()).isEqualByComparingTo("150");
        assertThat(orcamentos.findByOsId(found.osId()).orElseThrow().id()).isEqualTo(found.id());
        assertThat(orcamentos.findBySagaId(found.sagaId())).extracting(Orcamento::id).containsExactly(found.id());
        assertThat(orcamentos.existsByOsId(found.osId())).isTrue();
        assertThat(orcamentos.existsByOsId(UUID.randomUUID())).isFalse();
        assertThat(orcamentos.findById(UUID.randomUUID())).isEmpty();
        assertThat(orcamentos.findByOsId(UUID.randomUUID())).isEmpty();
        assertThat(orcamentos.findBySagaId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void persistsApprovedBudgetAndImmutableDecisionAtomically() {
        var budget = approvedBudget();
        var decision = aprovacoes.findByOrcamentoId(budget.id()).orElseThrow();
        assertThat(decision.status()).isEqualTo(AprovacaoStatus.APPROVED);
        assertThat(decision.responsavelId()).isEqualTo("operador-1");
        assertThat(decision.correlationId()).isNotNull();
        assertThat(decision.createdAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(budget.approvedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(budget.rejectedAt()).isNull();
        assertThat(budget.version()).isEqualTo(1);
        assertThatThrownBy(() -> aprovacoes.save(new Aprovacao(UUID.randomUUID(), budget.id(), budget.osId(),
                AprovacaoStatus.APPROVED, budget.approvedAt(), null, null, null, NOW.plusSeconds(2))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE aprovacoes SET motivo = 'alterado' WHERE id = ?", decision.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM aprovacoes WHERE id = ?", decision.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsRejectionWithReasonAndWithoutInventedResponsible() {
        var budget = orcamentos.save(orcamento());
        transaction.executeWithoutResult(status -> {
            budget.rejeitar(NOW.plusSeconds(1));
            orcamentos.save(budget);
            aprovacoes.save(Aprovacao.registrar(budget, null, "Recusado", null, NOW.plusSeconds(1)));
        });
        var found = orcamentos.findById(budget.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(OrcamentoStatus.REJECTED);
        assertThat(found.rejectedAt()).isEqualTo(NOW.plusSeconds(1));
        var decision = aprovacoes.findByOrcamentoId(budget.id()).orElseThrow();
        assertThat(decision.motivo()).isEqualTo("Recusado");
        assertThat(decision.responsavelId()).isNull();
        assertThat(decision.correlationId()).isNull();
    }

    @Test
    void rollsBackDecisionAndBudgetTogetherOnFailure() {
        var budget = orcamentos.save(orcamento());
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            budget.aprovar(NOW.plusSeconds(1));
            orcamentos.save(budget);
            aprovacoes.save(Aprovacao.registrar(budget, null, null, null, NOW.plusSeconds(2)));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(orcamentos.findById(budget.id()).orElseThrow().status()).isEqualTo(OrcamentoStatus.PENDING_APPROVAL);
        assertThat(aprovacoes.findByOrcamentoId(budget.id())).isEmpty();
    }

    @Test
    void requiresConsistentDecisionInSameTransaction() {
        var budget = orcamentos.save(orcamento());
        budget.aprovar(NOW.plusSeconds(1));
        assertThatThrownBy(() -> orcamentos.save(budget)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> aprovacoes.save(Aprovacao.registrar(budget, null, null, null, NOW.plusSeconds(2))))
                .isInstanceOf(RuntimeException.class);
        assertThat(aprovacoes.findByOrcamentoId(budget.id())).isEmpty();
        assertThat(orcamentos.findById(budget.id()).orElseThrow().status()).isEqualTo(OrcamentoStatus.PENDING_APPROVAL);
    }

    @Test
    void rejectsStaleBudgetDecisionThroughOptimisticLocking() {
        var initial = orcamentos.save(orcamento());
        var first = orcamentos.findById(initial.id()).orElseThrow();
        var stale = orcamentos.findById(initial.id()).orElseThrow();
        first.cancelar(NOW.plusSeconds(1));
        orcamentos.save(first);
        stale.aprovar(NOW.plusSeconds(1));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            orcamentos.save(stale);
            aprovacoes.save(Aprovacao.registrar(stale, null, null, null, NOW.plusSeconds(2)));
        })).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(aprovacoes.findByOrcamentoId(initial.id())).isEmpty();
    }

    @Test
    void concurrentApprovalAndRejectionCommitOnlyOneFinalDecision() throws Exception {
        var budget = orcamentos.save(orcamento());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approval = executor.submit(() -> concurrentDecision(budget.id(), true, barrier));
            var rejection = executor.submit(() -> concurrentDecision(budget.id(), false, barrier));
            assertThat(List.of(approval.get(20, TimeUnit.SECONDS), rejection.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        var persisted = orcamentos.findById(budget.id()).orElseThrow();
        var decision = aprovacoes.findByOrcamentoId(budget.id()).orElseThrow();
        assertThat(persisted.status().name()).isEqualTo(decision.status().name());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aprovacoes WHERE orcamento_id = ?",
                Integer.class, budget.id())).isEqualTo(1);
    }

    private boolean concurrentDecision(UUID budgetId, boolean approve, CyclicBarrier barrier) {
        try {
            transaction.executeWithoutResult(status -> {
                var budget = orcamentos.findById(budgetId).orElseThrow();
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                if (approve) {
                    budget.aprovar(NOW.plusSeconds(1));
                } else {
                    budget.rejeitar(NOW.plusSeconds(1));
                }
                orcamentos.save(budget);
                aprovacoes.save(Aprovacao.registrar(budget, null, null, null, NOW.plusSeconds(2)));
            });
            return true;
        } catch (OptimisticLockingFailureException exception) {
            return false;
        }
    }

    @Test
    void enforcesUniqueOsAndItemIdLocalForeignKeysAndChecks() {
        var budget = orcamentos.save(orcamento());
        assertThatThrownBy(() -> orcamentos.save(Orcamento.criar(budget.osId(), UUID.randomUUID(), budget.itens(), NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO orcamento_itens SELECT ?, orcamento_id, 2, item_id, descricao, tipo, quantidade,
                    quantidade_escala, unidade_preco, preco_unitario, valor_total FROM orcamento_itens WHERE id = ?
                """, UUID.randomUUID(), budget.itens().getFirst().id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO orcamento_itens SELECT ?, ?, 0, item_id, descricao, tipo, quantidade,
                    quantidade_escala, unidade_preco, preco_unitario, valor_total FROM orcamento_itens WHERE id = ?
                """, UUID.randomUUID(), UUID.randomUUID(), budget.itens().getFirst().id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE orcamento_itens SET preco_unitario = -1 WHERE orcamento_id = ?", budget.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE orcamentos SET status = 'INVALID' WHERE id = ?", budget.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE orcamentos SET valor_total = 999 WHERE id = ?", budget.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM orcamento_itens WHERE orcamento_id = ?", budget.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preservesPaymentAttemptHistoryAndResolvesAllIdempotencyKeys() {
        var budget = approvedBudget();
        var payment = pagamentos.save(Pagamento.criar(budget, "MERCADO_PAGO", "reference", "first-key", NOW.plusSeconds(2)));
        assertThat(payment.version()).isZero();
        assertThat(payment.osId()).isEqualTo(budget.osId());
        assertThat(payment.sagaId()).isEqualTo(budget.sagaId());
        assertThat(payment.orcamentoId()).isEqualTo(budget.id());
        assertThat(payment.valor()).isEqualByComparingTo(budget.valorTotal());
        payment.processar("first-key", NOW.plusSeconds(3));
        payment = pagamentos.save(payment);
        payment.falhar("first-key", "timeout", NOW.plusSeconds(4));
        payment = pagamentos.save(payment);
        var failed = pagamentos.findById(payment.id()).orElseThrow();
        assertThat(failed.failedAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(failed.failureReason()).isEqualTo("timeout");
        failed.novaTentativa("second-key", NOW.plusSeconds(5));
        failed.novaTentativa("second-key", NOW.plusSeconds(5));
        var retry = pagamentos.save(failed);
        retry.confirmar("second-key", "mp-42", NOW.plusSeconds(6));
        var confirmed = pagamentos.save(retry);
        var restored = pagamentos.findById(confirmed.id()).orElseThrow();
        restored.confirmar("second-key", "mp-42", NOW.plusSeconds(7));
        pagamentos.save(restored);
        assertThat(restored.confirmedAt()).isEqualTo(NOW.plusSeconds(6));
        assertThat(restored.providerPaymentId()).isEqualTo("mp-42");
        assertThat(restored.failedAt()).isNull();
        assertThat(restored.tentativas()).hasSize(2);
        assertThat(restored.tentativas().getFirst().failureReason()).isEqualTo("timeout");
        assertThat(restored.tentativas().getFirst().failedAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(restored.tentativas().getFirst().updatedAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(pagamentos.findByIdempotencyKey("first-key").orElseThrow().id()).isEqualTo(restored.id());
        assertThat(pagamentos.findByIdempotencyKey("second-key").orElseThrow().id()).isEqualTo(restored.id());
        assertThat(pagamentos.findByOsId(budget.osId())).hasSize(1);
        assertThat(pagamentos.findBySagaId(budget.sagaId())).hasSize(1);
        assertThat(pagamentos.findByOsId(UUID.randomUUID())).isEmpty();
        assertThat(pagamentos.findBySagaId(UUID.randomUUID())).isEmpty();
        assertThat(pagamentos.findById(UUID.randomUUID())).isEmpty();
        assertThat(pagamentos.findByIdempotencyKey("unknown")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamentos_tentativas", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE pagamentos_tentativas SET failure_reason = 'alterado' WHERE id = ?",
                restored.tentativas().getFirst().id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM pagamentos_tentativas WHERE id = ?",
                restored.tentativas().getFirst().id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE pagamentos SET status = 'FAILED' WHERE id = ?", restored.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesGlobalAttemptKeysAndSingleLogicalPaymentPerBudget() {
        var budget = approvedBudget();
        var first = pagamentos.save(Pagamento.criar(budget, "MP", "ref-1", "same-key", NOW.plusSeconds(2)));
        assertThatThrownBy(() -> pagamentos.save(Pagamento.criar(budget, "MP", "ref-2", "other-key", NOW.plusSeconds(2))))
                .isInstanceOf(DataIntegrityViolationException.class);
        var otherBudget = approvedBudget();
        assertThatThrownBy(() -> pagamentos.save(Pagamento.criar(otherBudget, "MP", "ref-2", "same-key", NOW.plusSeconds(2))))
                .isInstanceOf(DataIntegrityViolationException.class);
        first.falhar("same-key", "timeout", NOW.plusSeconds(3));
        var failed = pagamentos.save(first);
        failed.novaTentativa("new-key", NOW.plusSeconds(4));
        pagamentos.save(failed);
        assertThatThrownBy(() -> pagamentos.save(Pagamento.criar(otherBudget, "MP", "ref-2", "same-key", NOW.plusSeconds(5))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsStalePaymentUpdates() {
        var budget = approvedBudget();
        var initial = pagamentos.save(Pagamento.criar(budget, "MP", "ref", "key", NOW.plusSeconds(2)));
        var first = pagamentos.findById(initial.id()).orElseThrow();
        var stale = pagamentos.findById(initial.id()).orElseThrow();
        first.processar("key", NOW.plusSeconds(3));
        pagamentos.save(first);
        stale.falhar("key", "timeout", NOW.plusSeconds(3));
        assertThatThrownBy(() -> pagamentos.save(stale)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(pagamentos.findById(initial.id()).orElseThrow().status()).isEqualTo(PagamentoStatus.PROCESSING);
    }

    @Test
    void databaseRejectsChargesForUnapprovedBudgetOrWrongAmountAndIdentity() {
        var budget = approvedBudget();
        var original = Pagamento.criar(budget, "MP", "ref", "key", NOW.plusSeconds(2));
        var pending = orcamentos.save(orcamento());
        assertThatThrownBy(() -> pagamentos.save(Pagamento.restaurar(original.id(), pending.osId(), pending.sagaId(),
                pending.id(), original.valor(), "BRL", original.provider(), original.externalReference(),
                original.createdAt(), null, original.tentativas()))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> pagamentos.save(Pagamento.restaurar(original.id(), original.osId(), original.sagaId(),
                original.orcamentoId(), BigDecimal.ONE, "BRL", original.provider(), original.externalReference(),
                original.createdAt(), null, original.tentativas()))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> pagamentos.save(Pagamento.restaurar(original.id(), UUID.randomUUID(), original.sagaId(),
                original.orcamentoId(), original.valor(), "BRL", original.provider(), original.externalReference(),
                original.createdAt(), null, original.tentativas()))).isInstanceOf(DataIntegrityViolationException.class);
        var saved = pagamentos.save(original);
        assertThatThrownBy(() -> jdbc.update("UPDATE pagamentos SET moeda = 'USD' WHERE id = ?", saved.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE pagamentos_tentativas SET status = 'INVALID' WHERE pagamento_id = ?", saved.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Orcamento approvedBudget() {
        var pending = orcamentos.save(orcamento());
        return transaction.execute(status -> {
            pending.aprovar(NOW.plusSeconds(1));
            var approved = orcamentos.save(pending);
            aprovacoes.save(Aprovacao.registrar(approved, "operador-1", null, UUID.randomUUID(), NOW.plusSeconds(2)));
            return approved;
        });
    }
}
