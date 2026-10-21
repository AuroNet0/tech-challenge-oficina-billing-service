package com.oficina.billing.payment;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.oficina.billing.application.payment.*;
import com.oficina.billing.application.payment.ProviderData.*;
import com.oficina.billing.domain.enums.PagamentoStatus;
import com.oficina.billing.infrastructure.payment.*;
import tools.jackson.databind.json.JsonMapper;
import static com.oficina.billing.TestFixtures.*;
import static org.assertj.core.api.Assertions.*;

class ProviderUnitTests {
    static final String SECRET = "unit-test-webhook-secret-not-a-real-secret";

    static String signature(String id, String requestId, String timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "ts=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(
                    ("id:" + id + ";request-id:" + requestId + ";ts:" + timestamp + ";").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    static MercadoPagoProperties properties() {
        var properties = new MercadoPagoProperties();
        properties.setEnabled(true); properties.setAccessToken("TEST-local-unit-credential"); properties.setWebhookSecret(SECRET);
        properties.setNotificationUrl(URI.create("https://example.test/webhook"));
        properties.setSuccessUrl(URI.create("https://example.test/success"));
        properties.setFailureUrl(URI.create("https://example.test/failure"));
        properties.setPendingUrl(URI.create("https://example.test/pending"));
        return properties;
    }

    @Test
    void acceptsSecondsAndMillisecondsAndDeduplicatesTheSignedManifest() {
        var properties = properties();
        var verifier = new MercadoPagoSignatureVerifier(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        for (String timestamp : List.of("" + NOW.getEpochSecond(), "" + NOW.toEpochMilli())) {
            String signed = signature("123", "request-1", timestamp);
            assertThat(verifier.verify("123", "request-1", signed)).hasSize(64)
                    .isEqualTo(verifier.verify("123", "request-1", signed));
            assertThatThrownBy(() -> verifier.verify("124", "request-1", signed)).isInstanceOf(InvalidSignatureException.class);
            assertThatThrownBy(() -> verifier.verify("123", "request-2", signed)).isInstanceOf(InvalidSignatureException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ts=1,v1=abcd", "ts=1792497600,v1=no", "ts=1792497600,ts=1792497600", "ts=1792497600,v1=aa,extra=1"})
    void rejectsMalformedSignatures(String signature) {
        var verifier = new MercadoPagoSignatureVerifier(properties(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> verifier.verify("123", "request-1", signature)).isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsMissingFieldsOldFutureAndInvalidSecretsButCanConfigureTolerance() {
        var properties = properties();
        var verifier = new MercadoPagoSignatureVerifier(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> verifier.verify(null, "request", "signature")).isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify("123", null, null)).isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify("123", "bad;request", "signature")).isInstanceOf(InvalidSignatureException.class);
        for (long offset : List.of(-301L, 301L)) {
            String timestamp = "" + NOW.plusSeconds(offset).getEpochSecond();
            assertThatThrownBy(() -> verifier.verify("123", "request", signature("123", "request", timestamp)))
                    .isInstanceOf(InvalidSignatureException.class);
        }
        properties.setWebhookSecret("different-test-secret");
        assertThatThrownBy(() -> verifier.verify("123", "request", signature("123", "request", "" + NOW.getEpochSecond())))
                .isInstanceOf(InvalidSignatureException.class);
        properties.setWebhookSecret(SECRET); properties.setSignatureTolerance(Duration.ZERO);
        assertThat(verifier.verify("123", "request", signature("123", "request", "" + NOW.minusSeconds(400).getEpochSecond()))).hasSize(64);
        properties.setEnabled(false);
        assertThatThrownBy(() -> verifier.verify(null, null, null)).isInstanceOf(IntegrationUnavailableException.class);
    }

    @Test
    void configurationIsDisabledByDefaultAndRejectsUnsafeEnabledExecution() {
        new MercadoPagoProperties().validate();
        var properties = properties();
        properties.validate();
        properties.setAccessToken("");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setAccessToken("TEST-local"); properties.setApiBaseUrl(URI.create("http://untrusted.test"));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setApiBaseUrl(URI.create("https://api.mercadopago.com"));
        properties.setSuccessUrl(URI.create("http://localhost/return"));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setSuccessUrl(URI.create("https://example.test/success")); properties.setHttpTimeout(Duration.ZERO);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setHttpTimeout(Duration.ofSeconds(31));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setHttpTimeout(Duration.ofSeconds(5)); properties.setLeaseDuration(Duration.ofSeconds(5));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lateVerifiedSettlementPreservesTheFailedValueAndStopsNewAttempts() {
        var payment = pagamento();
        String firstKey = payment.idempotencyKey();
        var failed = payment.tentativas().getFirst();
        payment.falhar(firstKey, "Recusa", NOW.plusSeconds(3));
        failed = payment.tentativas().getFirst();
        payment.novaTentativa("second-key", NOW.plusSeconds(4));
        payment.confirmarVerificado(firstKey, "provider-1", NOW.plusSeconds(5));
        assertThat(payment.status()).isEqualTo(PagamentoStatus.CONFIRMED);
        assertThat(payment.idempotencyKey()).isEqualTo(firstKey);
        assertThat(payment.providerPaymentId()).isEqualTo("provider-1");
        assertThat(failed.status()).isEqualTo(PagamentoStatus.FAILED);
        assertThat(payment.tentativas().getLast().status()).isEqualTo(PagamentoStatus.FAILED);
        assertThat(payment.tentativas().getFirst().status()).isEqualTo(PagamentoStatus.CONFIRMED);
        payment.confirmarVerificado(firstKey, "provider-1", NOW.plusSeconds(6));
        assertThat(payment.confirmedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThatThrownBy(() -> payment.confirmarVerificado(firstKey, "other", NOW.plusSeconds(7))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> payment.novaTentativa("third", NOW.plusSeconds(7))).isInstanceOf(RuntimeException.class);
        var restored = com.oficina.billing.domain.model.Pagamento.restaurar(payment.id(), payment.osId(), payment.sagaId(),
                payment.orcamentoId(), payment.valor(), payment.moeda(), payment.provider(), payment.externalReference(),
                payment.createdAt(), payment.version(), payment.tentativas());
        assertThat(restored.status()).isEqualTo(PagamentoStatus.CONFIRMED);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 429, 500})
    void typedHttpClientClassifiesFailuresWithoutLeakingBodyOrToken(int code) {
        try (var server = new ProviderTestServer()) {
            var properties = properties(); properties.setApiBaseUrl(URI.create(server.url())); properties.setAllowInsecureLocal(true);
            properties.validate();
            var adapter = new MercadoPagoPaymentProviderAdapter(properties);
            server.errors.put("/v1/payments/123", code);
            assertThatThrownBy(() -> adapter.getPayment("123")).isInstanceOfSatisfying(ProviderException.class,
                    exception -> assertThat(exception.retryable()).isEqualTo(code >= 500 || code == 429))
                    .hasMessageNotContaining("simulated").hasMessageNotContaining(properties.getAccessToken());
            properties.setEnabled(false);
            assertThatThrownBy(() -> adapter.getPayment("123")).isInstanceOf(IntegrationUnavailableException.class);
        }
    }

    @Test
    void providerNumbersPreserveAllBrlDigitsWithoutDoubleConversion() {
        try (var server = new ProviderTestServer()) {
            var properties = properties(); properties.setApiBaseUrl(URI.create(server.url())); properties.setAllowInsecureLocal(true);
            var adapter = new MercadoPagoPaymentProviderAdapter(properties);
            BigDecimal amount = new BigDecimal("99999999999999999.99");
            var request = new CheckoutRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "reference", "stable-key", amount, "BRL", "PENDING");
            var preference = adapter.createPreference(request);
            assertThat(preference.amount()).isEqualByComparingTo(amount);
            assertThat(adapter.getPreference(preference.id()).amount()).isEqualByComparingTo(amount);
            assertThat(adapter.searchPreferences("reference")).hasSize(1);
        }
    }
}
