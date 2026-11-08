package com.oficina.billing.infrastructure.web.security;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AuthJwtDecoder implements JwtDecoder {
    public static final Set<String> ROLES = Set.of("CLIENTE", "ATENDENTE", "MECANICO", "GERENTE");
    private NimbusJwtDecoder delegate;
    private final String jwksUri;
    private long refreshedAt;
    private final java.util.function.LongSupplier ticker;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    public AuthJwtDecoder(String issuer, String audience, String jwksUri, Clock clock) {
        this(issuer, audience, jwksUri, clock, System::nanoTime);
    }

    public AuthJwtDecoder(String issuer, String audience, String jwksUri, Clock clock,
                          java.util.function.LongSupplier ticker) {
        if (issuer.isBlank() || audience.isBlank() || jwksUri.isBlank()) {
            throw new IllegalArgumentException("JWT configuration is required");
        }
        this.issuer = issuer;
        this.audience = audience;
        this.clock = clock;
        this.ticker = ticker;
        this.jwksUri = jwksUri;
    }

    private void refresh() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        delegate = NimbusJwtDecoder.withJwkSetUri(jwksUri).jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(factory)).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        delegate.setJwtValidator(timestamps);
    }

    @Override
    public synchronized Jwt decode(String token) throws JwtException {
        try {
            if (token == null || token.length() > 8192) { throw invalid(); }
            var parsed = SignedJWT.parse(token);
            var header = parsed.getHeader().toJSONObject();
            if (!"RS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))
                    || !(header.get("kid") instanceof String keyId) || keyId.isBlank()
                    || !Set.of("alg", "typ", "kid").containsAll(header.keySet())) { throw invalid(); }
            Map<String, Object> claims = parsed.getPayload().toJSONObject();
            if (!issuer.equals(claims.get("iss")) || !canonicalUuid(claims.get("sub"))
                    || !canonicalUuid(claims.get("jti"))) { throw invalid(); }
            if (!(claims.get("aud") instanceof List<?> audiences) || audiences.isEmpty()
                    || audiences.stream().anyMatch(value -> !(value instanceof String))
                    || !audiences.contains(audience)) { throw invalid(); }
            if (!(claims.get("roles") instanceof List<?> roles)
                    || roles.stream().anyMatch(value -> !(value instanceof String) || !ROLES.contains(value))
                    || roles.stream().distinct().count() != roles.size()) { throw invalid(); }
            long issued = numericDate(claims.get("iat"));
            long notBefore = numericDate(claims.get("nbf"));
            long expires = numericDate(claims.get("exp"));
            long now = clock.instant().getEpochSecond();
            if (issued < 0 || issued > now || notBefore != issued || expires <= now || expires <= issued
                    || expires - issued > 900) { throw invalid(); }
            long monotonicNow = ticker.getAsLong();
            if (delegate == null || monotonicNow - refreshedAt >= Duration.ofSeconds(60).toNanos()) {
                refresh();
                refreshedAt = monotonicNow;
            }
            return delegate.decode(token);
        } catch (JwtException exception) {
            throw invalid();
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static long numericDate(Object value) {
        if (!(value instanceof Long || value instanceof Integer)) { throw invalid(); }
        return ((Number) value).longValue();
    }

    private static boolean canonicalUuid(Object value) {
        if (!(value instanceof String text)) { return false; }
        try { return UUID.fromString(text).toString().equals(text); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private static BadJwtException invalid() { return new BadJwtException("Invalid access token"); }
}
