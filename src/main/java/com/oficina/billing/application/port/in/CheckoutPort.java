package com.oficina.billing.application.port.in;

import java.util.UUID;
import com.oficina.billing.application.payment.ProviderData.CheckoutResponse;

public interface CheckoutPort {
    CheckoutResponse iniciar(UUID pagamentoId);
}
