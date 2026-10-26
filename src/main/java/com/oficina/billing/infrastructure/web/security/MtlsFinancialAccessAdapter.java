package com.oficina.billing.infrastructure.web.security;

import com.oficina.billing.application.authorization.FinancialAccessPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("mtls")
public final class MtlsFinancialAccessAdapter implements FinancialAccessPort {
    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public MtlsFinancialAccessAdapter(@Value("${oficina.internal.os-uri}") URI endpoint) {
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !"/internal/financial-authorization".equals(endpoint.getPath())) {
            throw new IllegalArgumentException("Trusted HTTPS authorization endpoint required");
        }
        this.endpoint = endpoint;
    }

    @Override
    public Decision authorize(UUID actorId, Set<String> roles, UUID osId, Action action) {
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!(authentication instanceof JwtAuthenticationToken user) || !user.isAuthenticated()
                    || !user.getToken().getSubject().equals(actorId.toString())) { return Decision.DENY; }
            var request = new Request(actorId, osId, action, UUID.randomUUID());
            var response = http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + user.getToken().getTokenValue())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 1024
                    || !"no-store".equals(response.headers().firstValue("Cache-Control").orElse(""))) { return Decision.UNAVAILABLE; }
            var decision = json.readValue(response.body(), Response.class);
            if (!actorId.equals(decision.usuarioId()) || !osId.equals(decision.osId()) || action != decision.action()
                    || !request.correlationId().equals(decision.correlationId())) { return Decision.DENY; }
            return decision.allowed() ? Decision.ALLOW : Decision.DENY;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Decision.UNAVAILABLE;
        } catch (Exception exception) { return Decision.UNAVAILABLE; }
    }

    record Request(UUID usuarioId, UUID osId, Action action, UUID correlationId) {}
    record Response(UUID usuarioId, UUID osId, Action action, UUID correlationId, boolean allowed) {}
}
