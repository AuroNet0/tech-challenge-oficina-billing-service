package com.oficina.billing.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.oficina.billing.application.exception.ResourceNotFoundException;
import com.oficina.billing.application.port.in.DecidirOrcamentoPort.DecisaoCommand;
import com.oficina.billing.application.port.out.*;
import com.oficina.billing.application.usecase.*;
import com.oficina.billing.domain.enums.AprovacaoStatus;
import com.oficina.billing.domain.exception.DomainException;
import com.oficina.billing.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import static com.oficina.billing.TestFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialUseCaseTests {
    private static final Instant OPERATION_TIME = NOW.plusSeconds(10).plusNanos(123456789);
    @Mock private OrcamentoRepositoryPort orcamentos;
    @Mock private AprovacaoRepositoryPort aprovacoes;
    @Mock private PagamentoRepositoryPort pagamentos;
    private ConsultarOrcamentoUseCase consultas;
    private ConsultarPagamentoUseCase consultasPagamento;
    private DecidirOrcamentoUseCase decisoes;

    @BeforeEach
    void setUp() {
        consultas = new ConsultarOrcamentoUseCase(orcamentos, aprovacoes);
        consultasPagamento = new ConsultarPagamentoUseCase(pagamentos);
        decisoes = new DecidirOrcamentoUseCase(orcamentos, aprovacoes,
                Clock.fixed(OPERATION_TIME, ZoneOffset.UTC));
    }

    @Test
    void consultsBudgetByIdAndOsPreservingExactValues() {
        var budget = orcamento();
        when(orcamentos.findById(budget.id())).thenReturn(Optional.of(budget));
        when(orcamentos.findByOsId(budget.osId())).thenReturn(Optional.of(budget));
        assertThat(consultas.porId(budget.id())).isSameAs(budget);
        assertThat(consultas.porOsId(budget.osId()).valorTotal().toPlainString()).isEqualTo("150.00");
        assertThat(budget.itens().getFirst().quantidade().toPlainString()).isEqualTo("1.2500");
    }

    @Test
    void reportsMissingBudgetByIdOsAndDecision() {
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> consultas.porId(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> consultas.porOsId(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> consultas.aprovacao(id)).isInstanceOf(ResourceNotFoundException.class);
        var budget = orcamento();
        when(orcamentos.findById(budget.id())).thenReturn(Optional.of(budget));
        assertThatThrownBy(() -> consultas.aprovacao(budget.id())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void consultsOriginalPersistedDecision() {
        var budget = aprovado();
        var decision = Aprovacao.registrar(budget, "informado", null, UUID.randomUUID(), NOW.plusSeconds(2));
        when(orcamentos.findById(budget.id())).thenReturn(Optional.of(budget));
        when(aprovacoes.findByOrcamentoId(budget.id())).thenReturn(Optional.of(decision));
        assertThat(consultas.aprovacao(budget.id())).isSameAs(decision);
        verify(aprovacoes, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void persistsDecisionWithOneTimestampAndCorrelation(boolean approve) {
        var budget = orcamento();
        var items = budget.itens();
        var correlation = UUID.randomUUID();
        when(orcamentos.findById(budget.id())).thenReturn(Optional.of(budget));
        when(aprovacoes.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var command = new DecisaoCommand(budget.id(), "id-informado", "Motivo informado", correlation);
        var decision = approve ? decisoes.aprovar(command) : decisoes.rejeitar(command);
        assertThat(decision.status()).isEqualTo(approve ? AprovacaoStatus.APPROVED : AprovacaoStatus.REJECTED);
        assertThat(decision.correlationId()).isEqualTo(correlation);
        assertThat(decision.responsavelId()).isEqualTo("id-informado");
        assertThat(decision.motivo()).isEqualTo(approve ? null : "Motivo informado");
        assertThat(decision.decisionAt()).isEqualTo(OPERATION_TIME.truncatedTo(ChronoUnit.MICROS));
        assertThat(decision.createdAt()).isEqualTo(decision.decisionAt()).isEqualTo(budget.updatedAt());
        assertThat(budget.itens()).isEqualTo(items);
        assertThat(budget.valorTotal().toPlainString()).isEqualTo("150.00");
        var order = inOrder(orcamentos, aprovacoes);
        order.verify(orcamentos).save(budget);
        order.verify(aprovacoes).save(decision);
    }

    @ParameterizedTest
    @EnumSource(AprovacaoStatus.class)
    void repeatedAndOppositeDecisionsNeverCreateAnotherRecord(AprovacaoStatus status) {
        var budget = orcamento();
        if (status == AprovacaoStatus.APPROVED) {
            budget.aprovar(NOW.plusSeconds(1));
        } else {
            budget.rejeitar(NOW.plusSeconds(1));
        }
        var original = Aprovacao.registrar(budget, null, null, null, NOW.plusSeconds(1));
        when(orcamentos.findById(budget.id())).thenReturn(Optional.of(budget));
        when(aprovacoes.findByOrcamentoId(budget.id())).thenReturn(Optional.of(original));
        var command = new DecisaoCommand(budget.id(), "outro", "outro motivo", UUID.randomUUID());
        assertThatThrownBy(() -> decisoes.aprovar(command)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> decisoes.rejeitar(command)).isInstanceOf(DomainException.class);
        assertThat(budget.updatedAt()).isEqualTo(original.decisionAt());
        verify(orcamentos, never()).save(any());
        verify(aprovacoes, never()).save(any());
    }

    @Test
    void refusesCancelledBudgetAndMissingBudget() {
        var missing = new DecisaoCommand(UUID.randomUUID(), null, null, null);
        assertThatThrownBy(() -> decisoes.aprovar(missing)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> decisoes.rejeitar(missing)).isInstanceOf(ResourceNotFoundException.class);
        var budget = orcamento();
        budget.cancelar(NOW);
        when(orcamentos.findById(budget.id())).thenReturn(Optional.of(budget));
        var command = new DecisaoCommand(budget.id(), null, null, null);
        assertThatThrownBy(() -> decisoes.aprovar(command)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> decisoes.rejeitar(command)).isInstanceOf(DomainException.class);
        verify(aprovacoes, never()).save(any());
    }

    @Test
    void propagatesOptimisticConflictWithoutSavingDecision() {
        var budget = orcamento();
        when(orcamentos.findById(budget.id())).thenReturn(Optional.of(budget));
        doThrow(new OptimisticLockingFailureException("concurrent update")).when(orcamentos).save(any());
        assertThatThrownBy(() -> decisoes.aprovar(new DecisaoCommand(budget.id(), null, null, null)))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(aprovacoes, never()).save(any());
    }

    @Test
    void permitsOptionalDecisionMetadata() {
        var budget = orcamento();
        when(orcamentos.findById(budget.id())).thenReturn(Optional.of(budget));
        when(aprovacoes.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var decision = decisoes.rejeitar(new DecisaoCommand(budget.id(), null, null, null));
        assertThat(decision.responsavelId()).isNull();
        assertThat(decision.motivo()).isNull();
        assertThat(decision.correlationId()).isNull();
    }

    @Test
    void consultsPaymentAndItsHistoryWithoutChangingIt() {
        var payment = pagamento();
        var key = payment.idempotencyKey();
        payment.falhar(key, "timeout", NOW.plusSeconds(3));
        payment.novaTentativa("retry", NOW.plusSeconds(4));
        when(pagamentos.findById(payment.id())).thenReturn(Optional.of(payment));
        when(pagamentos.findByOsId(payment.osId())).thenReturn(List.of(payment));
        assertThat(consultasPagamento.porId(payment.id()).tentativas()).hasSize(2);
        assertThat(consultasPagamento.porOsId(payment.osId())).containsExactly(payment);
        verify(pagamentos, never()).save(any());
    }

    @Test
    void reportsMissingPaymentAndEmptyOsHistory() {
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> consultasPagamento.porId(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(consultasPagamento.porOsId(id)).isEmpty();
    }
}
