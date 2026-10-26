package com.oficina.billing.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import com.oficina.billing.application.authorization.FinancialAccessPort;
import com.oficina.billing.application.authorization.FinancialAccessPort.*;
import com.oficina.billing.infrastructure.web.security.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinancialAuthorizationTest {
    private final FinancialAccessPort port = mock(FinancialAccessPort.class);
    private final FinancialAuthorization authorization = new FinancialAuthorization(port);
    private final UUID os = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void login(String role) {
        var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(actor.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void clientNeedsPositiveCurrentDecisionAndIdentityComesFromSubject() {
        login("CLIENTE");
        when(port.authorize(eq(actor), anySet(), eq(os), eq(Action.DECIDE))).thenReturn(Decision.ALLOW);
        assertThat(authorization.decisionActor(os, null)).isEqualTo(actor.toString());
        assertThat(authorization.decisionActor(os, actor.toString())).isEqualTo(actor.toString());
        assertThatThrownBy(() -> authorization.decisionActor(os, UUID.randomUUID().toString()))
                .isInstanceOf(AccessDeniedException.class);
        when(port.authorize(any(), anySet(), any(), any())).thenReturn(Decision.DENY);
        assertThatThrownBy(() -> authorization.require(os, Action.CHECKOUT)).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATENDENTE", "GERENTE", "MECANICO"})
    void staffCannotDecideOrCheckout(String role) {
        login(role);
        for (var action : List.of(Action.DECIDE, Action.CHECKOUT)) {
            assertThatThrownBy(() -> authorization.require(os, action)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATENDENTE", "GERENTE"})
    void operationalRolesRequireExplicitScope(String role) {
        login(role);
        when(port.authorize(any(), anySet(), any(), any())).thenReturn(Decision.ALLOW);
        assertThat(authorization.require(os, Action.READ)).isEqualTo(actor.toString());
        assertThat(authorization.operational()).isEqualTo(role.equals("ATENDENTE"));
        when(port.authorize(any(), anySet(), any(), any())).thenReturn(Decision.DENY);
        assertThatThrownBy(() -> authorization.require(os, Action.READ)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noAuthenticationOrMechanicCannotRead() {
        assertThatThrownBy(() -> authorization.require(os, Action.READ)).isInstanceOf(AccessDeniedException.class);
        login("MECANICO");
        assertThatThrownBy(() -> authorization.require(os, Action.READ)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(port);
    }

    @Test
    void unavailableAndBrokenDependencyNeverGrantAccess() {
        login("CLIENTE");
        assertThat(new UnavailableFinancialAccessAdapter().authorize(actor, java.util.Set.of("ROLE_CLIENTE"), os, Action.READ))
                .isEqualTo(Decision.UNAVAILABLE);
        for (var decision : new Decision[]{null, Decision.UNAVAILABLE}) {
            when(port.authorize(any(), anySet(), any(), any())).thenReturn(decision);
            assertThatThrownBy(() -> authorization.require(os, Action.READ)).isInstanceOf(ResponseStatusException.class)
                    .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value()).isEqualTo(503);
        }
        when(port.authorize(any(), anySet(), any(), any())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> authorization.require(os, Action.READ)).isInstanceOf(ResponseStatusException.class);
    }
}
