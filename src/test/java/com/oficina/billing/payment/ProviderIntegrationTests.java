package com.oficina.billing.payment;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.oficina.billing.application.payment.*;
import com.oficina.billing.application.payment.ProviderData.*;
import com.oficina.billing.application.port.in.CheckoutPort;
import com.oficina.billing.application.port.out.*;
import com.oficina.billing.domain.enums.PagamentoStatus;
import com.oficina.billing.domain.model.*;
import com.oficina.billing.infrastructure.messaging.OutboxPublisher;
import com.oficina.billing.infrastructure.payment.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import static com.oficina.billing.TestFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = {"spring.security.user.name=billing-test", "spring.security.user.password=test-password",
        "spring.security.user.roles=BILLING_OPERATOR", "billing.mercado-pago.enabled=true",
        "billing.mercado-pago.allow-insecure-local=true", "billing.mercado-pago.access-token=TEST-integration-only",
        "billing.mercado-pago.webhook-secret=" + ProviderUnitTests.SECRET,
        "billing.mercado-pago.notification-url=https://example.test/webhook", "billing.mercado-pago.success-url=https://example.test/success",
        "billing.mercado-pago.failure-url=https://example.test/failure", "billing.mercado-pago.pending-url=https://example.test/pending",
        "billing.mercado-pago.http-timeout=2s", "billing.mercado-pago.worker-delay=1h", "billing.outbox.enabled=false"})
@AutoConfigureMockMvc
class ProviderIntegrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine")
            .withAdminUser("billing").withAdminPassword("test-password");
    static final ProviderTestServer SERVER = new ProviderTestServer();
    static final List<Map<String, Object>> EVIDENCE = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "billing");
        registry.add("spring.rabbitmq.password", () -> "test-password");
        registry.add("billing.mercado-pago.api-base-url", SERVER::url);
    }

    @Autowired CheckoutPort checkout;
    @Autowired PaymentWorkflow worker;
    @Autowired ProviderTransactions transactions;
    @Autowired PagamentoRepositoryPort payments;
    @Autowired OrcamentoRepositoryPort quotes;
    @Autowired AprovacaoRepositoryPort decisions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired OutboxPublisher publisher;
    @Autowired RabbitTemplate rabbit;
    @MockitoSpyBean MercadoPagoPaymentProviderAdapter provider;
    @MockitoSpyBean OutboxRepositoryPort outbox;
    @MockitoSpyBean ProviderWorkflowPort state;
    private Pagamento initial;
    private TransactionTemplate transaction;

    @BeforeEach
    void prepare() {
        SERVER.reset();
        jdbc.execute("TRUNCATE provider_notifications,outbox_events,inbox_messages,pagamentos_tentativas,pagamentos,aprovacoes,orcamento_itens,orcamentos CASCADE");
        transaction = new TransactionTemplate(transactionManager);
        initial = transaction.execute(status -> {
            var quote = quotes.save(orcamento());
            quote.aprovar(NOW.plusSeconds(1));
            quote = quotes.save(quote);
            decisions.save(Aprovacao.registrar(quote, null, null, UUID.randomUUID(), quote.approvedAt()));
            return payments.save(Pagamento.criar(quote, "MERCADO_PAGO", "billing:" + quote.id(), UUID.randomUUID().toString(), NOW.plusSeconds(2)));
        });
        var admin = new RabbitAdmin(rabbit);
        admin.declareQueue(new org.springframework.amqp.core.Queue("payment.observer", true));
        admin.declareBinding(new Binding("payment.observer", Binding.DestinationType.QUEUE, "oficina.events", "payment.#", null));
        admin.purgeQueue("payment.observer", false);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(provider).createPreference(any());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(provider).searchPayments(any());
    }

    @AfterAll
    static void closeServer() throws Exception {
        SERVER.close();
        Files.writeString(Path.of("target", "provider-http-evidence.json"),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
                        "provider", "local JDK HttpServer simulator", "officialProviderCalled", false, "cases", EVIDENCE)));
    }

    @AfterEach
    void recordEvidence(TestInfo info) {
        EVIDENCE.add(Map.of("case", info.getDisplayName(), "preferencePostCount", SERVER.creates.get(),
                "httpCalls", SERVER.requests.stream().map(request -> Map.of("method", request.method(), "path", request.path())).toList(),
                "paymentStatus", payments.findById(initial.id()).orElseThrow().status().name(),
                "outboxTypes", jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY sequence", String.class)));
    }

    @Test
    void createsOnlyOnePreferenceAndAnAuthenticatedCheckoutWithoutFinancialInput() throws Exception {
        var response = checkout.iniciar(initial.id());
        assertThat(response.checkoutStatus()).isEqualTo("READY");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(checkout.iniciar(initial.id())).isEqualTo(response);
        assertThat(SERVER.creates.get()).isEqualTo(1);
        assertThat(payments.findById(initial.id()).orElseThrow().status()).isEqualTo(PagamentoStatus.PENDING);
        assertThat(count("outbox_events")).isZero();
        var posted = SERVER.requests.stream().filter(request -> request.method().equals("POST")).findFirst().orElseThrow();
        assertThat(posted.body()).contains(initial.externalReference()).contains(initial.idempotencyKey());
        assertThat(posted.authorization()).isEqualTo("Bearer TEST-integration-only");
        mvc.perform(post(checkoutPath()).with(httpBasic("billing-test", "test-password")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preferenceId").value(response.preferenceId()));
        mvc.perform(post(checkoutPath()).with(user("ordinary").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(checkoutPath()).with(user("operator").roles("BILLING_OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(post(checkoutPath()).with(user("operator").roles("BILLING_OPERATOR")).with(csrf())
                        .contentType("application/json").content("{\"valor\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentCheckoutCallsCannotCreateTwoPreferences() throws Exception {
        SERVER.creationStarted = new CountDownLatch(1);
        SERVER.continueCreation = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> checkout.iniciar(initial.id()));
            assertThat(SERVER.creationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> checkout.iniciar(initial.id())).isInstanceOf(ConcurrencyFailureException.class);
            SERVER.continueCreation.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).checkoutStatus()).isEqualTo("READY");
        }
        assertThat(SERVER.creates.get()).isEqualTo(1);
    }

    @Test
    void recoversPreferenceAfterUnknownHttpResultWithoutRepeatingPost() {
        SERVER.createDelay = 2500;
        assertThat(checkout.iniciar(initial.id()).checkoutStatus()).isEqualTo("UNKNOWN");
        SERVER.createDelay = 0;
        assertThat(checkout.iniciar(initial.id()).checkoutStatus()).isEqualTo("READY");
        assertThat(SERVER.creates.get()).isEqualTo(1);
        assertThat(count("provider_checkouts")).isEqualTo(1);
    }

    @Test
    void unknownWithoutVisiblePreferenceStaysUnknownAndNeverRetriesCreation() {
        SERVER.errors.put("/checkout/preferences", 500);
        assertThat(checkout.iniciar(initial.id()).checkoutStatus()).isEqualTo("UNKNOWN");
        SERVER.errors.clear();
        assertThat(checkout.iniciar(initial.id()).checkoutStatus()).isEqualTo("UNKNOWN");
        assertThat(SERVER.requests.stream().filter(request -> request.method().equals("POST")).count()).isEqualTo(1);
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void webhookIsDurableAuthenticatedAndCannotConfirmFromItsBody() throws Exception {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), "pending", Instant.now());
        notify("1001", "same-request");
        notify("1001", "same-request");
        assertThat(count("provider_notifications")).isEqualTo(1);
        assertThat(payments.findById(initial.id()).orElseThrow().status()).isEqualTo(PagamentoStatus.PENDING);
        assertThat(worker.processNotification()).isTrue();
        assertThat(count("outbox_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM provider_notifications", String.class)).isEqualTo("PROCESSED");
        mvc.perform(post("/api/v1/webhooks/mercado-pago?data.id=1001").contentType("application/json")
                .content("{\"type\":\"payment\",\"data\":{\"id\":\"1001\"}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signedWebhookConfirmsAtomicallyAndPublishesTheRealOutboxViaRabbit() throws Exception {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), "approved", Instant.now());
        notify("1001", "first");
        assertThat(worker.processNotification()).isTrue();
        var paid = payments.findById(initial.id()).orElseThrow();
        assertThat(paid.status()).isEqualTo(PagamentoStatus.CONFIRMED);
        assertThat(paid.providerPaymentId()).isEqualTo("1001");
        assertThat(paid.tentativas().getFirst().status()).isEqualTo(PagamentoStatus.CONFIRMED);
        assertThat(eventCount("payment.confirmed.v1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM provider_notifications", String.class)).isEqualTo("PROCESSED");
        assertThat(publisher.publishOne()).isTrue();
        Message message = rabbit.receive("payment.observer", 5000);
        assertThat(message).isNotNull();
        var envelope = mapper.readTree(message.getBody());
        assertThat(envelope.get("occurredAt").isString()).isTrue();
        assertThat(envelope.get("source").asString()).isEqualTo("billing-service");
        assertThat(envelope.get("payload").get("paymentId").asString()).isEqualTo(initial.id().toString());
        assertThat(envelope.get("osId").asString()).isEqualTo(initial.osId().toString());
        assertThat(envelope.get("sagaId").asString()).isEqualTo(initial.sagaId().toString());
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("payment.confirmed.v1");
        assertThat(jdbc.queryForObject("SELECT state FROM provider_checkouts", String.class)).isEqualTo("CLOSED");
        notify("1001", "duplicate"); worker.processNotification();
        assertThat(eventCount("payment.confirmed.v1")).isEqualTo(1);
        assertThat(payments.findById(initial.id()).orElseThrow().version()).isEqualTo(paid.version());
        assertThatThrownBy(() -> checkout.iniciar(initial.id())).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rejected", "cancelled"})
    void confirmedFinancialFailureCreatesOnlyOneFailedEvent(String status) throws Exception {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), status, Instant.now());
        notify("1001", "first"); worker.processNotification();
        notify("1001", "second"); worker.processNotification();
        assertThat(payments.findById(initial.id()).orElseThrow().status()).isEqualTo(PagamentoStatus.FAILED);
        assertThat(eventCount("payment.failed.v1")).isEqualTo(1);
        assertThat(publisher.publishOne()).isTrue();
        Message message = rabbit.receive("payment.observer", 5000);
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("payment.failed.v1");
        assertThat(mapper.readTree(message.getBody()).get("payload").get("reason").asString()).isNotBlank();
        assertThat(eventCount("payment.confirmed.v1")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"in_process", "authorized", "pending"})
    void pendingAndProcessingNeverEmitFinancialResults(String status) throws Exception {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), status, Instant.now());
        notify("1001", "pending"); worker.processNotification();
        assertThat(payments.findById(initial.id()).orElseThrow().status())
                .isEqualTo(status.equals("pending") ? PagamentoStatus.PENDING : PagamentoStatus.PROCESSING);
        assertThat(count("outbox_events")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "refunded", "charged_back", "in_mediation"})
    void unmappedAndDisputedStatesAreAuditedNotConvertedToFailed(String status) throws Exception {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), status, Instant.now());
        notify("1001", "review"); worker.processNotification();
        assertThat(count("provider_observations")).isEqualTo(1);
        assertThat(count("outbox_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT review_reason FROM provider_sync", String.class)).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "currency", "attempt", "live"})
    void mismatchedProviderIdentityCannotConfirm(String mismatch) throws Exception {
        var ready = checkout.iniciar(initial.id());
        var remote = SERVER.payment("1001", ready.preferenceId(), "approved", Instant.now());
        switch (mismatch) {
            case "amount" -> remote.put("transaction_amount", new BigDecimal("149.99"));
            case "currency" -> remote.put("currency_id", "USD");
            case "attempt" -> remote.put("metadata", Map.of("billing_attempt_id", UUID.randomUUID().toString()));
            case "live" -> remote.put("live_mode", true);
            default -> throw new IllegalArgumentException();
        }
        notify("1001", "wrong"); worker.processNotification();
        assertThat(payments.findById(initial.id()).orElseThrow().status()).isEqualTo(PagamentoStatus.PENDING);
        assertThat(count("outbox_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM provider_notifications", String.class)).isEqualTo("REVIEW");
    }

    @Test
    void wrongReferenceIsDurablyHeldForReview() throws Exception {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), "approved", Instant.now()).put("external_reference", "another-reference");
        notify("1001", "wrong-ref"); worker.processNotification();
        assertThat(count("outbox_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error FROM provider_notifications", String.class)).isEqualTo("UNKNOWN_REFERENCE");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500})
    void operationalFailureIsRetriedWithoutEmittingPaymentFailed(int errorCode) throws Exception {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), "approved", Instant.now());
        SERVER.errors.put("/v1/payments/1001", errorCode);
        notify("1001", "temporary"); worker.processNotification();
        assertThat(count("outbox_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM provider_notifications", String.class)).isEqualTo("PENDING");
        SERVER.errors.clear();
        retryNow(); worker.processNotification();
        assertThat(eventCount("payment.confirmed.v1")).isEqualTo(1);
    }

    @Test
    void reconciliationFindsAnApprovalWithoutAnyWebhook() {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), "approved", Instant.now());
        retryNow();
        assertThat(worker.processDue()).isTrue();
        assertThat(eventCount("payment.confirmed.v1")).isEqualTo(1);
        assertThat(count("provider_notifications")).isZero();
    }

    @Test
    void oldRejectedResponseCannotUndoAnApproval() throws Exception {
        var ready = checkout.iniciar(initial.id());
        Instant approvedAt = Instant.now();
        SERVER.payment("1001", ready.preferenceId(), "approved", approvedAt);
        notify("1001", "approved"); worker.processNotification();
        SERVER.payment("1001", ready.preferenceId(), "rejected", approvedAt.minusSeconds(1));
        notify("1001", "old"); worker.processNotification();
        assertThat(payments.findById(initial.id()).orElseThrow().status()).isEqualTo(PagamentoStatus.CONFIRMED);
        assertThat(eventCount("payment.failed.v1")).isZero();
        assertThat(eventCount("payment.confirmed.v1")).isEqualTo(1);
    }

    @Test
    void lateApprovalOfTheFirstAttemptSettlesTheLogicalPaymentAndPreservesFailureHistory() throws Exception {
        var first = checkout.iniciar(initial.id());
        Instant failedAt = Instant.now().minusSeconds(2);
        SERVER.payment("1001", first.preferenceId(), "rejected", failedAt);
        notify("1001", "failed"); worker.processNotification();
        transaction.executeWithoutResult(status -> {
            var payment = payments.findById(initial.id()).orElseThrow();
            payment.novaTentativa("second-stable-key", Instant.now());
            payments.save(payment);
        });
        var second = checkout.iniciar(initial.id());
        assertThat(second.preferenceId()).isNotEqualTo(first.preferenceId());
        SERVER.payment("1001", first.preferenceId(), "approved", failedAt.plusSeconds(1));
        notify("1001", "late"); worker.processNotification();
        var payment = payments.findById(initial.id()).orElseThrow();
        assertThat(payment.status()).isEqualTo(PagamentoStatus.CONFIRMED);
        assertThat(payment.providerPaymentId()).isEqualTo("1001");
        assertThat(payment.tentativas().getFirst().status()).isEqualTo(PagamentoStatus.CONFIRMED);
        assertThat(payment.tentativas().getLast().status()).isEqualTo(PagamentoStatus.FAILED);
        assertThat(eventCount("payment.confirmed.v1")).isEqualTo(1);
        assertThat(eventCount("payment.failed.v1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT previous_attempt->>'status' FROM provider_observations WHERE status='LATE_SETTLEMENT'", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_checkouts WHERE state='CLOSED'", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> checkout.iniciar(initial.id())).isInstanceOf(RuntimeException.class);
        SERVER.payment("1002", second.preferenceId(), "approved", Instant.now());
        notify("1002", "duplicate-financial"); worker.processNotification();
        assertThat(jdbc.queryForObject("SELECT review_reason FROM provider_sync", String.class)).isEqualTo("MULTIPLE_EXTERNAL_SETTLEMENTS");
        assertThat(eventCount("payment.confirmed.v1")).isEqualTo(1);
    }

    @Test
    void twoExternalApprovalsRequireReviewRatherThanAnIncorrectSagaResult() throws Exception {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), "approved", Instant.now());
        SERVER.payment("1002", ready.preferenceId(), "approved", Instant.now());
        notify("1001", "two"); worker.processNotification();
        assertThat(payments.findById(initial.id()).orElseThrow().status()).isEqualTo(PagamentoStatus.PENDING);
        assertThat(count("outbox_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT review_reason FROM provider_sync", String.class)).isEqualTo("MULTIPLE_EXTERNAL_SETTLEMENTS");
    }

    @Test
    void outboxFailureRollsBackPaymentAttemptObservationAndNotificationProcessing() throws Exception {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), "approved", Instant.now());
        OutboxRepositoryPort target = AopTestUtils.getUltimateTargetObject(outbox);
        doThrow(new DataAccessResourceFailureException("simulated outbox failure")).when(target).insert(any());
        notify("1001", "atomicity"); worker.processNotification();
        assertThat(payments.findById(initial.id()).orElseThrow().status()).isEqualTo(PagamentoStatus.PENDING);
        assertThat(count("provider_payments")).isZero();
        assertThat(count("provider_observations")).isZero();
        assertThat(count("outbox_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM provider_notifications", String.class)).isEqualTo("PENDING");
        reset(target); retryNow(); worker.processNotification();
        assertThat(eventCount("payment.confirmed.v1")).isEqualTo(1);
    }

    @Test
    void staleLeaseCannotFinalizeAnotherWorkersCheckout() {
        var old = transactions.reserveSync(initial.id());
        jdbc.update("UPDATE provider_sync SET locked_until=now()-interval '1 second' WHERE payment_id=?", initial.id());
        var current = transactions.reserveSync(initial.id());
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThatThrownBy(() -> transactions.checkouts(old)).isInstanceOf(ConcurrencyFailureException.class);
        transactions.finish(old, "old-worker");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM provider_sync", UUID.class)).isEqualTo(current.token());
    }

    @Test
    void concurrentWebhookAndReconciliationHaveOnlyOneFinancialEffect() throws Exception {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), "approved", Instant.now());
        notify("1001", "race"); retryNow();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new CyclicBarrier(2);
            var first = executor.submit(() -> { barrier.await(); return worker.processNotification(); });
            var second = executor.submit(() -> { barrier.await(); return worker.processDue(); });
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        }
        retryNow(); worker.processNotification();
        assertThat(payments.findById(initial.id()).orElseThrow().status()).isEqualTo(PagamentoStatus.CONFIRMED);
        assertThat(eventCount("payment.confirmed.v1")).isEqualTo(1);
    }

    @Test
    void signedInconsistentBodyAndOldSignatureAreRejectedBeforeDurableReceipt() throws Exception {
        String timestamp = "" + Instant.now().getEpochSecond();
        mvc.perform(post("/api/v1/webhooks/mercado-pago?data.id=1001").header("x-request-id", "body")
                .header("x-signature", ProviderUnitTests.signature("1001", "body", timestamp))
                .contentType("application/json").content("{\"type\":\"payment\",\"data\":{\"id\":\"1002\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/webhooks/mercado-pago?data.id=1001").header("x-request-id", "old")
                .header("x-signature", ProviderUnitTests.signature("1001", "old", "" + Instant.now().minusSeconds(301).getEpochSecond()))
                .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        assertThat(count("provider_notifications")).isZero();
    }

    @Test
    void failureToPersistNotificationNeverAcknowledgesReceipt() throws Exception {
        ProviderWorkflowPort target = AopTestUtils.getUltimateTargetObject(state);
        doThrow(new DataAccessResourceFailureException("simulated receipt failure")).when(target).receive(any(), any(), any(), any());
        String timestamp = "" + Instant.now().getEpochSecond();
        mvc.perform(post("/api/v1/webhooks/mercado-pago?data.id=1001").header("x-request-id", "durable")
                .header("x-signature", ProviderUnitTests.signature("1001", "durable", timestamp))
                .contentType("application/json").content("{\"type\":\"payment\",\"data\":{\"id\":\"1001\"}}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.detail").value("Nao foi possivel concluir a requisicao."));
        assertThat(count("provider_notifications")).isZero();
    }

    @Test
    void crashAfterExternalCreationBeforeFinalizationRecoversByReference() {
        var old = transactions.reserveCheckout(initial.id());
        var preference = provider.createPreference(old.request());
        jdbc.update("UPDATE provider_sync SET locked_until=now()-interval '1 second' WHERE payment_id=?", initial.id());
        var recovered = checkout.iniciar(initial.id());
        assertThat(recovered.preferenceId()).isEqualTo(preference.id());
        assertThat(SERVER.creates.get()).isEqualTo(1);
        assertThatThrownBy(() -> transactions.storePreference(old, old.checkout(), preference)).isInstanceOf(ConcurrencyFailureException.class);
    }

    @Test
    void contradictoryStatusesWithSameProviderTimestampRequireReview() throws Exception {
        var ready = checkout.iniciar(initial.id());
        Instant time = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        SERVER.payment("1001", ready.preferenceId(), "pending", time);
        notify("1001", "pending"); worker.processNotification();
        SERVER.payment("1001", ready.preferenceId(), "approved", time);
        notify("1001", "contradiction"); worker.processNotification();
        assertThat(count("outbox_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT review_reason FROM provider_sync", String.class)).isEqualTo("CONTRADICTORY_PROVIDER_RESULT");
    }

    @Test
    void preferenceClosureFailureDoesNotUndoASettlementAndIsReconciled() throws Exception {
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), "approved", Instant.now());
        SERVER.errors.put("/checkout/preferences/" + ready.preferenceId(), 500);
        notify("1001", "approved"); worker.processNotification();
        assertThat(payments.findById(initial.id()).orElseThrow().status()).isEqualTo(PagamentoStatus.CONFIRMED);
        assertThat(jdbc.queryForObject("SELECT state FROM provider_checkouts", String.class)).isEqualTo("CLOSING");
        assertThat(jdbc.queryForObject("SELECT status FROM provider_notifications", String.class)).isEqualTo("PROCESSED");
        SERVER.errors.clear(); retryNow(); worker.processDue();
        assertThat(jdbc.queryForObject("SELECT state FROM provider_checkouts", String.class)).isEqualTo("CLOSED");
        assertThat(eventCount("payment.confirmed.v1")).isEqualTo(1);
    }

    @Test
    void financialEventPreservesPaymentRequestCorrelationFromInbox() throws Exception {
        UUID correlation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inbox_messages(event_id,event_type,os_id,saga_id,correlation_id,payload,status,received_at,processed_at)
                VALUES (?,'payment.request.v1',?,?,?,'{}','PROCESSED',now(),now())
                """, UUID.randomUUID(), initial.osId(), initial.sagaId(), correlation);
        var ready = checkout.iniciar(initial.id());
        SERVER.payment("1001", ready.preferenceId(), "approved", Instant.now());
        notify("1001", "correlation"); worker.processNotification();
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM outbox_events WHERE event_type='payment.confirmed.v1'", UUID.class)).isEqualTo(correlation);
    }

    @Test
    void existingDuplicatePreferencesAreHeldForFinancialReview() {
        var old = transactions.reserveCheckout(initial.id());
        provider.createPreference(old.request());
        provider.createPreference(old.request());
        jdbc.update("UPDATE provider_sync SET locked_until=now()-interval '1 second' WHERE payment_id=?", initial.id());
        assertThatThrownBy(() -> checkout.iniciar(initial.id())).isInstanceOf(ProviderException.class);
        assertThat(jdbc.queryForObject("SELECT review_reason FROM provider_sync", String.class)).isEqualTo("DUPLICATE_PREFERENCES");
        assertThat(count("outbox_events")).isZero();
    }

    private void notify(String id, String requestId) throws Exception {
        String timestamp = "" + Instant.now().getEpochSecond();
        mvc.perform(post("/api/v1/webhooks/mercado-pago?data.id=" + id).header("x-request-id", requestId)
                .header("x-signature", ProviderUnitTests.signature(id, requestId, timestamp))
                .contentType("application/json").content("{\"type\":\"payment\",\"status\":\"approved\",\"data\":{\"id\":\"" + id + "\"}}"))
                .andExpect(status().isOk());
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private int eventCount(String type) { return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type=?", Integer.class, type); }
    private String checkoutPath() { return "/api/v1/pagamentos/" + initial.id() + "/checkout"; }
    private void retryNow() {
        jdbc.update("UPDATE provider_notifications SET next_attempt_at=now() WHERE status='PENDING'");
        jdbc.update("UPDATE provider_sync SET next_sync_at=now()");
    }
}
