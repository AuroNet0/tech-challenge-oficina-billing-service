package com.oficina.billing.application.usecase;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.oficina.billing.application.exception.InvalidCommandException;
import com.oficina.billing.application.message.MessageEnvelope;
import com.oficina.billing.application.port.out.*;
import com.oficina.billing.domain.enums.*;
import com.oficina.billing.domain.exception.DomainException;
import com.oficina.billing.domain.model.*;
import static com.oficina.billing.domain.model.FinancialRules.*;

@Service
public class CommandHandler {
    private final InboxRepositoryPort inbox;
    private final OrcamentoRepositoryPort orcamentos;
    private final PagamentoRepositoryPort pagamentos;
    private final MessageCodecPort codec;
    private final EventRecorder events;
    private final Clock clock;
    private final NotificacaoOrcamentoRepositoryPort notificacoes;

    public CommandHandler(InboxRepositoryPort inbox, OrcamentoRepositoryPort orcamentos,
                          PagamentoRepositoryPort pagamentos, MessageCodecPort codec, EventRecorder events, Clock clock,
                          NotificacaoOrcamentoRepositoryPort notificacoes) {
        this.inbox = inbox;
        this.orcamentos = orcamentos;
        this.pagamentos = pagamentos;
        this.codec = codec;
        this.events = events;
        this.clock = clock;
        this.notificacoes = notificacoes;
    }

    @Transactional
    public InboxStatus handle(MessageEnvelope command, String rawBody) {
        inbox.lockOs(command.osId());
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var message = inbox.claim(new InboxMessage(command.eventId(), command.eventType(), command.osId(),
                command.sagaId(), command.correlationId(), rawBody, InboxStatus.RECEIVED, now, null, null));
        if (!codec.equivalent(message.payload(), rawBody))
            throw new InvalidCommandException("eventId reutilizado com envelope incompativel");
        if (message.status() != InboxStatus.RECEIVED) return message.status();
        InboxStatus result = InboxStatus.PROCESSED;
        String error = null;
        try {
            switch (command.eventType()) {
                case "quote.create.v1" -> createQuote(command);
                case "payment.request.v1" -> requestPayment(command);
                default -> throw new InvalidCommandException("Comando nao suportado");
            }
        } catch (InvalidCommandException exception) {
            result = InboxStatus.REJECTED;
            error = exception.getMessage();
        }
        inbox.finish(command.eventId(), result, now, error);
        return result;
    }

    private void createQuote(MessageEnvelope command) {
        var existing = orcamentos.findByOsId(command.osId());
        if (existing.isPresent()) {
            var budget = existing.get();
            requireSaga(budget.sagaId(), command);
            try {
                var items = items(command.payload());
                if (!sameItems(budget.itens(), items))
                    throw new InvalidCommandException("Orcamento existente possui itens divergentes");
            } catch (DomainException | IllegalArgumentException exception) {
                throw new InvalidCommandException("Itens invalidos para orcamento existente");
            }
            return;
        }
        Orcamento budget;
        try {
            budget = Orcamento.criar(command.osId(), command.sagaId(), items(command.payload()),
                    clock.instant().truncatedTo(ChronoUnit.MICROS));
        } catch (DomainException | IllegalArgumentException exception) {
            events.record(command.osId(), "Orcamento", command.osId(), command.sagaId(), command.correlationId(),
                    "quote.creation-failed.v1", Map.of("reason", "Dados do diagnostico invalidos para gerar orcamento."),
                    clock.instant());
            throw new InvalidCommandException("Dados do diagnostico invalidos");
        }
        budget = orcamentos.save(budget);
        notificacoes.registrar(budget);
        events.record(budget.id(), "Orcamento", budget.osId(), budget.sagaId(), command.correlationId(),
                "quote.created.v1", Map.of("quoteId", budget.id().toString()), clock.instant());
    }

    private List<OrcamentoItem> items(Map<String, Object> payload) {
        require(payload.keySet().equals(Set.of("items")), "Payload de orcamento invalido");
        require(payload.get("items") instanceof List<?>, "Items deve ser lista");
        var result = new ArrayList<OrcamentoItem>();
        for (Object value : (List<?>) payload.get("items")) {
            require(value instanceof Map<?, ?>, "Item invalido");
            var item = (Map<?, ?>) value;
            require(item.keySet().equals(Set.of("itemId", "descricao", "tipo", "quantidade", "unidadePreco", "precoPrevisto")),
                    "Campos do item invalidos");
            result.add(OrcamentoItem.criar(field(item, "itemId"), field(item, "descricao"),
                    OrcamentoItemType.valueOf(field(item, "tipo")), new BigDecimal(field(item, "quantidade")),
                    PriceUnit.valueOf(field(item, "unidadePreco")), new BigDecimal(field(item, "precoPrevisto"))));
        }
        require(!result.isEmpty(), "Itens obrigatorios");
        return result;
    }

    private String field(Map<?, ?> values, String field) {
        require(values.get(field) instanceof String, "Campo deve ser string decimal ou texto");
        return text((String) values.get(field), 500, field);
    }

    private boolean sameItems(List<OrcamentoItem> stored, List<OrcamentoItem> incoming) {
        if (stored.size() != incoming.size()
                || incoming.stream().map(OrcamentoItem::itemId).distinct().count() != incoming.size()) return false;
        var byId = new HashMap<String, OrcamentoItem>();
        stored.forEach(item -> byId.put(item.itemId(), item));
        return incoming.stream().allMatch(item -> {
            var original = byId.get(item.itemId());
            return original != null && original.descricao().equals(item.descricao()) && original.tipo() == item.tipo()
                    && original.unidadePreco() == item.unidadePreco()
                    && original.quantidade().compareTo(item.quantidade()) == 0
                    && original.precoUnitario().compareTo(item.precoUnitario()) == 0;
        });
    }

    private void requestPayment(MessageEnvelope command) {
        String repairId;
        try {
            require(command.payload().keySet().equals(Set.of("repairId")), "Payload de pagamento invalido");
            repairId = field(command.payload(), "repairId");
        } catch (DomainException exception) {
            throw new InvalidCommandException("repairId obrigatorio e textual");
        }
        var budget = orcamentos.findByOsId(command.osId())
                .orElseThrow(() -> new InvalidCommandException("Orcamento inexistente"));
        requireSaga(budget.sagaId(), command);
        if (budget.status() != OrcamentoStatus.APPROVED)
            throw new InvalidCommandException("Pagamento exige orcamento aprovado");
        var existing = pagamentos.findByOrcamentoId(budget.id());
        if (existing.isPresent()) {
            var original = inbox.findOrigin(command.osId(), command.sagaId(), "payment.request.v1")
                    .orElseThrow(() -> new InvalidCommandException("Pagamento sem comando original rastreavel"));
            var originalCommand = codec.readCommand(original.payload(), "payment.request.v1");
            if (!repairId.equals(originalCommand.payload().get("repairId"))
                    || !existing.get().sagaId().equals(command.sagaId())
                    || existing.get().valor().compareTo(budget.valorTotal()) != 0)
                throw new InvalidCommandException("Pagamento existente incompativel com comando");
            return;
        }
        String reference = "billing:" + budget.id();
        String key = UUID.nameUUIDFromBytes((reference + ":initial").getBytes(StandardCharsets.UTF_8)).toString();
        Pagamento payment;
        try {
            payment = Pagamento.criar(budget, "MERCADO_PAGO", reference, key, clock.instant());
        } catch (DomainException exception) {
            throw new InvalidCommandException("Pagamento nao elegivel");
        }
        pagamentos.save(payment);
    }

    private void requireSaga(UUID sagaId, MessageEnvelope command) {
        if (!sagaId.equals(command.sagaId())) throw new InvalidCommandException("sagaId incompativel");
    }
}
