package com.oficina.billing.infrastructure.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.oficina.billing.application.payment.PaymentIntegrationPolicy;
import com.oficina.billing.application.payment.PaymentWorkflow;

@Component
public class PaymentReconciliationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentReconciliationScheduler.class);
    private final PaymentWorkflow workflow;
    private final PaymentIntegrationPolicy policy;

    public PaymentReconciliationScheduler(PaymentWorkflow workflow, PaymentIntegrationPolicy policy) {
        this.workflow = workflow;
        this.policy = policy;
    }

    @Scheduled(fixedDelayString = "${billing.mercado-pago.worker-delay:5s}")
    public void runOnce() {
        if (!policy.enabled()) return;
        for (int index = 0; index < policy.batchSize(); index++) {
            try {
                if (index % 2 == 0) {
                    if (!workflow.processNotification() && policy.reconciliationEnabled()) workflow.processDue();
                } else if (!policy.reconciliationEnabled() || !workflow.processDue()) workflow.processNotification();
            } catch (RuntimeException exception) {
                LOGGER.warn("payment reconciliation result=deferred errorType={}", exception.getClass().getSimpleName());
            }
        }
    }
}
