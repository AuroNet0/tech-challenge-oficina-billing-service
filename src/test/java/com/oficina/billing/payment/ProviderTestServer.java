package com.oficina.billing.payment;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

final class ProviderTestServer implements AutoCloseable {
    record Request(String method, String path, String authorization, String body) {}
    final Map<String, Map<String, Object>> preferences = new ConcurrentHashMap<>();
    final Map<String, Map<String, Object>> payments = new ConcurrentHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final Map<String, Integer> errors = new ConcurrentHashMap<>();
    final AtomicInteger creates = new AtomicInteger();
    volatile long createDelay;
    volatile CountDownLatch creationStarted;
    volatile CountDownLatch continueCreation;
    private final JsonMapper mapper = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    ProviderTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    void reset() {
        preferences.clear(); payments.clear(); requests.clear(); errors.clear(); creates.set(0);
        createDelay = 0; creationStarted = null; continueCreation = null;
    }

    Map<String, Object> payment(String id, String preferenceId, String status, Instant updatedAt) {
        var preference = preferences.get(preferenceId);
        var value = new HashMap<String, Object>();
        value.put("id", id); value.put("collector_id", 42); value.put("live_mode", false);
        value.put("external_reference", preference.get("external_reference"));
        value.put("metadata", preference.get("metadata"));
        value.put("order", Map.of("id", "order-" + id));
        value.put("transaction_amount", new BigDecimal("150.00")); value.put("currency_id", "BRL");
        value.put("status", status); value.put("date_last_updated", updatedAt.toString());
        value.put("test_preference", preferenceId);
        payments.put(id, value);
        return value;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(method, exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("Authorization"), body));
            if (errors.containsKey(path)) { respond(exchange, errors.get(path), Map.of("message", "simulated provider failure")); return; }
            if (path.equals("/users/me")) { respond(exchange, 200, Map.of("id", 42, "tags", List.of("test_user"))); return; }
            if (path.equals("/checkout/preferences") && method.equals("POST")) {
                Map<String, Object> value = new HashMap<>(mapper.readValue(body, Map.class));
                String id = "preference-" + creates.incrementAndGet();
                value.put("id", id); value.put("collector_id", 42);
                value.put("sandbox_init_point", "https://sandbox.mercadopago.com.br/checkout/v1/redirect?pref_id=" + id);
                preferences.put(id, value);
                if (creationStarted != null) creationStarted.countDown();
                if (continueCreation != null) continueCreation.await(1500, TimeUnit.MILLISECONDS);
                if (createDelay > 0) Thread.sleep(createDelay);
                respond(exchange, 201, value); return;
            }
            if (path.equals("/checkout/preferences/search")) {
                String reference = query(exchange, "external_reference");
                var elements = preferences.values().stream().filter(value -> value.get("external_reference").equals(reference))
                        .map(value -> Map.of("id", value.get("id"))).toList();
                respond(exchange, 200, Map.of("elements", elements, "total", elements.size(), "next_offset", elements.size())); return;
            }
            if (path.startsWith("/checkout/preferences/")) {
                var value = preferences.get(path.substring("/checkout/preferences/".length()));
                if (value == null) { respond(exchange, 404, Map.of()); return; }
                if (method.equals("PUT")) value.putAll(mapper.readValue(body, Map.class));
                respond(exchange, 200, value); return;
            }
            if (path.equals("/v1/payments/search")) {
                String reference = query(exchange, "external_reference");
                var values = payments.values().stream().filter(value -> value.get("external_reference").equals(reference))
                        .map(value -> Map.of("id", value.get("id"))).toList();
                respond(exchange, 200, Map.of("results", values, "paging", Map.of("total", values.size()))); return;
            }
            if (path.startsWith("/v1/payments/")) {
                var value = payments.get(path.substring("/v1/payments/".length()));
                respond(exchange, value == null ? 404 : 200, value == null ? Map.of() : value); return;
            }
            if (path.startsWith("/merchant_orders/order-")) {
                var value = payments.get(path.substring("/merchant_orders/order-".length()));
                respond(exchange, 200, Map.of("preference_id", value.get("test_preference"))); return;
            }
            respond(exchange, 404, Map.of());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally { exchange.close(); }
    }

    private String query(HttpExchange exchange, String name) {
        for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals(name)) return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        return "";
    }

    private void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() { server.stop(0); executor.shutdownNow(); }
}
