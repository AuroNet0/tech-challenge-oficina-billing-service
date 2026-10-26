package com.oficina.billing.application.authorization;

import java.util.Set;
import java.util.UUID;

public interface FinancialAccessPort {
    enum Action { READ, DECIDE, CHECKOUT }
    enum Decision { ALLOW, DENY, UNAVAILABLE }
    Decision authorize(UUID actorId, Set<String> roles, UUID osId, Action action);
}
