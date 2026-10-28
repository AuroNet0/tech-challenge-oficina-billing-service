package com.oficina.billing.infrastructure.config;

import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import com.oficina.billing.infrastructure.web.security.AuthJwtDecoder;
import com.oficina.billing.infrastructure.web.security.SecurityProblemHandler;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${billing.security.issuer}") String issuer,
                          @Value("${billing.security.audience}") String audience,
                          @Value("${billing.security.jwks-uri}") String jwksUri, Clock clock) {
        URI uri = URI.create(jwksUri);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("JWKS requires HTTPS");
        }
        return new AuthJwtDecoder(issuer, audience, jwksUri, clock);
    }

    @Bean
    @Order(1)
    SecurityFilterChain webhookChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(request -> "POST".equals(request.getMethod())
                        && (request.getContextPath() + "/api/v1/webhooks/mercado-pago").equals(request.getRequestURI()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problems) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
                                "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/notificacoes").hasRole("CLIENTE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orcamentos", "/api/v1/orcamentos/*",
                                "/api/v1/orcamentos/*/aprovacao", "/api/v1/pagamentos", "/api/v1/pagamentos/*")
                                .hasAnyRole("CLIENTE", "ATENDENTE", "GERENTE")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orcamentos/*/aprovar", "/api/v1/orcamentos/*/rejeitar",
                                "/api/v1/pagamentos/*/checkout").hasRole("CLIENTE")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems)).build();
    }
}
