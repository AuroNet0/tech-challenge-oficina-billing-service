package com.oficina.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.oficina.billing.domain.enums.*;
import com.oficina.billing.domain.exception.DomainException;
import com.oficina.billing.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.oficina.billing.TestFixtures.*;
import static org.assertj.core.api.Assertions.*;

class FinancialDomainTests {
    @Test
    void calculatesExactBrlTotalsAndPreservesQuantity() {
        var item = item("1", "1.2500", "80.000000");
        assertThat(item.quantidade()).isEqualTo(new BigDecimal("1.2500"));
        assertThat(item.precoUnitario()).isEqualTo(new BigDecimal("80.000000"));
        assertThat(item.valorTotal()).isEqualTo(new BigDecimal("100.00"));
        assertThat(orcamento().valorTotal()).isEqualTo(new BigDecimal("150.00"));
        assertThat(item("zero", "1.000001", "0").valorTotal()).isEqualTo(new BigDecimal("0.00"));
        assertThat(item("subcent", "100", "0.000100").valorTotal()).isEqualTo(new BigDecimal("0.01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.0000001", "1.0000000", "10000000000000", "1E+2"})
    void rejectsInvalidQuantities(String quantity) {
        assertThatThrownBy(() -> item("1", quantity, "1.00")).isInstanceOf(DomainException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "0.0000001", "10000000000000", "0.001"})
    void rejectsInvalidPricesOrFractionalCentTotals(String price) {
        assertThatThrownBy(() -> item("1", "1", price)).isInstanceOf(DomainException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void requiresExternalItemIdAndDescription(String value) {
        assertThatThrownBy(() -> item(value, "1", "1")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> OrcamentoItem.criar("1", value, OrcamentoItemType.PECA,
                BigDecimal.ONE, PriceUnit.UNIDADE, BigDecimal.ZERO)).isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsMissingItemDataAndExcessiveText() {
        assertThatThrownBy(() -> item("x".repeat(101), "1", "1")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> OrcamentoItem.criar("1", "x".repeat(501), OrcamentoItemType.PECA,
                BigDecimal.ONE, PriceUnit.KIT, BigDecimal.ONE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> OrcamentoItem.criar("1", "item", null, BigDecimal.ONE,
                PriceUnit.KIT, BigDecimal.ONE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> OrcamentoItem.criar("1", "item", OrcamentoItemType.MATERIAL,
                null, PriceUnit.KIT, BigDecimal.ONE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> OrcamentoItem.criar("1", "item", OrcamentoItemType.MATERIAL,
                BigDecimal.ONE, null, BigDecimal.ONE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> OrcamentoItem.criar("1", "item", OrcamentoItemType.MATERIAL,
                BigDecimal.ONE, PriceUnit.KIT, null)).isInstanceOf(DomainException.class);
    }

    @Test
    void acceptsAllInitialItemTypesAndPriceUnits() {
        for (var tipo : OrcamentoItemType.values()) {
            for (var unidade : PriceUnit.values()) {
                var item = OrcamentoItem.criar("1", "item", tipo, BigDecimal.ONE, unidade, BigDecimal.ONE);
                assertThat(item.tipo()).isEqualTo(tipo);
                assertThat(item.unidadePreco()).isEqualTo(unidade);
            }
        }
    }

    @Test
    void requiresValidBudgetIdentityAndItems() {
        var valid = orcamento();
        assertThatThrownBy(() -> Orcamento.criar(null, valid.sagaId(), valid.itens(), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Orcamento.criar(valid.osId(), null, valid.itens(), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Orcamento.criar(valid.osId(), valid.sagaId(), null, NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Orcamento.criar(valid.osId(), valid.sagaId(), List.of(), NOW)).isInstanceOf(DomainException.class);
        var invalid = new ArrayList<OrcamentoItem>();
        invalid.add(null);
        assertThatThrownBy(() -> Orcamento.criar(valid.osId(), valid.sagaId(), invalid, NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Orcamento.criar(valid.osId(), valid.sagaId(), valid.itens(), null)).isInstanceOf(DomainException.class);
    }

    @Test
    void preventsDuplicateItemsAndTotalOverflow() {
        assertThatThrownBy(() -> Orcamento.criar(UUID.randomUUID(), UUID.randomUUID(),
                List.of(item("same", "1", "1"), item("same", "2", "1")), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Orcamento.criar(UUID.randomUUID(), UUID.randomUUID(),
                List.of(item("1", "10000", "9999999999999.00"), item("2", "10000", "9999999999999.00")), NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void protectsFinancialItemsFromExternalMutation() {
        var items = new ArrayList<>(List.of(item("1", "1", "12")));
        var budget = Orcamento.criar(UUID.randomUUID(), UUID.randomUUID(), items, NOW);
        items.clear();
        assertThat(budget.itens()).hasSize(1);
        assertThatThrownBy(() -> budget.itens().clear()).isInstanceOf(UnsupportedOperationException.class);
        budget.aprovar(NOW.plusSeconds(1));
        assertThat(budget.valorTotal()).isEqualByComparingTo("12");
    }

    @Test
    void approvesAndCreatesImmutableAuditableDecision() {
        var budget = orcamento();
        assertThat(budget.status()).isEqualTo(OrcamentoStatus.PENDING_APPROVAL);
        assertThat(budget.version()).isNull();
        var correlation = UUID.randomUUID();
        budget.aprovar(NOW.plusSeconds(1));
        var decision = Aprovacao.registrar(budget, "operador-1", null, correlation, NOW.plusSeconds(2));
        assertThat(decision.orcamentoId()).isEqualTo(budget.id());
        assertThat(decision.osId()).isEqualTo(budget.osId());
        assertThat(decision.status()).isEqualTo(AprovacaoStatus.APPROVED);
        assertThat(decision.responsavelId()).isEqualTo("operador-1");
        assertThat(decision.correlationId()).isEqualTo(correlation);
        assertThat(decision.decisionAt()).isEqualTo(budget.approvedAt());
        assertThat(decision.createdAt()).isEqualTo(NOW.plusSeconds(2));
        assertTerminal(budget);
    }

    @Test
    void rejectsWithOptionalReasonAndWithoutFictitiousIdentity() {
        var budget = orcamento();
        budget.rejeitar(NOW.plusSeconds(1));
        var decision = Aprovacao.registrar(budget, null, "Cliente recusou", null, NOW.plusSeconds(1));
        assertThat(decision.status()).isEqualTo(AprovacaoStatus.REJECTED);
        assertThat(decision.motivo()).isEqualTo("Cliente recusou");
        assertThat(decision.responsavelId()).isNull();
        assertThat(decision.decisionAt()).isEqualTo(budget.rejectedAt());
        assertTerminal(budget);
    }

    @Test
    void cancelsAndRejectsDecisionsWithoutAValidTimeOrState() {
        var budget = orcamento();
        assertThatThrownBy(() -> budget.aprovar(NOW.minusSeconds(1))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> budget.aprovar(null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Aprovacao.registrar(budget, null, null, null, NOW)).isInstanceOf(DomainException.class);
        budget.cancelar(NOW);
        assertTerminal(budget);
        assertThatThrownBy(() -> Aprovacao.registrar(budget, null, null, null, NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Aprovacao.registrar(aprovado(), " ", null, null, NOW.plusSeconds(2)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Aprovacao.registrar(aprovado(), null, null, null, NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void createsPaymentOnlyFromApprovedBudgetAndCopiesApprovedAmount() {
        var payment = pagamento();
        assertThat(payment.valor()).isEqualTo(new BigDecimal("150.00"));
        assertThat(payment.moeda()).isEqualTo("BRL");
        assertThat(payment.provider()).isEqualTo("MERCADO_PAGO");
        assertThat(payment.status()).isEqualTo(PagamentoStatus.PENDING);
        assertThat(payment.tentativas()).hasSize(1);
        assertThat(payment.providerPaymentId()).isNull();
        assertThat(payment.version()).isNull();
        var pending = orcamento();
        assertThatThrownBy(() -> Pagamento.criar(pending, "MP", "ref", "key", NOW)).isInstanceOf(DomainException.class);
        pending.rejeitar(NOW);
        assertThatThrownBy(() -> Pagamento.criar(pending, "MP", "ref", "key", NOW)).isInstanceOf(DomainException.class);
        var cancelled = orcamento();
        cancelled.cancelar(NOW);
        assertThatThrownBy(() -> Pagamento.criar(cancelled, "MP", "ref", "key", NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Pagamento.criar(aprovado(), "MP", "ref", "key", NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void confirmsIdempotentlyAndRejectsConflictingOrLateChanges() {
        var payment = pagamento();
        payment.processar(payment.idempotencyKey(), NOW.plusSeconds(3));
        assertThat(payment.status()).isEqualTo(PagamentoStatus.PROCESSING);
        assertThatThrownBy(() -> payment.processar(payment.idempotencyKey(), NOW.plusSeconds(3)))
                .isInstanceOf(DomainException.class);
        payment.confirmar(payment.idempotencyKey(), "mp-123", NOW.plusSeconds(4));
        payment.confirmar(payment.idempotencyKey(), "mp-123", NOW.plusSeconds(5));
        assertThat(payment.confirmedAt()).isEqualTo(NOW.plusSeconds(4));
        assertThat(payment.providerPaymentId()).isEqualTo("mp-123");
        assertThat(payment.failedAt()).isNull();
        assertThatThrownBy(() -> payment.confirmar(payment.idempotencyKey(), "other", NOW.plusSeconds(5)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payment.falhar(payment.idempotencyKey(), "reason", NOW.plusSeconds(5)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payment.novaTentativa("new-key", NOW.plusSeconds(5))).isInstanceOf(DomainException.class);
        payment.novaTentativa(payment.idempotencyKey(), NOW.plusSeconds(5));
        assertThat(payment.tentativas()).hasSize(1);
    }

    @Test
    void retriesExplicitlyWithoutOverwritingFailedAttempt() {
        var payment = pagamento();
        var firstKey = payment.idempotencyKey();
        assertThatThrownBy(() -> payment.novaTentativa("retry", NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
        payment.falhar(firstKey, "timeout", NOW.plusSeconds(3));
        payment.falhar(firstKey, "timeout", NOW.plusSeconds(4));
        assertThat(payment.failedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(payment.failureReason()).isEqualTo("timeout");
        assertThatThrownBy(() -> payment.falhar(firstKey, "different", NOW.plusSeconds(4))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payment.confirmar(firstKey, "mp", NOW.plusSeconds(4))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payment.novaTentativa("retry", NOW)).isInstanceOf(DomainException.class);
        payment.novaTentativa("retry", NOW.plusSeconds(4));
        payment.novaTentativa("retry", NOW.plusSeconds(5));
        payment.novaTentativa(firstKey, NOW.plusSeconds(5));
        assertThat(payment.tentativas()).hasSize(2);
        assertThat(payment.tentativas().getFirst().status()).isEqualTo(PagamentoStatus.FAILED);
        assertThat(payment.tentativas().getFirst().failureReason()).isEqualTo("timeout");
        assertThat(payment.status()).isEqualTo(PagamentoStatus.PENDING);
        assertThat(payment.failureReason()).isNull();
        assertThatThrownBy(() -> payment.confirmar(firstKey, "late", NOW.plusSeconds(5))).isInstanceOf(DomainException.class);
        payment.confirmar("retry", "mp-retry", NOW.plusSeconds(6));
        assertThatThrownBy(() -> payment.tentativas().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidPaymentInputsAndRestoredState() {
        var budget = aprovado();
        assertThatThrownBy(() -> Pagamento.criar(budget, null, "ref", "key", NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Pagamento.criar(budget, "MP", " ", "key", NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Pagamento.criar(budget, "MP", "ref", " ", NOW.plusSeconds(2))).isInstanceOf(DomainException.class);
        var payment = pagamento();
        assertThatThrownBy(() -> payment.confirmar("wrong", "mp", NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payment.confirmar(payment.idempotencyKey(), " ", NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payment.falhar(payment.idempotencyKey(), " ", NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> payment.processar(payment.idempotencyKey(), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> restorePayment(payment, "USD", BigDecimal.ONE, payment.tentativas())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> restorePayment(payment, "BRL", new BigDecimal("-1"), payment.tentativas())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> restorePayment(payment, "BRL", payment.valor(), List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> restorePayment(payment, "BRL", payment.valor(),
                List.of(payment.tentativas().getFirst(), payment.tentativas().getFirst()))).isInstanceOf(DomainException.class);
    }

    private Pagamento restorePayment(Pagamento payment, String currency, BigDecimal amount, List<PagamentoTentativa> attempts) {
        return Pagamento.restaurar(payment.id(), payment.osId(), payment.sagaId(), payment.orcamentoId(),
                amount, currency, payment.provider(), payment.externalReference(), payment.createdAt(), 0L, attempts);
    }

    private void assertTerminal(Orcamento budget) {
        assertThatThrownBy(() -> budget.aprovar(NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> budget.rejeitar(NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> budget.cancelar(NOW.plusSeconds(3))).isInstanceOf(DomainException.class);
    }
}
