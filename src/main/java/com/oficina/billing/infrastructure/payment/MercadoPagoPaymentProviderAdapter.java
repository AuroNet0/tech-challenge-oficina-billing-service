package com.oficina.billing.infrastructure.payment;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Component;
import com.oficina.billing.application.payment.*;
import com.oficina.billing.application.payment.ProviderData.*;
import com.oficina.billing.application.port.out.PaymentProviderPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class MercadoPagoPaymentProviderAdapter implements PaymentProviderPort {
    private final MercadoPagoProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public MercadoPagoPaymentProviderAdapter(MercadoPagoProperties properties) {
        this.properties = properties;
        this.mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        this.client = HttpClient.newBuilder().connectTimeout(properties.getHttpTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Preference createPreference(CheckoutRequest request) {
        String seller = seller();
        var payload = Map.of("external_reference", request.reference(), "items", List.of(Map.of(
                "id", request.paymentId().toString(), "title", "Orcamento " + request.paymentId(),
                "quantity", 1, "unit_price", request.amount(), "currency_id", request.currency())),
                "metadata", Map.of("billing_attempt_id", request.attemptId().toString(),
                        "billing_payment_id", request.paymentId().toString(), "billing_os_id", request.osId().toString(),
                        "billing_saga_id", request.sagaId().toString(), "billing_idempotency_key", request.key()),
                "notification_url", properties.getNotificationUrl().toString(),
                "back_urls", Map.of("success", properties.getSuccessUrl().toString(),
                        "failure", properties.getFailureUrl().toString(), "pending", properties.getPendingUrl().toString()),
                "auto_return", "approved");
        JsonNode response = send("POST", "/checkout/preferences", payload);
        if (!seller.equals(text(response, "collector_id"))) throw new ProviderException(false);
        return preference(response);
    }

    @Override
    public Preference getPreference(String id) {
        JsonNode node = send("GET", "/checkout/preferences/" + segment(id), null);
        if (!seller().equals(text(node, "collector_id"))) throw new ProviderException(false);
        return preference(node);
    }

    @Override
    public List<Preference> searchPreferences(String reference) {
        var preferences = new ArrayList<Preference>();
        for (int offset = 0; offset < 100; offset += 20) {
            JsonNode node = send("GET", "/checkout/preferences/search?external_reference=" + segment(reference)
                    + "&limit=20&offset=" + offset, null);
            JsonNode elements = node.get("elements");
            if (elements == null || !elements.isArray() || !node.hasNonNull("total")) throw new ProviderException(true);
            for (JsonNode element : elements) preferences.add(getPreference(text(element, "id")));
            if (offset + elements.size() >= node.get("total").asInt()) return preferences;
            if (elements.isEmpty()) throw new ProviderException(true);
        }
        throw new ProviderException(false);
    }

    @Override
    public ExternalPayment getPayment(String id) {
        JsonNode node = send("GET", "/v1/payments/" + segment(id), null);
        if (!id.equals(text(node, "id")) || !seller().equals(text(node, "collector_id"))
                || !node.hasNonNull("live_mode") || !node.get("live_mode").isBoolean()) throw new ProviderException(false);
        String preferenceId = null;
        JsonNode order = node.get("order");
        if (order != null && order.hasNonNull("id")) {
            JsonNode merchant = send("GET", "/merchant_orders/" + segment(text(order, "id")), null);
            preferenceId = text(merchant, "preference_id");
        }
        UUID attemptId = optionalAttempt(node);
        if (preferenceId == null && attemptId == null) throw new ProviderException(false);
        return new ExternalPayment(id, text(node, "external_reference"), preferenceId, attemptId,
                decimal(node, "transaction_amount"), text(node, "currency_id"), text(node, "status"),
                OffsetDateTime.parse(text(node, "date_last_updated")).toInstant(), node.get("live_mode").asBoolean());
    }

    @Override
    public List<ExternalPayment> searchPayments(String reference) {
        var payments = new ArrayList<ExternalPayment>();
        for (int offset = 0; offset < 100; offset += 20) {
            JsonNode node = send("GET", "/v1/payments/search?external_reference=" + segment(reference)
                    + "&limit=20&offset=" + offset, null);
            JsonNode elements = node.get("results");
            if (elements == null || !elements.isArray() || !node.hasNonNull("paging")
                    || !node.get("paging").hasNonNull("total")) throw new ProviderException(true);
            for (JsonNode element : elements) payments.add(getPayment(text(element, "id")));
            if (offset + elements.size() >= node.get("paging").get("total").asInt()) return payments;
            if (elements.isEmpty()) throw new ProviderException(true);
        }
        throw new ProviderException(false);
    }

    @Override
    public void expirePreference(String id) {
        Instant now = Instant.now();
        send("PUT", "/checkout/preferences/" + segment(id), Map.of("expires", true,
                "expiration_date_from", now.minusSeconds(3600).toString(),
                "expiration_date_to", now.minusSeconds(1).toString()));
        JsonNode checked = send("GET", "/checkout/preferences/" + segment(id), null);
        if (!checked.hasNonNull("expires") || !checked.get("expires").asBoolean()
                || OffsetDateTime.parse(text(checked, "expiration_date_to")).toInstant().isAfter(Instant.now())) {
            throw new ProviderException(true);
        }
    }

    private String seller() {
        JsonNode user = send("GET", "/users/me", null);
        boolean test = properties.getAccessToken().startsWith("TEST-");
        JsonNode tags = user.get("tags");
        if (tags != null && tags.isArray()) for (JsonNode tag : tags) test |= "test_user".equals(tag.asString());
        if (!test) throw new ProviderException(false);
        return text(user, "id");
    }

    private Preference preference(JsonNode node) {
        JsonNode items = node.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) throw new ProviderException(false);
        BigDecimal amount = BigDecimal.ZERO;
        for (JsonNode item : items) {
            if (!"BRL".equals(text(item, "currency_id"))) throw new ProviderException(false);
            amount = amount.add(decimal(item, "quantity").multiply(decimal(item, "unit_price")));
        }
        URI url = URI.create(text(node, "sandbox_init_point"));
        if (!"https".equals(url.getScheme()) || url.getUserInfo() != null
                || !("sandbox.mercadopago.com.br".equals(url.getHost()) || "sandbox.mercadopago.com".equals(url.getHost()))) {
            throw new ProviderException(false);
        }
        return new Preference(text(node, "id"), text(node, "external_reference"), optionalAttempt(node), amount, "BRL", url.toString());
    }

    private UUID optionalAttempt(JsonNode node) {
        JsonNode metadata = node.get("metadata");
        return metadata != null && metadata.hasNonNull("billing_attempt_id")
                ? UUID.fromString(text(metadata, "billing_attempt_id")) : null;
    }

    private JsonNode send(String method, String path, Object body) {
        if (!properties.isEnabled()) throw new IntegrationUnavailableException();
        try {
            var builder = HttpRequest.newBuilder(URI.create(properties.getApiBaseUrl().toString().replaceAll("/$", "") + path))
                    .timeout(properties.getHttpTimeout()).header("Authorization", "Bearer " + properties.getAccessToken())
                    .header("Accept", "application/json");
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderException(response.statusCode() == 429 || response.statusCode() == 408 || response.statusCode() >= 500);
            }
            if (response.body().length() > 2_000_000) throw new ProviderException(false);
            return mapper.readTree(response.body());
        } catch (ProviderException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderException(true);
        } catch (Exception exception) {
            throw new ProviderException(true);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || (!value.isString() && !value.isNumber())) throw new ProviderException(false);
        String text = value.asString();
        if (text.isBlank() || text.length() > 2048) throw new ProviderException(false);
        return text;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) throw new ProviderException(false);
        return new BigDecimal(value.asString());
    }

    private String segment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
