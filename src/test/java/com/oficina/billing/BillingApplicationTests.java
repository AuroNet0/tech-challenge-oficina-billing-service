package com.oficina.billing;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS, properties = {
        "spring.security.user.name=billing-test", "spring.security.user.password=test-password"
})
@AutoConfigureMockMvc
class BillingApplicationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("oficina_billing")
            .withUsername("billing")
            .withPassword("test-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ApplicationContext context;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private DataSource dataSource;

    @Test
    void startsSpringWithJpaValidationAndFinancialEntities() {
        assertThat(context.getBean(BillingApplication.class)).isNotNull();
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getMetamodel().getEntities()).hasSize(7);
        assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(context.getEnvironment().getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    }

    @Test
    void connectsOnlyToBillingPostgresql() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getCatalog()).isEqualTo("oficina_billing");
        }
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void preservesBootstrapMigrationAndAppliesFinancialSchema() {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class)).isTrue();
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        assertThat(tables).containsExactlyInAnyOrder("flyway_schema_history", "orcamentos", "orcamento_itens",
                "aprovacoes", "pagamentos", "pagamentos_tentativas", "inbox_messages", "outbox_events",
                "provider_sync", "provider_checkouts", "provider_notifications", "provider_payments", "provider_observations");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void exposesPublicHealthAndProbes(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void readinessFailsWhenDatabaseIsUnavailableWhileLivenessRemainsUp() throws Exception {
        doThrow(new SQLException("Database unavailable")).when(dataSource).getConnection();
        try {
            mvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"));
            mvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        } finally {
            reset(dataSource);
        }
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void exposesSwaggerUiAndItsConfiguration() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
        mvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }

    @Test
    void exposesOpenApiWithBasicAuthenticationAndOnlyAllowedFinancialOperations() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Oficina Billing Service"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/orcamentos/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orcamentos/{id}/aprovar'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/pagamentos'].post").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/api/orcamentos", "/api/pagamentos", "/actuator/env",
            "/actuator/health/db", "/swagger-ui.html", "/error"})
    void requiresAuthenticationOutsidePublicAllowlist(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsIncorrectBasicCredentials() throws Exception {
        mvc.perform(get("/api/pagamentos").with(httpBasic("billing-test", "incorrect")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void productionAuthorizationAdapterKeepsFinancialReadsUnavailable() throws Exception {
        var repository = context.getBean(com.oficina.billing.application.port.out.OrcamentoRepositoryPort.class);
        var quote = repository.save(TestFixtures.orcamento());
        mvc.perform(get("/api/v1/orcamentos/{id}", quote.id())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(jwt -> jwt.subject(java.util.UUID.randomUUID().toString()))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CLIENTE"))))
                .andExpect(status().isServiceUnavailable());
        assertThat(context.getBean(com.oficina.billing.application.authorization.FinancialAccessPort.class))
                .isInstanceOf(com.oficina.billing.infrastructure.web.security.UnavailableFinancialAccessAdapter.class);
    }

    @Test
    void acceptsBasicCredentialsWithoutProvidingFinancialEndpoints() throws Exception {
        mvc.perform(get("/api/pagamentos").with(httpBasic("billing-test", "test-password")))
                .andExpect(status().isUnauthorized());
    }
}
