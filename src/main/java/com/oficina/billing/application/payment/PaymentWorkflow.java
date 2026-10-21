package com.oficina.billing.application.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.oficina.billing.application.payment.ProviderData.*;
import com.oficina.billing.application.port.in.CheckoutPort;
import com.oficina.billing.application.port.out.PaymentProviderPort;

@Service
public class PaymentWorkflow implements CheckoutPort {
    private final ProviderTransactions transactions;
    private final PaymentProviderPort provider;
    private final PaymentIntegrationPolicy policy;

    public PaymentWorkflow(ProviderTransactions transactions, PaymentProviderPort provider, PaymentIntegrationPolicy policy) {
        this.transactions = transactions;
        this.provider = provider;
        this.policy = policy;
    }

    @Override
    public CheckoutResponse iniciar(UUID paymentId) {
        enabled();
        Lease lease = transactions.reserveCheckout(paymentId);
        Checkout checkout = lease.checkout();
        try {
            if (lease.create()) {
                try {
                    Preference preference = provider.createPreference(lease.request());
                    transactions.storePreference(lease, checkout, preference);
                    checkout = new Checkout(checkout.paymentId(), checkout.attemptId(), checkout.key(), "READY",
                            preference.id(), preference.checkoutUrl());
                } catch (RuntimeException exception) {
                    transactions.unknown(lease);
                    transactions.finish(lease, "CREATE_RESULT_UNKNOWN");
                    return response(lease, new Checkout(checkout.paymentId(), checkout.attemptId(), checkout.key(), "UNKNOWN", null, null));
                }
            } else if (!checkout.state().equals("READY")) {
                recoverPreferences(lease);
                checkout = transactions.checkouts(lease).stream().filter(value -> value.attemptId().equals(lease.request().attemptId()))
                        .findFirst().orElseThrow();
            }
            transactions.finish(lease, null);
            return response(lease, checkout);
        } catch (RuntimeException exception) {
            transactions.finish(lease, "CHECKOUT_RECONCILIATION_ERROR");
            throw exception;
        }
    }

    public boolean processNotification() {
        if (!policy.enabled()) return false;
        Notification notification = transactions.notification();
        if (notification == null) return false;
        Lease lease = null;
        try {
            ExternalPayment payment = provider.getPayment(notification.resourceId());
            UUID paymentId = transactions.findPayment(payment.reference());
            if (paymentId == null) {
                transactions.notificationResult(notification, "REVIEW", "UNKNOWN_REFERENCE");
                return true;
            }
            lease = transactions.reserveSync(paymentId);
            if (lease == null) {
                transactions.notificationResult(notification, "PENDING", "PAYMENT_BUSY");
                return true;
            }
            synchronize(lease, notification, payment);
        } catch (RuntimeException exception) {
            if (lease != null) transactions.finish(lease, "PROVIDER_QUERY_ERROR");
            transactions.notificationResult(notification, exception instanceof ProviderException error && !error.retryable()
                    ? "REVIEW" : "PENDING", "PROVIDER_QUERY_ERROR");
        }
        return true;
    }

    public boolean processDue() {
        if (!policy.enabled()) return false;
        Lease lease = transactions.reserveDue();
        if (lease == null) return false;
        try { synchronize(lease, null, null); }
        catch (RuntimeException exception) { transactions.finish(lease, "PROVIDER_QUERY_ERROR"); }
        return true;
    }

    private void synchronize(Lease lease, Notification notification, ExternalPayment direct) {
        recoverPreferences(lease);
        List<ExternalPayment> payments = new ArrayList<>(provider.searchPayments(lease.request().reference()));
        if (direct != null && payments.stream().noneMatch(value -> value.id().equals(direct.id()))) payments.add(direct);
        transactions.apply(lease, payments, notification);
        for (Checkout checkout : transactions.checkouts(lease)) if (checkout.state().equals("CLOSING")) {
            try {
                provider.expirePreference(checkout.preferenceId());
                transactions.close(lease, checkout);
            } catch (RuntimeException exception) {
                transactions.finish(lease, "PREFERENCE_CLOSURE_PENDING");
                return;
            }
        }
        transactions.finish(lease, null);
    }

    private void recoverPreferences(Lease lease) {
        for (Checkout checkout : transactions.checkouts(lease)) {
            if (!List.of("NEW", "CREATING", "UNKNOWN").contains(checkout.state())) continue;
            var matches = provider.searchPreferences(lease.request().reference()).stream()
                    .filter(preference -> checkout.attemptId().equals(preference.attemptId())).toList();
            if (matches.size() > 1) {
                transactions.review(lease, "DUPLICATE_PREFERENCES");
                throw new ProviderException(false);
            }
            if (matches.size() == 1) transactions.storePreference(lease, checkout, matches.getFirst());
        }
    }

    private CheckoutResponse response(Lease lease, Checkout checkout) {
        return new CheckoutResponse(lease.paymentId(), "MERCADO_PAGO", checkout.preferenceId(), checkout.checkoutUrl(),
                lease.request().paymentStatus(), checkout.state());
    }

    private void enabled() { if (!policy.enabled()) throw new IntegrationUnavailableException(); }
}
