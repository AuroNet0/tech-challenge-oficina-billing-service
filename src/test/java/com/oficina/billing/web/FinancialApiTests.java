package com.oficina.billing.web;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.jayway.jsonpath.JsonPath;
import com.oficina.billing.application.port.out.*;
import com.oficina.billing.domain.enums.*;
import com.oficina.billing.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static com.oficina.billing.TestFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = {
        "spring.security.user.name=billing-test", "spring.security.user.password=test-password"
})
@AutoConfigureMockMvc
class FinancialApiTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("oficina_billing").withUsername("billing").withPassword("test-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mvc;
    @MockitoSpyBean private OrcamentoRepositoryPort orcamentos;
    @MockitoSpyBean private AprovacaoRepositoryPort aprovacoes;
    @Autowired private PagamentoRepositoryPort pagamentos;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void prepareDatabase() {
        jdbc.execute("TRUNCATE pagamentos_tentativas, pagamentos, aprovacoes, orcamento_itens, orcamentos CASCADE");
    }

    @Test
    void consultsBudgetWithExactDecimalStringsAndUnmodifiedItems() throws Exception {
        var budget = orcamentos.save(orcamento());
        mvc.perform(auth(get("/api/v1/orcamentos/{id}", budget.id())))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.osId").value(budget.osId().toString()))
                .andExpect(jsonPath("$.sagaId").value(budget.sagaId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.valorTotal").value("150.00"))
                .andExpect(jsonPath("$.itens[0].quantidade").value("1.2500"))
                .andExpect(jsonPath("$.itens[0].precoUnitario").value("80.000000"))
                .andExpect(jsonPath("$.itens[0].valorTotal").value("100.00"));
        mvc.perform(auth(get("/api/v1/orcamentos").param("osId", budget.osId().toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(budget.id().toString()));
        assertThat(orcamentos.findById(budget.id()).orElseThrow().version()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"aprovar", "rejeitar"})
    void persistsRestDecisionAndPreservesOriginalOnRepeats(String action) throws Exception {
        var budget = orcamentos.findById(orcamentos.save(orcamento()).id()).orElseThrow();
        var correlation = UUID.randomUUID();
        String body = action.equals("aprovar") ? "{\"responsavelId\":\"id-informado\"}"
                : "{\"responsavelId\":\"id-informado\",\"motivo\":\"Acima do esperado\"}";
        String expected = action.equals("aprovar") ? "APPROVED" : "REJECTED";
        var response = mvc.perform(auth(post("/api/v1/orcamentos/{id}/{action}", budget.id(), action))
                        .with(csrf()).header("X-Correlation-Id", correlation).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.correlationId").value(correlation.toString()))
                .andExpect(jsonPath("$.responsavelId").value("id-informado"))
                .andReturn().getResponse().getContentAsString();
        var saved = orcamentos.findById(budget.id()).orElseThrow();
        var decision = aprovacoes.findByOrcamentoId(budget.id()).orElseThrow();
        assertThat(saved.status().name()).isEqualTo(expected);
        assertThat(decision.decisionAt()).isEqualTo(saved.updatedAt()).isEqualTo(decision.createdAt());
        assertThat(JsonPath.<String>read(response, "$.decisionAt")).isEqualTo(decision.decisionAt().toString());
        assertThat(saved.itens()).isEqualTo(budget.itens());
        assertThat(saved.valorTotal()).isEqualTo(budget.valorTotal());
        if (action.equals("rejeitar")) {
            assertThat(decision.motivo()).isEqualTo("Acima do esperado");
            assertThat(saved.rejectedAt()).isEqualTo(decision.decisionAt());
        } else {
            assertThat(saved.approvedAt()).isEqualTo(decision.decisionAt());
        }
        for (var repeated : List.of("aprovar", "rejeitar")) {
            mvc.perform(auth(post("/api/v1/orcamentos/{id}/{action}", budget.id(), repeated))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(409));
        }
        mvc.perform(auth(get("/api/v1/orcamentos/{id}/aprovacao", budget.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(decision.id().toString()));
        assertThat(aprovacoes.findByOrcamentoId(budget.id()).orElseThrow()).isEqualTo(decision);
        assertThat(orcamentos.findById(budget.id()).orElseThrow().version()).isEqualTo(saved.version());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aprovacoes", Integer.class)).isEqualTo(1);
    }

    @Test
    void optionalDecisionMetadataDoesNotInventIdentity() throws Exception {
        var budget = orcamentos.save(orcamento());
        mvc.perform(auth(post("/api/v1/orcamentos/{id}/rejeitar", budget.id()))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        var decision = aprovacoes.findByOrcamentoId(budget.id()).orElseThrow();
        assertThat(decision.responsavelId()).isNull();
        assertThat(decision.motivo()).isNull();
        assertThat(decision.correlationId()).isNull();
    }

    @Test
    void rollsBackBudgetWhenDatabaseRejectsDecision() throws Exception {
        var budget = orcamentos.save(orcamento());
        jdbc.execute("""
                CREATE FUNCTION test_reject_decision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'internal SQL detail secret' USING ERRCODE = '23514'; END $$;
                """);
        jdbc.execute("""
                CREATE TRIGGER test_reject_decision BEFORE INSERT ON aprovacoes
                FOR EACH ROW EXECUTE FUNCTION test_reject_decision()
                """);
        try {
            mvc.perform(auth(post("/api/v1/orcamentos/{id}/aprovar", budget.id()))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("Operacao em conflito com o estado atual do recurso."))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret"))));
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_decision ON aprovacoes");
            jdbc.execute("DROP FUNCTION test_reject_decision()");
        }
        assertPendingWithoutDecision(budget.id());
    }

    @Test
    void unexpectedFailureReturnsSanitizedProblemAndRollsBack() throws Exception {
        var budget = orcamentos.save(orcamento());
        doThrow(new IllegalStateException("SQL password secret")).when(aprovacoes).save(any());
        mvc.perform(auth(post("/api/v1/orcamentos/{id}/aprovar", budget.id()))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Nao foi possivel concluir a requisicao."))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret"))));
        assertPendingWithoutDecision(budget.id());
    }

    @Test
    void concurrentRestDecisionsCommitOneAndReturnOneConflict() throws Exception {
        var budget = orcamentos.save(orcamento());
        var barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            barrier.await(15, TimeUnit.SECONDS);
            return result;
        }).when(aprovacoes).findByOrcamentoId(budget.id());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approval = executor.submit(() -> decide(budget.id(), "aprovar"));
            var rejection = executor.submit(() -> decide(budget.id(), "rejeitar"));
            assertThat(List.of(approval.get(30, TimeUnit.SECONDS), rejection.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            reset(aprovacoes);
        }
        var decision = aprovacoes.findByOrcamentoId(budget.id()).orElseThrow();
        assertThat(orcamentos.findById(budget.id()).orElseThrow().status().name()).isEqualTo(decision.status().name());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aprovacoes", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aprovar", "rejeitar"})
    void cancelledBudgetCannotBeDecided(String action) throws Exception {
        var budget = orcamentos.save(orcamento());
        budget.cancelar(NOW.plusSeconds(1));
        orcamentos.save(budget);
        mvc.perform(auth(post("/api/v1/orcamentos/{id}/{action}", budget.id(), action))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        assertThat(aprovacoes.findByOrcamentoId(budget.id())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/orcamentos/", "/api/v1/pagamentos/"})
    void returns404ForMissingFinancialResource(String path) throws Exception {
        mvc.perform(auth(get(path + UUID.randomUUID()))).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void missingBudgetDecisionAndMissingOsHaveExplicitSemantics() throws Exception {
        var missing = UUID.randomUUID();
        mvc.perform(auth(get("/api/v1/orcamentos").param("osId", missing.toString()))).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/pagamentos").param("osId", missing.toString())))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(auth(get("/api/v1/orcamentos/{id}/aprovacao", missing))).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/v1/orcamentos/{id}/aprovar", missing)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        var budget = orcamentos.save(orcamento());
        mvc.perform(auth(get("/api/v1/orcamentos/{id}/aprovacao", budget.id()))).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"valorTotal\":1}", "{\"itens\":[]}", "{\"responsavelId\":\" \"}",
            "{\"responsavelId\":[]}", "null", "{invalid"})
    void rejectsInvalidOrFinanciallyTamperedApprovalBody(String body) throws Exception {
        var budget = orcamentos.save(orcamento());
        mvc.perform(auth(post("/api/v1/orcamentos/{id}/aprovar", budget.id())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        assertPendingWithoutDecision(budget.id());
    }

    @Test
    void validatesUuidParametersCorrelationAndDecisionTextLengths() throws Exception {
        mvc.perform(auth(get("/api/v1/orcamentos/not-uuid"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/orcamentos"))).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/pagamentos").param("osId", "invalid"))).andExpect(status().isBadRequest());
        var budget = orcamentos.save(orcamento());
        mvc.perform(auth(post("/api/v1/orcamentos/{id}/aprovar", budget.id())).with(csrf())
                        .header("X-Correlation-Id", "invalid").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        for (var body : List.of("{\"responsavelId\":\"" + "x".repeat(101) + "\"}",
                "{\"motivo\":\"" + "x".repeat(501) + "\"}", "{\"motivo\":\" \"}")) {
            mvc.perform(auth(post("/api/v1/orcamentos/{id}/rejeitar", budget.id())).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(auth(post("/api/v1/orcamentos/{id}/aprovar", budget.id())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
        assertPendingWithoutDecision(budget.id());
    }

    @Test
    void requiresAuthenticationAndRejectsInvalidCredentialsWithProblemDetails() throws Exception {
        var id = UUID.randomUUID();
        for (var path : List.of("/api/v1/orcamentos/" + id, "/api/v1/pagamentos/" + id,
                "/api/v1/orcamentos/" + id + "/aprovacao", "/api/v1/orcamentos?osId=" + id,
                "/api/v1/pagamentos?osId=" + id)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(401));
        }
        for (var action : List.of("aprovar", "rejeitar")) {
            mvc.perform(post("/api/v1/orcamentos/{id}/{action}", id, action).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"responsavelId\":\"billing-test\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        }
        mvc.perform(get("/api/v1/orcamentos/{id}", id).with(httpBasic("billing-test", "incorrect")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void requiresValidCsrfTokenAndSupportsTokenFromExistingGet() throws Exception {
        var budget = orcamentos.save(orcamento());
        mvc.perform(auth(post("/api/v1/orcamentos/{id}/aprovar", budget.id()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(auth(post("/api/v1/orcamentos/{id}/aprovar", budget.id())).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        var getResult = mvc.perform(auth(get("/api/v1/orcamentos/{id}", budget.id()))).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) getResult.getRequest().getSession(false);
        var token = getResult.getResponse().getHeader("X-CSRF-TOKEN");
        mvc.perform(auth(post("/api/v1/orcamentos/{id}/aprovar", budget.id())).session(session)
                        .header("X-CSRF-TOKEN", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void exposesPaymentHistoryWithoutProviderSecretsOrKeys() throws Exception {
        var pending = orcamentos.save(orcamento());
        var approved = new TransactionTemplate(transactionManager).execute(status -> {
            pending.aprovar(NOW.plusSeconds(1));
            var saved = orcamentos.save(pending);
            aprovacoes.save(Aprovacao.registrar(saved, null, null, null, NOW.plusSeconds(1)));
            return saved;
        });
        var payment = pagamentos.save(Pagamento.criar(approved, "MERCADO_PAGO", "internal-reference",
                "internal-key", NOW.plusSeconds(2)));
        payment.falhar("internal-key", "upstream token=secret", NOW.plusSeconds(3));
        payment = pagamentos.save(payment);
        payment.novaTentativa("retry-key", NOW.plusSeconds(4));
        payment = pagamentos.save(payment);
        payment.confirmar("retry-key", "provider-internal", NOW.plusSeconds(5));
        var confirmed = pagamentos.save(payment);
        for (var path : List.of("/api/v1/pagamentos/" + confirmed.id(),
                "/api/v1/pagamentos?osId=" + confirmed.osId())) {
            mvc.perform(auth(get(path))).andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("idempotencyKey"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("providerPaymentId"))));
        }
        mvc.perform(auth(get("/api/v1/pagamentos/{id}", confirmed.id())))
                .andExpect(jsonPath("$.valor").value("150.00"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.tentativas.length()").value(2))
                .andExpect(jsonPath("$.tentativas[0].status").value("FAILED"))
                .andExpect(jsonPath("$.tentativas[1].status").value("CONFIRMED"));
    }

    @Test
    void cannotCreateBudgetsOrPaymentsOrMutatePaymentsThroughRest() throws Exception {
        for (var path : List.of("/api/v1/orcamentos", "/api/v1/pagamentos")) {
            mvc.perform(auth(post(path)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.status").value(405));
        }
        for (var action : List.of("confirmar", "falhar")) {
            mvc.perform(auth(post("/api/v1/pagamentos/{id}/{action}", UUID.randomUUID(), action)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orcamentos", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pagamentos", Integer.class)).isZero();
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.with(httpBasic("billing-test", "test-password"));
    }

    private void assertPendingWithoutDecision(UUID id) {
        var budget = orcamentos.findById(id).orElseThrow();
        assertThat(budget.status()).isEqualTo(OrcamentoStatus.PENDING_APPROVAL);
        assertThat(budget.approvedAt()).isNull();
        assertThat(budget.version()).isZero();
        assertThat(aprovacoes.findByOrcamentoId(id)).isEmpty();
    }

    private int decide(UUID id, String action) throws Exception {
        return mvc.perform(auth(post("/api/v1/orcamentos/{id}/{action}", id, action)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
    }
}
