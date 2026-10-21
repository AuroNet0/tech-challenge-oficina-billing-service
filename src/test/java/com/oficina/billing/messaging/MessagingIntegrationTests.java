package com.oficina.billing.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.jayway.jsonpath.JsonPath;
import com.oficina.billing.application.message.MessageEnvelope;
import com.oficina.billing.application.port.out.*;
import com.oficina.billing.application.usecase.CommandHandler;
import com.oficina.billing.domain.enums.*;
import com.oficina.billing.domain.model.*;
import com.oficina.billing.infrastructure.messaging.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import static com.oficina.billing.TestFixtures.*;
import static com.oficina.billing.messaging.MessagingFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = {"spring.security.user.name=billing-test", "spring.security.user.password=test-password",
        "billing.messaging.enabled=true", "billing.messaging.max-deliveries=3", "billing.messaging.retry-delay=100ms",
        "billing.messaging.confirm-timeout=1s", "billing.outbox.enabled=false",
        "billing.outbox.initial-backoff=100ms", "billing.outbox.max-backoff=300ms", "billing.outbox.max-attempts=3"})
@AutoConfigureMockMvc
class MessagingIntegrationTests {
    private static final String OBSERVER = "billing-test-os-events";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("oficina_billing").withUsername("billing").withPassword("test-password");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine")
            .withAdminUser("billing").withAdminPassword("test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "billing");
        registry.add("spring.rabbitmq.password", () -> "test-password");
    }

    @Autowired private RabbitTemplate rabbit;
    @Autowired private RabbitListenerEndpointRegistry listeners;
    @Autowired private RabbitMessageBrokerAdapter broker;
    @Autowired private ObjectMapper mapper;
    @Autowired private MockMvc mvc;
    @Autowired private OrcamentoRepositoryPort orcamentos;
    @Autowired private AprovacaoRepositoryPort aprovacoes;
    @Autowired private PagamentoRepositoryPort pagamentos;
    @MockitoSpyBean private InboxRepositoryPort inbox;
    @MockitoSpyBean private OutboxRepositoryPort outbox;
    @MockitoSpyBean private CommandHandler handler;
    @Autowired private OutboxPublisher publisher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    private RabbitAdmin admin;
    private OutboxRepositoryPort outboxTarget;
    private InboxRepositoryPort inboxTarget;

    @BeforeEach
    void clean() {
        listeners.stop();
        outboxTarget = AopTestUtils.getUltimateTargetObject(outbox);
        inboxTarget = AopTestUtils.getUltimateTargetObject(inbox);
        admin = new RabbitAdmin(rabbit.getConnectionFactory());
        admin.initialize();
        admin.declareQueue(new Queue(OBSERVER, true));
        admin.declareBinding(observerBinding());
        for (var queue : List.of(OBSERVER, "billing.commands.dlq", "billing.quote.create", "billing.payment.request",
                "billing.quote.create.retry", "billing.payment.request.retry")) {
            admin.purgeQueue(queue, true);
        }
        jdbc.execute("TRUNCATE outbox_events, inbox_messages, pagamentos_tentativas, pagamentos, aprovacoes, orcamento_itens, orcamentos CASCADE");
        listeners.start();
    }

    @Test
    void consumesRealQuoteAndPublishesOsCompatibleJsonWithPersistedQuoteId() throws Exception {
        var command = quote();
        send(command);
        awaitBudget(command);
        var budget = orcamentos.findByOsId(command.osId()).orElseThrow();
        assertThat(budget.itens().getFirst().quantidade().toPlainString()).isEqualTo("1.2500");
        assertThat(budget.valorTotal().toPlainString()).isEqualTo("100.00");
        assertThat(jdbc.queryForObject("SELECT status FROM inbox_messages WHERE event_id = ?", String.class, command.eventId()))
                .isEqualTo("PROCESSED");
        assertThat(publisher.publishOne()).isTrue();
        var message = receiveEvent();
        var root = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(root.get("occurredAt").isString()).isTrue();
        var contract = mapper.treeToValue(root, OsEnvelope.class);
        assertThat(contract.eventType()).isEqualTo("quote.created.v1");
        assertThat(contract.occurredAt()).isNotNull();
        assertThat(contract.osId()).isEqualTo(command.osId());
        assertThat(contract.sagaId()).isEqualTo(command.sagaId());
        assertThat(contract.correlationId()).isEqualTo(command.correlationId());
        assertThat(contract.source()).isEqualTo("billing-service");
        assertThat(contract.payload().get("quoteId")).isEqualTo(budget.id().toString());
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("quote.created.v1");
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(contract.eventId().toString());
        assertThat(count("outbox_events", "status = 'PUBLISHED'")).isEqualTo(1);
    }

    @Test
    void approvalRestUsesOriginalSagaCorrelationAndPublishesOnlyOneEvent() throws Exception {
        var command = quote();
        send(command);
        awaitBudget(command);
        publisher.publishOne();
        receiveEvent();
        var budget = orcamentos.findByOsId(command.osId()).orElseThrow();
        mvc.perform(post("/api/v1/orcamentos/{id}/aprovar", budget.id()).with(httpBasic("billing-test", "test-password"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.correlationId").value(command.correlationId().toString()));
        publisher.publishOne();
        var event = read(receiveEvent());
        assertThat(event.eventType()).isEqualTo("quote.approved.v1");
        assertThat(event.payload()).isEmpty();
        assertThat(event.correlationId()).isEqualTo(command.correlationId());
        mvc.perform(post("/api/v1/orcamentos/{id}/aprovar", budget.id()).with(httpBasic("billing-test", "test-password"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        assertThat(count("outbox_events", "true")).isEqualTo(2);
        assertThat(publisher.publishOne()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Valor acima do esperado"})
    void rejectionPublishesNonEmptyReasonAndPreservesRestCorrelation(String reason) throws Exception {
        var command = quote();
        send(command);
        awaitBudget(command);
        publisher.publishOne();
        receiveEvent();
        var budget = orcamentos.findByOsId(command.osId()).orElseThrow();
        var correlation = UUID.randomUUID();
        String request = reason.isEmpty() ? "{}" : mapper.writeValueAsString(Map.of("motivo", reason));
        mvc.perform(post("/api/v1/orcamentos/{id}/rejeitar", budget.id()).with(httpBasic("billing-test", "test-password"))
                        .with(csrf()).header("X-Correlation-Id", correlation)
                        .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk());
        publisher.publishOne();
        var event = read(receiveEvent());
        assertThat(event.eventType()).isEqualTo("quote.rejected.v1");
        assertThat((String) event.payload().get("reason")).isNotBlank();
        assertThat(event.payload().get("reason")).isEqualTo(reason.isEmpty() ? "Orcamento recusado pelo solicitante." : reason);
        assertThat(event.correlationId()).isEqualTo(correlation);
        assertThat(aprovacoes.findByOrcamentoId(budget.id()).orElseThrow().correlationId()).isEqualTo(correlation);
    }

    @Test
    void paymentRequestCreatesOnlyPendingIntentAndStableInitialAttempt() throws Exception {
        var quote = quote();
        send(quote);
        awaitBudget(quote);
        var budget = orcamentos.findByOsId(quote.osId()).orElseThrow();
        approve(budget.id());
        String repair = UUID.randomUUID().toString();
        var command = command("payment.request.v1", quote.osId(), quote.sagaId(), Map.of("repairId", repair));
        send(command);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(pagamentos.findByOrcamentoId(budget.id())).isPresent());
        var payment = pagamentos.findByOrcamentoId(budget.id()).orElseThrow();
        String key = payment.idempotencyKey();
        assertThat(payment.status()).isEqualTo(PagamentoStatus.PENDING);
        assertThat(payment.valor()).isEqualTo(budget.valorTotal());
        assertThat(payment.confirmedAt()).isNull();
        assertThat(payment.failedAt()).isNull();
        assertThat(payment.providerPaymentId()).isNull();
        assertThat(payment.tentativas()).hasSize(1);
        send(command);
        var duplicate = command("payment.request.v1", quote.osId(), quote.sagaId(), Map.of("repairId", repair));
        send(duplicate);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("inbox_messages", "event_type = 'payment.request.v1' AND status = 'PROCESSED'")).isEqualTo(2));
        assertThat(count("pagamentos", "true")).isEqualTo(1);
        assertThat(count("pagamentos_tentativas", "true")).isEqualTo(1);
        assertThat(pagamentos.findByOrcamentoId(budget.id()).orElseThrow().idempotencyKey()).isEqualTo(key);
        assertThat(count("outbox_events", "event_type LIKE 'payment.%'")).isZero();
        var conflicting = command("payment.request.v1", quote.osId(), quote.sagaId(),
                Map.of("repairId", UUID.randomUUID().toString()));
        send(conflicting);
        assertThat(receiveDlq()).isNotNull();
        assertThat(count("pagamentos", "true")).isEqualTo(1);
    }

    @Test
    void concurrentConsumersDeduplicateBothSameAndDifferentEventIds() throws Exception {
        var command = quote();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> send(command));
            var second = executor.submit(() -> send(command));
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        awaitBudget(command);
        verify(handler, timeout(10000).atLeast(2)).handle(any(), any());
        var duplicate = command("quote.create.v1", command.osId(), command.sagaId(), command.payload());
        send(duplicate);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count("inbox_messages", "status = 'PROCESSED'")).isEqualTo(2));
        assertThat(count("orcamentos", "true")).isEqualTo(1);
        assertThat(count("outbox_events", "true")).isEqualTo(1);
    }

    @Test
    void incompatibleEventIdReuseGoesToDlqWithoutChangingOriginalInbox() throws Exception {
        var command = quote();
        send(command);
        awaitBudget(command);
        var conflict = new MessageEnvelope(command.eventId(), command.eventType(), command.occurredAt(),
                command.correlationId(), command.sagaId(), command.osId(), command.source(), quotePayload("1.2500", "90.00"));
        send(conflict);
        assertThat(receiveDlq()).isNotNull();
        assertThat(count("inbox_messages", "status = 'PROCESSED'")).isEqualTo(1);
        assertThat(count("outbox_events", "true")).isEqualTo(1);
        assertThat(orcamentos.findByOsId(command.osId()).orElseThrow().valorTotal().toPlainString()).isEqualTo("100.00");
    }

    @Test
    void wrongSagaIsRejectedWithoutPublishingFailureAgainstAnotherSaga() throws Exception {
        var command = quote();
        send(command);
        awaitBudget(command);
        send(command("quote.create.v1", command.osId(), UUID.randomUUID(), command.payload()));
        receiveDlq();
        assertThat(count("inbox_messages", "status = 'REJECTED'")).isEqualTo(1);
        assertThat(count("outbox_events", "true")).isEqualTo(1);
    }

    @Test
    void semanticInvalidQuoteHasAtomicFailureOutboxAndDlq() throws Exception {
        var command = command("quote.create.v1", UUID.randomUUID(), UUID.randomUUID(), quotePayload("0", "80.00"));
        send(command);
        receiveDlq();
        assertThat(count("orcamentos", "true")).isZero();
        assertThat(count("inbox_messages", "status = 'REJECTED'")).isEqualTo(1);
        publisher.publishOne();
        var event = read(receiveEvent());
        assertThat(event.eventType()).isEqualTo("quote.creation-failed.v1");
        assertThat((String) event.payload().get("reason")).isNotBlank();
    }

    @Test
    void numericOccurredAtIsRejectedBeforeCreatingInbox() {
        var command = quote();
        String raw = body(command).replace("\"" + NOW + "\"", "123");
        sendRaw(command.eventType(), raw);
        receiveDlq();
        assertThat(count("inbox_messages", "true")).isZero();
        assertThat(count("outbox_events", "true")).isZero();
    }

    @Test
    void unapprovedPaymentGoesToDlqWithoutSimulatedProviderFailure() throws Exception {
        var quote = quote();
        send(quote);
        awaitBudget(quote);
        send(command("payment.request.v1", quote.osId(), quote.sagaId(), Map.of("repairId", UUID.randomUUID().toString())));
        receiveDlq();
        assertThat(count("pagamentos", "true")).isZero();
        assertThat(count("outbox_events", "event_type LIKE 'payment.%'")).isZero();
    }

    @Test
    void quoteCreationRollsBackInboxItemsAndOutboxOnWriteFailure() {
        var command = quote();
        doThrow(new IllegalStateException("outbox unavailable")).when(outboxTarget).insert(any());
        assertThatThrownBy(() -> handler.handle(command, body(command)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(count("inbox_messages", "true")).isZero();
        assertThat(count("orcamentos", "true")).isZero();
        assertThat(count("orcamento_itens", "true")).isZero();
        assertThat(count("outbox_events", "true")).isZero();
    }

    @Test
    void decisionRollsBackWhenOutboxCannotBePersisted() throws Exception {
        var budget = orcamentos.save(orcamento());
        doThrow(new IllegalStateException("outbox unavailable")).when(outboxTarget).insert(any());
        mvc.perform(post("/api/v1/orcamentos/{id}/aprovar", budget.id()).with(httpBasic("billing-test", "test-password"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError());
        assertThat(orcamentos.findById(budget.id()).orElseThrow().status()).isEqualTo(OrcamentoStatus.PENDING_APPROVAL);
        assertThat(aprovacoes.findByOrcamentoId(budget.id())).isEmpty();
        assertThat(count("outbox_events", "true")).isZero();
    }

    @Test
    void paymentAndAttemptRollBackWithInboxWhenFinalizationFails() throws Exception {
        var budget = approvedBudget();
        var command = command("payment.request.v1", budget.osId(), budget.sagaId(), Map.of("repairId", "repair-1"));
        doThrow(new IllegalStateException("inbox unavailable")).when(inboxTarget).finish(any(), any(), any(), any());
        assertThatThrownBy(() -> handler.handle(command, body(command)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(count("inbox_messages", "true")).isZero();
        assertThat(count("pagamentos", "true")).isZero();
        assertThat(count("pagamentos_tentativas", "true")).isZero();
    }

    @Test
    void transientFailureRetriesAndCommitsExactlyOneSetOfEffects() throws Exception {
        doThrow(new IllegalStateException("temporary")).doCallRealMethod().when(outboxTarget).insert(any());
        var command = quote();
        send(command);
        awaitBudget(command);
        assertThat(count("inbox_messages", "status = 'PROCESSED'")).isEqualTo(1);
        assertThat(count("outbox_events", "true")).isEqualTo(1);
        verify(outboxTarget, timeout(10000).times(2)).insert(any());
        assertThat(rabbit.receive("billing.commands.dlq", 100)).isNull();
    }

    @Test
    void transientFailuresAreBoundedAndEventuallySentToDlq() {
        doThrow(new IllegalStateException("persistent outage")).when(outboxTarget).insert(any());
        send(quote());
        var dead = receiveDlq();
        assertThat(((Number) dead.getMessageProperties().getHeader("billing-attempt")).intValue()).isEqualTo(3);
        verify(outboxTarget, timeout(10000).times(3)).insert(any());
        assertThat(count("inbox_messages", "true")).isZero();
        assertThat(count("orcamentos", "true")).isZero();
    }

    @Test
    void concurrentOutboxPublishersDoNotAcquireTheSameLiveLease() throws Exception {
        var first = quote();
        var second = quote();
        handler.handle(first, body(first));
        handler.handle(second, body(second));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var workerOne = executor.submit(publisher::publishOne);
            var workerTwo = executor.submit(publisher::publishOne);
            assertThat(workerOne.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(workerTwo.get(20, TimeUnit.SECONDS)).isTrue();
        }
        var eventIds = Set.of(read(receiveEvent()).eventId(), read(receiveEvent()).eventId());
        assertThat(eventIds).hasSize(2);
        assertThat(count("outbox_events", "status = 'PUBLISHED'")).isEqualTo(2);
    }

    @Test
    void expiredLeaseIsRecoveredAndOldWorkerCannotUpdateNewLease() throws Exception {
        var command = quote();
        handler.handle(command, body(command));
        var budget = orcamentos.findByOsId(command.osId()).orElseThrow();
        approve(budget.id());
        Instant start = Instant.now().plusSeconds(1);
        var first = outbox.acquire(10, start, Duration.ofSeconds(10), 3);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().eventType()).isEqualTo("quote.created.v1");
        assertThat(outbox.acquire(10, start.plusSeconds(1), Duration.ofSeconds(10), 3)).isEmpty();
        var renewed = outbox.acquire(10, start.plusSeconds(11), Duration.ofSeconds(10), 3).getFirst();
        assertThat(renewed.eventId()).isEqualTo(first.getFirst().eventId());
        assertThat(renewed.leaseToken()).isNotEqualTo(first.getFirst().leaseToken());
        assertThat(renewed.attempts()).isEqualTo(2);
        assertThat(outbox.markPublished(renewed.eventId(), first.getFirst().leaseToken(), start.plusSeconds(12))).isFalse();
        assertThat(outbox.markAttemptFailed(first.getFirst(), start.plusSeconds(12), 3, Duration.ZERO, "stale")).isFalse();
        assertThat(outbox.markPublished(renewed.eventId(), renewed.leaseToken(), start.plusSeconds(12))).isTrue();
        var decision = outbox.acquire(10, start.plusSeconds(12), Duration.ofSeconds(10), 3).getFirst();
        assertThat(decision.eventType()).isEqualTo("quote.approved.v1");
        assertThat(decision.sequence()).isGreaterThan(renewed.sequence());
    }

    @Test
    void exhaustedExpiredLeaseBecomesFailedAndBlocksFollowingEventsForOs() throws Exception {
        var command = quote();
        handler.handle(command, body(command));
        approve(orcamentos.findByOsId(command.osId()).orElseThrow().id());
        Instant start = Instant.now().plusSeconds(1);
        outbox.acquire(10, start, Duration.ofSeconds(1), 1);
        assertThat(outbox.acquire(10, start.plusSeconds(2), Duration.ofSeconds(1), 1)).isEmpty();
        assertThat(count("outbox_events", "status = 'FAILED'")).isEqualTo(1);
        assertThat(count("outbox_events", "status = 'PENDING'")).isEqualTo(1);
    }

    @Test
    void unroutablePublicationRetriesWithSameEventIdAndPayload() {
        var command = quote();
        handler.handle(command, body(command));
        String original = jdbc.queryForObject("SELECT payload FROM outbox_events", String.class);
        admin.removeBinding(observerBinding());
        try {
            assertThat(publisher.publishOne()).isTrue();
            assertThat(count("outbox_events", "status = 'PENDING' AND attempts = 1")).isEqualTo(1);
        } finally {
            admin.declareBinding(observerBinding());
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            publisher.publishOne();
            assertThat(count("outbox_events", "status = 'PUBLISHED'")).isEqualTo(1);
        });
        var event = receiveEvent();
        assertThat(new String(event.getBody(), StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    void repeatedUnroutablePublicationEventuallyBecomesFailed() {
        var command = quote();
        handler.handle(command, body(command));
        admin.removeBinding(observerBinding());
        try {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                publisher.publishOne();
                assertThat(count("outbox_events", "status = 'FAILED' AND attempts = 3")).isEqualTo(1);
            });
        } finally {
            admin.declareBinding(observerBinding());
        }
        assertThat(count("outbox_events", "true")).isEqualTo(1);
    }

    @Test
    void rabbitOutagePreservesOutboxAndRecoversPublishingAndConsumption() throws Exception {
        var command = quote();
        handler.handle(command, body(command));
        assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        try {
            publisher.publishOne();
            assertThat(count("outbox_events", "status = 'PENDING'")).isEqualTo(1);
        } finally {
            assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            publisher.publishOne();
            assertThat(count("outbox_events", "status = 'PUBLISHED'")).isEqualTo(1);
        });
        assertThat(read(receiveEvent()).osId()).isEqualTo(command.osId());
        var afterRecovery = quote();
        send(afterRecovery);
        awaitBudget(afterRecovery);
        assertThat(count("orcamentos", "true")).isEqualTo(2);
    }

    private Binding observerBinding() {
        return new Binding(OBSERVER, Binding.DestinationType.QUEUE, RabbitMessagingConfig.EVENTS, "#.v1", null);
    }

    private void send(MessageEnvelope command) {
        sendRaw(command.eventType(), body(command));
    }

    private void sendRaw(String routingKey, String raw) {
        broker.send(RabbitMessagingConfig.COMMANDS, routingKey, new Message(raw.getBytes(StandardCharsets.UTF_8), new MessageProperties()));
    }

    private void awaitBudget(MessageEnvelope command) {
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(orcamentos.findByOsId(command.osId())).isPresent());
    }

    private Message receiveEvent() {
        var result = rabbit.receive(OBSERVER, 10000);
        assertThat(result).isNotNull();
        return result;
    }

    private Message receiveDlq() {
        var result = rabbit.receive("billing.commands.dlq", 20000);
        assertThat(result).isNotNull();
        return result;
    }

    private OsEnvelope read(Message message) {
        return mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), OsEnvelope.class);
    }

    private int count(String table, String condition) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + condition, Integer.class);
    }

    private void approve(UUID id) throws Exception {
        mvc.perform(post("/api/v1/orcamentos/{id}/aprovar", id).with(httpBasic("billing-test", "test-password"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
    }

    private Orcamento approvedBudget() {
        var pending = orcamentos.save(orcamento());
        return new TransactionTemplate(transactionManager).execute(status -> {
            pending.aprovar(NOW.plusSeconds(1));
            var saved = orcamentos.save(pending);
            aprovacoes.save(Aprovacao.registrar(saved, null, null, null, NOW.plusSeconds(1)));
            return saved;
        });
    }

    public record OsEnvelope(UUID eventId, String eventType, Instant occurredAt, UUID correlationId,
                             UUID sagaId, UUID osId, String source, Map<String, Object> payload) {
    }
}
