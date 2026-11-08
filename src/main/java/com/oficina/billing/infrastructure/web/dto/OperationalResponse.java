package com.oficina.billing.infrastructure.web.dto;

import java.util.UUID;
import com.oficina.billing.domain.model.Orcamento;
import com.oficina.billing.domain.model.Pagamento;

public record OperationalResponse(UUID id, UUID osId, String status) {
    public static OperationalResponse quote(Orcamento quote) {
        return new OperationalResponse(quote.id(), quote.osId(), quote.status().name());
    }
    public static OperationalResponse payment(Pagamento payment) {
        return new OperationalResponse(payment.id(), payment.osId(), payment.status().name());
    }
}
