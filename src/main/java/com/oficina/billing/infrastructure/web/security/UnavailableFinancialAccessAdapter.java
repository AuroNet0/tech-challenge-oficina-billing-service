package com.oficina.billing.infrastructure.web.security;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.oficina.billing.application.authorization.FinancialAccessPort;

@Component
@org.springframework.context.annotation.Profile("!mtls")
public final class UnavailableFinancialAccessAdapter implements FinancialAccessPort {
    @Override
    public Decision authorize(UUID actorId, Set<String> roles, UUID osId, Action action) {
        return Decision.UNAVAILABLE;
    }
}
