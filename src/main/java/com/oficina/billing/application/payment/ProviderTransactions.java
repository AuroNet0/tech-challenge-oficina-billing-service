package com.oficina.billing.application.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.billing.application.exception.ResourceNotFoundException;
import com.oficina.billing.application.port.in.PaymentNotificationPort;
import com.oficina.billing.application.port.out.*;
import com.oficina.billing.application.payment.ProviderData.*;
import com.oficina.billing.application.usecase.EventRecorder;
import com.oficina.billing.domain.enums.*;
import com.oficina.billing.domain.model.*;
import static com.oficina.billing.domain.model.FinancialRules.require;

@Service
@Transactional
public class ProviderTransactions implements PaymentNotificationPort {
    private final PagamentoRepositoryPort payments;
    private final OrcamentoRepositoryPort quotes;
    private final ProviderWorkflowPort workflow;
    private final EventRecorder events;
    private final PaymentIntegrationPolicy policy;
    private final Clock clock;

    public ProviderTransactions(PagamentoRepositoryPort payments, OrcamentoRepositoryPort quotes,
                                ProviderWorkflowPort workflow, EventRecorder events, PaymentIntegrationPolicy policy, Clock clock) {
        this.payments = payments;
        this.quotes = quotes;
        this.workflow = workflow;
        this.events = events;
        this.policy = policy;
        this.clock = clock;
    }

    public Lease reserveCheckout(UUID paymentId) {
        var payment = payment(paymentId);
        require(payment.status() == PagamentoStatus.PENDING || payment.status() == PagamentoStatus.PROCESSING,
                "Checkout exige pagamento elegivel");
        var quote = quotes.findById(payment.orcamentoId()).orElseThrow(ResourceNotFoundException::new);
        require(quote.status() == OrcamentoStatus.APPROVED && quote.valorTotal().compareTo(payment.valor()) == 0
                && quote.osId().equals(payment.osId()) && quote.sagaId().equals(payment.sagaId()), "Orcamento inconsistente");
        UUID token = workflow.claim(paymentId, now(), policy.leaseDuration());
        if (token == null) throw new ConcurrencyFailureException("Checkout em processamento");
        require(workflow.reviewReason(paymentId) == null, "Pagamento exige conciliacao manual");
        var attempt = payment.tentativas().getLast();
        Checkout checkout = workflow.checkout(paymentId, attempt.id(), attempt.idempotencyKey(), now());
        boolean create = checkout.state().equals("NEW");
        if (create || checkout.state().equals("CREATING")) {
            checkout = new Checkout(paymentId, attempt.id(), attempt.idempotencyKey(),
                    create ? "CREATING" : "UNKNOWN", checkout.preferenceId(), checkout.checkoutUrl());
            workflow.saveCheckout(checkout);
        }
        return new Lease(paymentId, token, request(payment, attempt), checkout, create);
    }

    public Lease reserveSync(UUID paymentId) {
        UUID token = workflow.claim(paymentId, now(), policy.leaseDuration());
        if (token == null) return null;
        var payment = payment(paymentId);
        var attempt = payment.tentativas().stream().filter(value -> value.idempotencyKey().equals(payment.idempotencyKey()))
                .findFirst().orElseThrow();
        return new Lease(paymentId, token, request(payment, attempt), null, false);
    }

    public Lease reserveDue() {
        UUID id = workflow.nextDue(now());
        return id == null ? null : reserveSync(id);
    }

    public List<Checkout> checkouts(Lease lease) {
        workflow.lock(lease.paymentId(), lease.token(), now());
        return workflow.checkouts(lease.paymentId());
    }

    public void storePreference(Lease lease, Checkout checkout, Preference preference) {
        workflow.lock(lease.paymentId(), lease.token(), now());
        require(preference.reference().equals(lease.request().reference())
                && preference.amount().compareTo(lease.request().amount()) == 0 && "BRL".equals(preference.currency())
                && checkout.attemptId().equals(preference.attemptId()), "Preferencia externa divergente");
        workflow.saveCheckout(new Checkout(checkout.paymentId(), checkout.attemptId(), checkout.key(), "READY",
                preference.id(), preference.checkoutUrl()));
    }

    public void unknown(Lease lease) {
        workflow.lock(lease.paymentId(), lease.token(), now());
        Checkout checkout = lease.checkout();
        workflow.saveCheckout(new Checkout(checkout.paymentId(), checkout.attemptId(), checkout.key(), "UNKNOWN", null, null));
    }

    public void close(Lease lease, Checkout checkout) {
        workflow.lock(lease.paymentId(), lease.token(), now());
        workflow.saveCheckout(new Checkout(checkout.paymentId(), checkout.attemptId(), checkout.key(), "CLOSED",
                checkout.preferenceId(), checkout.checkoutUrl()));
    }

    public void finish(Lease lease, String error) {
        workflow.release(lease.paymentId(), lease.token(), now(), error == null ? policy.syncInterval() : Duration.ofMinutes(2), error);
    }

    @Override
    public void receive(String id, String resourceId, String requestId) {
        workflow.receive(id, resourceId, requestId, now());
    }

    public Notification notification() { return workflow.claimNotification(now(), policy.leaseDuration()); }

    public UUID findPayment(String reference) { return workflow.findPayment(reference); }

    public void notificationResult(Notification notification, String status, String error) {
        workflow.finishNotification(notification, status, now(), Duration.ofMinutes(2), error);
    }

    public void review(Lease lease, String reason) {
        workflow.lock(lease.paymentId(), lease.token(), now());
        workflow.review(lease.paymentId(), reason);
    }

    public boolean apply(Lease lease, List<ExternalPayment> received, Notification notification) {
        Instant instant = now();
        workflow.lock(lease.paymentId(), lease.token(), instant);
        Pagamento payment = payment(lease.paymentId());
        var checkouts = workflow.checkouts(payment.id());
        boolean review = workflow.reviewReason(payment.id()) != null;
        for (ExternalPayment external : received) {
            var matches = checkouts.stream().filter(checkout ->
                    (external.preferenceId() != null && external.preferenceId().equals(checkout.preferenceId())
                            || external.preferenceId() == null && external.attemptId() != null
                            && external.attemptId().equals(checkout.attemptId()))
                    && (external.attemptId() == null || external.attemptId().equals(checkout.attemptId()))).toList();
            if (matches.size() != 1 || external.liveMode() || !payment.externalReference().equals(external.reference())
                    || !"BRL".equals(external.currency()) || external.amount().compareTo(payment.valor()) != 0
                    || external.status().length() > 64) {
                workflow.review(payment.id(), "IDENTITY_OR_AMOUNT_MISMATCH");
                review = true;
                continue;
            }
            String outcome = workflow.observe(payment.id(), matches.getFirst().attemptId(), external, instant);
            if (outcome.equals("CONFLICT")) {
                workflow.review(payment.id(), "CONTRADICTORY_PROVIDER_RESULT");
                review = true;
            }
        }
        var known = workflow.knownPayments(payment.id());
        var approved = known.stream().filter(value -> value.status().equals("approved")).toList();
        if (approved.size() > 1 || payment.status() == PagamentoStatus.CONFIRMED && approved.stream()
                .anyMatch(value -> !value.id().equals(payment.providerPaymentId()))) {
            workflow.review(payment.id(), "MULTIPLE_EXTERNAL_SETTLEMENTS");
            review = true;
        }
        if (known.stream().anyMatch(value -> !Set.of("pending", "in_process", "authorized", "approved", "rejected", "cancelled")
                .contains(value.status()))) {
            workflow.review(payment.id(), "UNMAPPED_OR_POST_SETTLEMENT_STATUS");
            review = true;
        }
        if (!review && !approved.isEmpty() && payment.status() != PagamentoStatus.CONFIRMED) {
            ExternalPayment settlement = approved.getFirst();
            var attempt = payment.tentativas().stream().filter(value -> value.id().equals(settlement.attemptId())).findFirst().orElseThrow();
            payment.confirmarVerificado(attempt.idempotencyKey(), settlement.id(), instant);
            workflow.prepareSettlement(payment.id(), attempt.idempotencyKey(), instant);
            payments.save(payment);
            record(payment, "payment.confirmed.v1", Map.of("paymentId", payment.id().toString()), instant);
        } else if (!review && payment.status() != PagamentoStatus.CONFIRMED) {
            var currentAttempt = payment.tentativas().getLast();
            var latest = known.stream().filter(value -> value.attemptId().equals(currentAttempt.id())).findFirst();
            if (latest.isPresent() && (payment.status() == PagamentoStatus.PENDING || payment.status() == PagamentoStatus.PROCESSING)) {
                String status = latest.get().status();
                if (Set.of("rejected", "cancelled").contains(status)) {
                    String reason = "Pagamento recusado ou cancelado pelo provedor.";
                    payment.falhar(payment.idempotencyKey(), reason, instant);
                    payments.save(payment);
                    record(payment, "payment.failed.v1", Map.of("reason", reason), instant);
                } else if (Set.of("in_process", "authorized").contains(status) && payment.status() == PagamentoStatus.PENDING) {
                    payment.processar(payment.idempotencyKey(), instant);
                    payments.save(payment);
                }
            }
        }
        if (payment.status() == PagamentoStatus.CONFIRMED) {
            for (Checkout checkout : checkouts) if (checkout.preferenceId() != null && !checkout.state().equals("CLOSED")) {
                workflow.saveCheckout(new Checkout(checkout.paymentId(), checkout.attemptId(), checkout.key(), "CLOSING",
                        checkout.preferenceId(), checkout.checkoutUrl()));
            }
        }
        if (notification != null) workflow.finishNotification(notification, review ? "REVIEW" : "PROCESSED",
                instant, policy.syncInterval(), review ? "FINANCIAL_REVIEW" : null);
        return review;
    }

    private void record(Pagamento payment, String type, Map<String, Object> payload, Instant instant) {
        events.record(payment.id(), "PAYMENT", payment.osId(), payment.sagaId(),
                events.paymentCorrelation(payment.osId(), payment.sagaId()), type, payload, instant);
    }

    private Pagamento payment(UUID id) {
        var payment = payments.findById(id).orElseThrow(ResourceNotFoundException::new);
        require("MERCADO_PAGO".equals(payment.provider()), "Provedor incompativel");
        return payment;
    }

    private CheckoutRequest request(Pagamento payment, PagamentoTentativa attempt) {
        return new CheckoutRequest(payment.id(), attempt.id(), payment.osId(), payment.sagaId(), payment.externalReference(),
                attempt.idempotencyKey(), payment.valor(), payment.moeda(), payment.status().name());
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
