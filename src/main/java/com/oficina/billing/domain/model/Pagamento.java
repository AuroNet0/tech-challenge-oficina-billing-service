package com.oficina.billing.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import com.oficina.billing.domain.enums.OrcamentoStatus;
import com.oficina.billing.domain.enums.PagamentoStatus;
import static com.oficina.billing.domain.model.FinancialRules.*;

public final class Pagamento {
    private final UUID id;
    private final UUID osId;
    private final UUID sagaId;
    private final UUID orcamentoId;
    private final BigDecimal valor;
    private final String moeda;
    private final String provider;
    private final String externalReference;
    private final Instant createdAt;
    private final Long version;
    private final List<PagamentoTentativa> tentativas;

    private Pagamento(UUID id, UUID osId, UUID sagaId, UUID orcamentoId, BigDecimal valor, String moeda,
                      String provider, String externalReference, Instant createdAt, Long version,
                      List<PagamentoTentativa> tentativas) {
        this.id = required(id, "id");
        this.osId = required(osId, "osId");
        this.sagaId = required(sagaId, "sagaId");
        this.orcamentoId = required(orcamentoId, "orcamentoId");
        this.valor = money(valor);
        require("BRL".equals(moeda), "Somente BRL permitido");
        this.moeda = moeda;
        this.provider = text(provider, 50, "provider");
        this.externalReference = text(externalReference, 100, "externalReference");
        this.createdAt = required(createdAt, "createdAt");
        require(version == null || version >= 0, "Version invalida");
        this.version = version;
        required(tentativas, "tentativas");
        require(!tentativas.isEmpty(), "Pagamento sem tentativa");
        this.tentativas = new ArrayList<>(tentativas);
        var keys = new HashSet<String>();
        var ids = new HashSet<UUID>();
        Instant previous = createdAt;
        int active = 0;
        for (int index = 0; index < tentativas.size(); index++) {
            var tentativa = required(tentativas.get(index), "tentativa");
            require(keys.add(tentativa.idempotencyKey()) && ids.add(tentativa.id()), "Tentativa duplicada");
            require(!tentativa.createdAt().isBefore(previous), "Ordem de tentativas invalida");
            if (tentativa.status() != PagamentoStatus.FAILED) active++;
            require(active <= 1, "Somente uma tentativa ativa");
            require(index == tentativas.size() - 1 || tentativa.status() == PagamentoStatus.FAILED
                    || tentativa.status() == PagamentoStatus.CONFIRMED, "Tentativa anterior ainda ativa");
            previous = tentativa.createdAt();
        }
    }

    public static Pagamento criar(Orcamento orcamento, String provider, String reference, String key, Instant now) {
        required(orcamento, "orcamento");
        require(orcamento.status() == OrcamentoStatus.APPROVED, "Cobranca exige orcamento aprovado");
        required(now, "createdAt");
        require(!now.isBefore(orcamento.approvedAt()), "Cobranca anterior a aprovacao");
        return new Pagamento(UUID.randomUUID(), orcamento.osId(), orcamento.sagaId(), orcamento.id(),
                orcamento.valorTotal(), "BRL", provider, reference, now, null,
                List.of(PagamentoTentativa.criar(key, now)));
    }

    public static Pagamento restaurar(UUID id, UUID osId, UUID sagaId, UUID orcamentoId, BigDecimal valor,
                                     String moeda, String provider, String reference, Instant createdAt,
                                     Long version, List<PagamentoTentativa> tentativas) {
        return new Pagamento(id, osId, sagaId, orcamentoId, valor, moeda, provider, reference, createdAt, version, tentativas);
    }

    public void processar(String key, Instant now) {
        requireCurrentKey(key);
        replace(atual().processar(now));
    }

    public void confirmar(String key, String paymentId, Instant now) {
        requireCurrentKey(key);
        replace(atual().confirmar(paymentId, now));
    }

    public void falhar(String key, String reason, Instant now) {
        requireCurrentKey(key);
        replace(atual().falhar(reason, now));
    }

    public void novaTentativa(String key, Instant now) {
        text(key, 100, "idempotencyKey");
        if (tentativas.stream().anyMatch(tentativa -> tentativa.idempotencyKey().equals(key))) {
            return;
        }
        require(status() == PagamentoStatus.FAILED, "Nova tentativa exige falha anterior");
        required(now, "createdAt");
        require(!now.isBefore(updatedAt()), "Nova tentativa anterior a falha");
        tentativas.add(PagamentoTentativa.criar(key, now));
    }

    public void confirmarVerificado(String key, String paymentId, Instant now) {
        required(now, "confirmedAt");
        if (status() == PagamentoStatus.CONFIRMED) {
            require(idempotencyKey().equals(key) && providerPaymentId().equals(paymentId), "Liquidacao externa conflitante");
            return;
        }
        var target = tentativas.stream().filter(attempt -> attempt.idempotencyKey().equals(key)).findFirst()
                .orElseThrow(() -> new com.oficina.billing.domain.exception.DomainException("Tentativa desconhecida"));
        require(!now.isBefore(updatedAt()), "Confirmacao anterior a atualizacao");
        for (int index = 0; index < tentativas.size(); index++) {
            var attempt = tentativas.get(index);
            if (attempt.id().equals(target.id())) {
                tentativas.set(index, attempt.confirmarReconciliado(paymentId, now));
            } else if (attempt.status() != PagamentoStatus.FAILED) {
                tentativas.set(index, attempt.falhar("Tentativa encerrada por liquidacao anterior", now));
            }
        }
    }

    private void requireCurrentKey(String key) {
        require(atual().idempotencyKey().equals(key), "Resultado nao pertence a tentativa atual");
    }

    private void replace(PagamentoTentativa tentativa) {
        require(!tentativa.updatedAt().isBefore(atual().updatedAt()), "Resultado anterior a atualizacao");
        tentativas.set(tentativas.size() - 1, tentativa);
    }

    private PagamentoTentativa atual() {
        return tentativas.stream().filter(attempt -> attempt.status() == PagamentoStatus.CONFIRMED)
                .findFirst().orElse(tentativas.getLast());
    }
    public UUID id() { return id; }
    public UUID osId() { return osId; }
    public UUID sagaId() { return sagaId; }
    public UUID orcamentoId() { return orcamentoId; }
    public BigDecimal valor() { return valor; }
    public String moeda() { return moeda; }
    public String provider() { return provider; }
    public String externalReference() { return externalReference; }
    public Instant createdAt() { return createdAt; }
    public Long version() { return version; }
    public List<PagamentoTentativa> tentativas() { return List.copyOf(tentativas); }
    public String idempotencyKey() { return atual().idempotencyKey(); }
    public PagamentoStatus status() { return atual().status(); }
    public String providerPaymentId() { return atual().providerPaymentId(); }
    public Instant updatedAt() { return atual().updatedAt(); }
    public Instant confirmedAt() { return atual().confirmedAt(); }
    public Instant failedAt() { return atual().failedAt(); }
    public String failureReason() { return atual().failureReason(); }
}
