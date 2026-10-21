package com.oficina.billing.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.oficina.billing.application.exception.InvalidCommandException;
import com.oficina.billing.application.message.MessageEnvelope;
import com.oficina.billing.application.port.out.*;
import com.oficina.billing.application.usecase.*;
import com.oficina.billing.domain.enums.*;
import com.oficina.billing.domain.model.*;
import com.oficina.billing.infrastructure.messaging.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;
import static com.oficina.billing.TestFixtures.*;
import static com.oficina.billing.messaging.MessagingFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessagingUnitTests {
    @Mock private InboxRepositoryPort inbox;
    @Mock private OutboxRepositoryPort outbox;
    @Mock private OrcamentoRepositoryPort orcamentos;
    @Mock private PagamentoRepositoryPort pagamentos;
    @Mock private EventRecorder events;
    @Mock private MessageBrokerPort broker;
    private CommandHandler handler;
    private final Clock clock = Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        handler = new CommandHandler(inbox, orcamentos, pagamentos, CODEC, events, clock);
        lenient().when(inbox.claim(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void envelopeUsesIso8601EvenWithoutJacksonJavaTimeConfiguration() {
        var command = quote();
        var raw = body(command);
        var root = JsonMapper.builder().build().readTree(raw);
        assertThat(root.get("occurredAt").isString()).isTrue();
        assertThat(root.get("occurredAt").asString()).isEqualTo(command.occurredAt().toString());
        assertThat(CODEC.readCommand(raw, command.eventType())).isEqualTo(command);
        assertThat(CODEC.equivalent(raw, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(root))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "occurredAt", "osId", "sagaId", "correlationId", "source", "payload", "eventType"})
    void rejectsInvalidOrMissingEnvelopeFields(String field) {
        var command = quote();
        var mapper = JsonMapper.builder().build();
        var root = mapper.readTree(body(command)).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) root).remove(field);
        assertThatThrownBy(() -> CODEC.readCommand(mapper.writeValueAsString(root), command.eventType()))
                .isInstanceOf(InvalidCommandException.class);
    }

    @Test
    void rejectsNumericTimeNonCanonicalUuidUnknownCommandAndWrongSource() {
        var command = quote();
        String raw = body(command);
        assertThatThrownBy(() -> CODEC.readCommand(raw.replace("\"" + NOW + "\"", "123"), command.eventType()))
                .isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> CODEC.readCommand(raw.replace(command.eventId().toString(), "1-1-1-1-1"), command.eventType()))
                .isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> CODEC.readCommand(raw.replace("os-service", "execution-service"), command.eventType()))
                .isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> CODEC.readCommand(raw, "payment.request.v1")).isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> CODEC.readCommand("{}", "repair.start.v1")).isInstanceOf(InvalidCommandException.class);
        assertThatThrownBy(() -> CODEC.readCommand("not json", command.eventType())).isInstanceOf(InvalidCommandException.class);
    }

    @Test
    void createsBudgetFromExactDiagnosticDecimalsAndRecordsRealQuoteId() {
        var command = quote();
        when(orcamentos.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(handler.handle(command, body(command))).isEqualTo(InboxStatus.PROCESSED);
        var budget = ArgumentCaptor.forClass(Orcamento.class);
        verify(orcamentos).save(budget.capture());
        assertThat(budget.getValue().itens().getFirst().quantidade().toPlainString()).isEqualTo("1.2500");
        assertThat(budget.getValue().valorTotal().toPlainString()).isEqualTo("100.00");
        verify(events).record(budget.getValue().id(), "Orcamento", command.osId(), command.sagaId(),
                command.correlationId(), "quote.created.v1", Map.of("quoteId", budget.getValue().id().toString()), clock.instant());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.0000001", "not-decimal"})
    void semanticQuoteFailureRecordsFailureEventAndRejectedInbox(String quantity) {
        var command = command("quote.create.v1", UUID.randomUUID(), UUID.randomUUID(), quotePayload(quantity, "80.00"));
        assertThat(handler.handle(command, body(command))).isEqualTo(InboxStatus.REJECTED);
        verify(orcamentos, never()).save(any());
        verify(events).record(command.osId(), "Orcamento", command.osId(), command.sagaId(), command.correlationId(),
                "quote.creation-failed.v1", Map.of("reason", "Dados do diagnostico invalidos para gerar orcamento."), clock.instant());
    }

    @Test
    void duplicateEventIdIsSideEffectFreeButConflictingEnvelopeIsNotIgnored() {
        var command = quote();
        var original = new InboxMessage(command.eventId(), command.eventType(), command.osId(), command.sagaId(),
                command.correlationId(), body(command), InboxStatus.PROCESSED, NOW, NOW, null);
        when(inbox.claim(any())).thenReturn(original);
        assertThat(handler.handle(command, body(command))).isEqualTo(InboxStatus.PROCESSED);
        verifyNoInteractions(orcamentos, pagamentos, events);
        assertThatThrownBy(() -> handler.handle(command, body(command).replace("80.00", "81.00")))
                .isInstanceOf(InvalidCommandException.class);
        verify(inbox, never()).finish(any(), any(), any(), any());
    }

    @Test
    void differentCommandForSameBudgetIsValidatedAndDoesNotRecreateIt() {
        var command = quote();
        var budget = Orcamento.criar(command.osId(), command.sagaId(),
                List.of(item("diagnostic-1", "1.2500", "80.00")), NOW);
        when(orcamentos.findByOsId(command.osId())).thenReturn(Optional.of(budget));
        assertThat(handler.handle(command, body(command))).isEqualTo(InboxStatus.PROCESSED);
        var changed = command("quote.create.v1", command.osId(), command.sagaId(), quotePayload("1.2500", "90.00"));
        assertThat(handler.handle(changed, body(changed))).isEqualTo(InboxStatus.REJECTED);
        var wrongSaga = command("quote.create.v1", command.osId(), UUID.randomUUID(), command.payload());
        assertThat(handler.handle(wrongSaga, body(wrongSaga))).isEqualTo(InboxStatus.REJECTED);
        verify(orcamentos, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void databaseFailurePropagatesWithoutConvertingItIntoBusinessFailure() {
        var command = quote();
        when(orcamentos.save(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> handler.handle(command, body(command))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(events);
        verify(inbox, never()).finish(any(), any(), any(), any());
    }

    @Test
    void createsPendingChargeWithStableKeyWithoutConfirmingOrPublishingPaymentOutcome() {
        var budget = aprovado();
        var command = command("payment.request.v1", budget.osId(), budget.sagaId(), Map.of("repairId", "R-1"));
        when(orcamentos.findByOsId(budget.osId())).thenReturn(Optional.of(budget));
        assertThat(handler.handle(command, body(command))).isEqualTo(InboxStatus.PROCESSED);
        var captured = ArgumentCaptor.forClass(Pagamento.class);
        verify(pagamentos).save(captured.capture());
        var payment = captured.getValue();
        assertThat(payment.status()).isEqualTo(PagamentoStatus.PENDING);
        assertThat(payment.valor()).isEqualTo(budget.valorTotal());
        assertThat(payment.confirmedAt()).isNull();
        assertThat(payment.providerPaymentId()).isNull();
        assertThat(payment.tentativas()).hasSize(1);
        assertThat(UUID.fromString(payment.idempotencyKey())).isNotNull();
        verifyNoInteractions(events);
    }

    @Test
    void repeatedPaymentRequestDoesNotCreateAnotherChargeAndConflictingRepairIsRejected() {
        var budget = aprovado();
        var command = command("payment.request.v1", budget.osId(), budget.sagaId(), Map.of("repairId", "R-1"));
        var payment = Pagamento.criar(budget, "MERCADO_PAGO", "ref", "key", NOW.plusSeconds(2));
        when(orcamentos.findByOsId(budget.osId())).thenReturn(Optional.of(budget));
        when(pagamentos.findByOrcamentoId(budget.id())).thenReturn(Optional.of(payment));
        when(inbox.findOrigin(budget.osId(), budget.sagaId(), "payment.request.v1")).thenReturn(Optional.of(
                new InboxMessage(command.eventId(), command.eventType(), command.osId(), command.sagaId(),
                        command.correlationId(), body(command), InboxStatus.PROCESSED, NOW, NOW, null)));
        assertThat(handler.handle(command, body(command))).isEqualTo(InboxStatus.PROCESSED);
        var conflict = command("payment.request.v1", budget.osId(), budget.sagaId(), Map.of("repairId", "R-2"));
        assertThat(handler.handle(conflict, body(conflict))).isEqualTo(InboxStatus.REJECTED);
        verify(pagamentos, never()).save(any());
    }

    @Test
    void rejectsPaymentWithoutApprovedBudgetOrRepairWithoutPublishingFakeFailure() {
        var budget = orcamento();
        when(orcamentos.findByOsId(budget.osId())).thenReturn(Optional.of(budget));
        var pending = command("payment.request.v1", budget.osId(), budget.sagaId(), Map.of("repairId", "R-1"));
        assertThat(handler.handle(pending, body(pending))).isEqualTo(InboxStatus.REJECTED);
        var missing = command("payment.request.v1", UUID.randomUUID(), UUID.randomUUID(), Map.of());
        assertThat(handler.handle(missing, body(missing))).isEqualTo(InboxStatus.REJECTED);
        verify(pagamentos, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void eventRecorderPreservesSuppliedOriginalAndDeterministicFallbackCorrelation() {
        var command = quote();
        var recorder = new EventRecorder(inbox, outbox, CODEC);
        assertThat(recorder.correlation(command.osId(), command.sagaId(), command.correlationId())).isEqualTo(command.correlationId());
        var fallback = recorder.correlation(command.osId(), command.sagaId(), null);
        assertThat(recorder.correlation(command.osId(), command.sagaId(), null)).isEqualTo(fallback);
        when(inbox.findOrigin(command.osId(), command.sagaId(), "quote.create.v1")).thenReturn(Optional.of(
                new InboxMessage(command.eventId(), command.eventType(), command.osId(), command.sagaId(),
                        command.correlationId(), body(command), InboxStatus.PROCESSED, NOW, NOW, null)));
        assertThat(recorder.correlation(command.osId(), command.sagaId(), null)).isEqualTo(command.correlationId());
        recorder.record(command.osId(), "Orcamento", command.osId(), command.sagaId(), command.correlationId(),
                "quote.approved.v1", Map.of(), clock.instant());
        var event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        var root = JsonMapper.builder().build().readTree(event.getValue().payload());
        assertThat(root.get("source").asString()).isEqualTo("billing-service");
        assertThat(root.get("occurredAt").isString()).isTrue();
        assertThat(root.get("payload").isEmpty()).isTrue();
        assertThat(root.get("eventId").asString()).isEqualTo(event.getValue().eventId().toString());
    }

    @Test
    void publisherUsesBoundedBackoffAndPreservesEventAcrossFailures() {
        var properties = new OutboxProperties(true, 2, Duration.ofMinutes(2), 3,
                Duration.ofMillis(100), Duration.ofMillis(250));
        assertThat(properties.backoff(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(properties.backoff(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(properties.backoff(100)).isEqualTo(Duration.ofMillis(250));
        var command = quote();
        var event = new OutboxEvent(UUID.randomUUID(), command.osId(), "Orcamento", "quote.created.v1",
                command.osId(), command.sagaId(), command.correlationId(), body(command), OutboxStatus.PROCESSING,
                3, NOW, null, NOW, NOW.plusSeconds(60), UUID.randomUUID(), null, 1);
        when(outbox.acquire(1, clock.instant(), properties.leaseDuration(), 3)).thenReturn(List.of(event), List.of());
        doThrow(new IllegalStateException("broker unavailable")).when(broker).publish(event);
        var publisher = new OutboxPublisher(outbox, broker, properties, clock);
        assertThat(publisher.publishOne()).isTrue();
        verify(outbox).markAttemptFailed(event, clock.instant(), 3, Duration.ofMillis(250), "IllegalStateException");
        assertThat(publisher.publishOne()).isFalse();
    }
}
