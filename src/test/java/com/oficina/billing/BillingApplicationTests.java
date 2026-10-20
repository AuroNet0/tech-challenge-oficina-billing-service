package com.oficina.billing;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.ConfigurableWebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
class BillingApplicationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("oficina_billing")
            .withUsername("billing")
            .withPassword("test-password");

    private static ConfigurableWebApplicationContext context;
    private static EntityManagerFactory entityManagerFactory;
    private static JdbcTemplate jdbc;
    private static Flyway flyway;
    private static MockMvc mvc;
    private static DataSource dataSource;

    @BeforeAll
    static void startApplication() {
        context = (ConfigurableWebApplicationContext) SpringApplication.from(BillingApplication::main)
                .with(DatabaseSpyConfig.class)
                .run("--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.security.user.name=billing-test",
                        "--spring.security.user.password=test-password").getApplicationContext();
        entityManagerFactory = context.getBean(EntityManagerFactory.class);
        jdbc = context.getBean(JdbcTemplate.class);
        flyway = context.getBean(Flyway.class);
        dataSource = context.getBean(DataSource.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) {
            context.close();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseSpyConfig {

        @Bean
        static BeanPostProcessor dataSourceSpyPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource ? spy(bean) : bean;
                }
            };
        }
    }

    @Test
    void startsSpringWithJpaValidationAndWithoutDomainEntities() {
        assertThat(context.getBean(BillingApplication.class)).isNotNull();
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getMetamodel().getEntities()).isEmpty();
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
    void appliesBootstrapMigrationWithoutCreatingBusinessTables() {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class)).isTrue();
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        assertThat(tables).containsExactly("flyway_schema_history");
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
    void exposesOpenApiWithBasicAuthenticationAndNoFinancialOperations() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Oficina Billing Service"))
                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.scheme").value("basic"))
                .andExpect(jsonPath("$.paths").isEmpty());
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
    void acceptsBasicCredentialsWithoutProvidingFinancialEndpoints() throws Exception {
        mvc.perform(get("/api/pagamentos").with(httpBasic("billing-test", "test-password")))
                .andExpect(status().isNotFound());
    }
}
