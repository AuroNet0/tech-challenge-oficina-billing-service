package com.oficina.billing.infrastructure.web.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import com.oficina.billing.application.authorization.FinancialAccessPort;
import com.oficina.billing.application.authorization.FinancialAccessPort.Action;

@Component
public final class FinancialAuthorization {
    private final FinancialAccessPort access;

    public FinancialAuthorization(FinancialAccessPort access) { this.access = access; }

    public String require(UUID osId, Action action) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            throw new AccessDeniedException("Acesso financeiro negado");
        }
        Set<String> roles = jwt.getAuthorities().stream().map(authority -> authority.getAuthority())
                .collect(Collectors.toUnmodifiableSet());
        boolean eligible = action == Action.READ
                ? roles.stream().anyMatch(Set.of("ROLE_CLIENTE", "ROLE_ATENDENTE", "ROLE_GERENTE")::contains)
                : roles.contains("ROLE_CLIENTE");
        if (!eligible) throw new AccessDeniedException("Acesso financeiro negado");
        var actor = UUID.fromString(jwt.getToken().getSubject());
        FinancialAccessPort.Decision decision;
        try { decision = access.authorize(actor, roles, osId, action); }
        catch (RuntimeException exception) { throw unavailable(); }
        if (decision == FinancialAccessPort.Decision.DENY) throw new AccessDeniedException("Acesso financeiro negado");
        if (decision != FinancialAccessPort.Decision.ALLOW) throw unavailable();
        return actor.toString();
    }

    public String decisionActor(UUID osId, String declaredActor) {
        String actor = require(osId, Action.DECIDE);
        if (declaredActor != null && !actor.equals(declaredActor)) {
            throw new AccessDeniedException("Responsavel divergente da identidade autenticada");
        }
        return actor;
    }

    public boolean operational() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ATENDENTE".equals(authority.getAuthority()));
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Autorizacao financeira indisponivel");
    }
}
