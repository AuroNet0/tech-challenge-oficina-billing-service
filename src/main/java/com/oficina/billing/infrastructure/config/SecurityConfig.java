package com.oficina.billing.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import com.oficina.billing.infrastructure.web.security.CsrfTokenResponseFilter;
import com.oficina.billing.infrastructure.web.security.SecurityProblemHandler;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problems) throws Exception {
        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers(request -> "POST".equals(request.getMethod())
                        && (request.getContextPath() + "/api/v1/webhooks/mercado-pago").equals(request.getRequestURI())))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/mercado-pago").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/pagamentos/*/checkout").hasRole("BILLING_OPERATOR")
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness",
                                "/swagger-ui/**",
                                "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(problems))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .addFilterAfter(new CsrfTokenResponseFilter(), CsrfFilter.class)
                .build();
    }
}
