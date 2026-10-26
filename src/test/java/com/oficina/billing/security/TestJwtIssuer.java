package com.oficina.billing.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public final class TestJwtIssuer implements AutoCloseable {
    public static final String ISSUER = "https://auth.example.test";
    public final RSAKey key;
    private final HttpServer server;
    public volatile boolean available = true;

    public TestJwtIssuer() {
        try {
            key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", exchange -> {
                if (!available) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public String jwksUri() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json"; }

    public Map<String, Object> claims(UUID user, String... roles) {
        long now = Instant.now().getEpochSecond();
        return new HashMap<>(Map.of("iss", ISSUER, "sub", user.toString(), "aud", List.of("oficina"),
                "iat", now, "nbf", now, "exp", now + 900, "jti", UUID.randomUUID().toString(), "roles", List.of(roles)));
    }

    public String token(UUID user, String... roles) { return sign(claims(user, roles), key, JWSAlgorithm.RS256); }

    public String sign(Map<String, Object> claims, RSAKey signingKey, JWSAlgorithm algorithm) {
        try {
            var token = new JWSObject(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                    new Payload(JSONObjectUtils.toJSONString(claims)));
            token.sign(new RSASSASigner(signingKey));
            return token.serialize();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    @Override
    public void close() { server.stop(0); }
}
