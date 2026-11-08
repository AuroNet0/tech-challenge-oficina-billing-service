package com.oficina.billing.application.port.out;

import java.util.List;
import com.oficina.billing.application.payment.ProviderData.*;

public interface PaymentProviderPort {
    Preference createPreference(CheckoutRequest request);
    Preference getPreference(String id);
    List<Preference> searchPreferences(String reference);
    ExternalPayment getPayment(String id);
    List<ExternalPayment> searchPayments(String reference);
    void expirePreference(String id);
}
