package com.oficina.billing.security;

import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import com.oficina.billing.application.authorization.FinancialAccessPort;
import com.oficina.billing.infrastructure.web.security.AuthJwtDecoder;
import static org.mockito.Mockito.*;

@TestConfiguration(proxyBeanMethods = false)
public class FinancialTestSecurity {
    public static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final TestJwtIssuer ISSUER = new TestJwtIssuer();

    @Bean
    @Primary
    JwtDecoder testDecoder() {
        return new AuthJwtDecoder(TestJwtIssuer.ISSUER, "oficina", ISSUER.jwksUri(), Clock.systemUTC());
    }

    @Bean
    @Primary
    FinancialAccessPort testFinancialAccess() { return mock(FinancialAccessPort.class); }

    public static RequestPostProcessor bearer(String... roles) {
        String token = ISSUER.token(ACTOR, roles);
        return request -> { request.addHeader("Authorization", "Bearer " + token); return request; };
    }

    public static String expiredToken() {
        var claims = ISSUER.claims(ACTOR, "CLIENTE");
        long past = java.time.Instant.now().minusSeconds(1000).getEpochSecond();
        claims.put("iat", past);
        claims.put("nbf", past);
        claims.put("exp", past + 900);
        return ISSUER.sign(claims, ISSUER.key, com.nimbusds.jose.JWSAlgorithm.RS256);
    }
}
