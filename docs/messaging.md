# Mensageria do Billing Service — etapa 7.4

## Referências e compatibilidade

Implementação baseada nas branches homolog consultadas:

- [OS: eventos e comandos](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/events.md).
- [OS: Saga](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/saga.md).
- [OS: SagaEventHandler](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/src/main/java/com/oficina/os/application/saga/SagaEventHandler.java).
- [OS: OutboxPublisher](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/src/main/java/com/oficina/os/infrastructure/messaging/OutboxPublisher.java).
- [Execution: mensageria](https://github.com/AuroNet0/tech-challenge-oficina-execution-service/blob/homolog/docs/messaging.md), CommandHandler, MessageCodec, InboxRepositoryAdapter, OutboxRepositoryAdapter e RabbitMessageBrokerAdapter.

O OS permanece como único orquestrador. Billing não consulta bancos externos, não consome comandos do Execution nem eventos de oficina.events. Não foram alterados os outros repositórios. As filas principais são declaradas sem argumentos de DLX para evitar PRECONDITION_FAILED com declarações preexistentes; retry/DLQ são encaminhamentos explícitos confirmados.

## Envelope

```json
{
  "eventId": "11111111-1111-4111-8111-111111111111",
  "eventType": "quote.create.v1",
  "occurredAt": "2026-10-20T12:00:00Z",
  "correlationId": "22222222-2222-4222-8222-222222222222",
  "sagaId": "33333333-3333-4333-8333-333333333333",
  "osId": "44444444-4444-4444-8444-444444444444",
  "source": "os-service",
  "payload": {
    "items": [{
      "itemId": "diagnostic-1",
      "descricao": "Diagnostico tecnico",
      "tipo": "SERVICO",
      "quantidade": "1.2500",
      "unidadePreco": "HORA",
      "precoPrevisto": "80.00"
    }]
  }
}
```

Todos os campos são obrigatórios. UUIDs devem ser canônicos (maiúsculas são aceitas), occurredAt deve ser texto ISO-8601 aceito por Instant.parse, payload deve ser objeto e eventType deve coincidir com a routing key. Comandos aceitam exclusivamente source=os-service. Esse campo valida o contrato, não autentica o produtor: configure permissões RabbitMQ para restringir produtores de comandos ao OS.

MessageCodec escreve occurredAt explicitamente como Instant.toString(), evitando timestamps numéricos independentemente da configuração global do Jackson. Eventos usam source=billing-service e eventId próprio. Republicações usam exatamente o envelope persistido, sem recriar UUID ou timestamp.

## Topologia

Exchanges topic duráveis:

| Exchange | Uso |
| --- | --- |
| oficina.commands | Comandos do OS |
| oficina.events | Eventos de resultado para o OS |
| billing.commands.retry | Transferência confirmada para retry |
| billing.commands.dlx | Transferência confirmada para DLQ |

| Fila durável | Binding |
| --- | --- |
| billing.quote.create | oficina.commands / quote.create.v1 |
| billing.payment.request | oficina.commands / payment.request.v1 |
| billing.quote.create.retry | billing.commands.retry / quote.create.v1 |
| billing.payment.request.retry | billing.commands.retry / payment.request.v1 |
| billing.commands.dlq | billing.commands.dlx / # |

Filas .retry têm TTL configurável e retornam a oficina.commands com a routing key original. A DLQ não tem binding de retorno: mensagens permanentemente inválidas não entram em ciclos automáticos. Mensagens e topologia são persistentes/duráveis.

## Comandos e resultados

### quote.create.v1

Payload contém somente items, com os seis campos de diagnóstico do exemplo. Quantidade e precoPrevisto são strings decimais; o preço previsto vira precoUnitario sem consulta ao Execution. Validações e cálculos usam as factories e a política BigDecimal da etapa 7.2.

Uma nova OS gera um orçamento PENDING_APPROVAL. A mesma transação grava orçamento, itens, Inbox PROCESSED e Outbox quote.created.v1 com `{"quoteId":"UUID real persistido"}`.

Um novo eventId para orçamento já existente só é aceito se a Saga e os itens forem consistentes: itemId, descrição, tipo, unidade, quantidade e preço. A comparação financeira é por valor decimal; a ordem de itens de uma retransmissão pode variar. Não há recriação de orçamento nem novo quote.created.

Itens semanticamente inválidos, quando ainda não existe orçamento, geram Inbox REJECTED e Outbox quote.creation-failed.v1 com reason não vazio e seguro. O comando vai à DLQ após commit. Divergências de Saga/itens de orçamento existente vão à DLQ sem emitir falha contra outra Saga. Falhas de banco, codec de saída ou Outbox revertem tudo e são reexecutáveis; não são convertidas em falhas financeiras.

### Decisões REST

Na etapa 8.3.4, as rotas HTTP usam Bearer RS256 e autorizacao pela OS, com adapter indisponivel por padrao. Os consumidores nao dependem de JWT humano. Cada decisao HTTP autorizada grava orcamento, Aprovacao e Outbox na mesma transacao:

| Decisão | Evento | Payload |
| --- | --- | --- |
| Aprovar | quote.approved.v1 | {} |
| Rejeitar | quote.rejected.v1 | {"reason":"Motivo informado ou fallback"} |

Se motivo não foi informado, o reason do evento é "Orcamento recusado pelo solicitante.". O campo motivo do registro de decisão continua opcional. OsIDs/SagaIDs são os do agregado; não vêm livremente do corpo HTTP.

X-Correlation-Id válido é preservado. Sem header, EventRecorder reutiliza a correlação do primeiro quote.create.v1 PROCESSED da mesma OS/Saga. A coluna interna received_order garante essa escolha mesmo com relógios iguais. Sem comando original (por exemplo, factories nos testes), o fallback é UUID.nameUUIDFromBytes de "billing:" + sagaId + ":" + osId, rastreável e determinístico. Decisão e evento usam a mesma correlação.

Orçamento, decisão e evento compartilham um instante UTC truncado a microssegundos. Repetições/oposições continuam retornando 409, sem novos registros, eventos, timestamps ou alterações de versão. Falha da Outbox reverte a decisão inteira. Controller nunca publica no broker.

### payment.request.v1

Payload contém somente repairId textual não vazio, conforme o comando criado pelo SagaEventHandler após repair.completed.v1. Billing valida OS/Saga e orçamento APPROVED e cria:

- Um pagamento lógico PENDING, com valor exatamente igual ao aprovado e moeda BRL.
- Provider MERCADO_PAGO como identificação do adapter futuro, sem qualquer chamada externa.
- externalReference "billing:" + orçamentoId.
- Uma tentativa inicial PENDING com UUID idempotente determinístico derivado de externalReference + ":initial".

Pagamento, tentativa e Inbox são atômicos. Novos comandos para o mesmo orçamento consultam o pagamento existente e o repairId do primeiro comando processado. Se consistentes, não criam outro pagamento ou tentativa; se conflitantes, vão à DLQ. Um comando de requisição não abre automaticamente nova tentativa de provedor após FAILED.

**PENDING significa intenção persistida, não cobrança realizada nem pagamento confirmado.** Não há chamada Mercado Pago, webhook, confirmação manual, payment.confirmed.v1 ou payment.failed.v1 nesta etapa. A ausência do adapter não é uma falha financeira. Falhas permanentes de elegibilidade vão à DLQ e exigem recuperação coordenada com o OS, que permanece aguardando pagamento.

## Inbox e atomicidade

V3 cria inbox_messages, com PK event_id, envelope completo em payload TEXT validado como objeto JSON, IDs de Saga/OS/correlação, status, timestamps e erro seguro. received_order é uma sequência técnica adicional.

CommandHandler é @Transactional. Antes de processar, obtém advisory lock PostgreSQL por OS, com duração da transação. INSERT ... ON CONFLICT DO NOTHING seguido de SELECT FOR UPDATE garante a posse/deduplicação do eventId. Não existe janela exists-then-insert desprotegida.

Uma entrega com o mesmo eventId deve ter o envelope JSON estruturalmente igual (ordem de campos e espaços são irrelevantes). Conteúdo ou identificadores divergentes são rejeitados, preservando a Inbox original. Mensagens PROCESSED não produzem novos efeitos; mensagens REJECTED continuam rejeitadas.

O lock por OS também serializa comandos diferentes que tentem criar simultaneamente o orçamento único ou pagamento lógico da mesma OS. Hash collision somente serializa duas OS, sem misturar dados. Não é um lock distribuído: ele existe exclusivamente dentro do PostgreSQL do Billing.

ACK é manual e ocorre somente depois que o proxy transacional do handler retorna, ou seja, após commit. Falhas de commit são capturadas como transitórias pelo consumidor.

## Retries de consumo e DLQ

- Envelope inválido: DLQ, sem Inbox com dados não confiáveis.
- Falha permanente de negócio: Inbox REJECTED; evento de falha de criação apenas quando apropriado ao contrato.
- Falha transitória: rollback e encaminhamento persistente confirmado para .retry.
- Header billing-attempt incrementa o número de transferências. COMMAND_MAX_DELIVERIES inclui o processamento inicial. Atingido o limite, segue para DLQ.
- Encaminhamento para retry/DLQ exige publisher confirm correlacionado e rota válida antes do ACK da entrega original.
- Se o próprio encaminhamento falhar, o canal é encerrado sem ACK; RabbitMQ preserva/reentrega a mensagem e o container recupera o canal com intervalo de 5s. Isso evita requeue imediato em loop quente. Falhas permanentes de infraestrutura de recuperação exigem intervenção, não descarte da mensagem.

Confirm seguido de perda do ACK pode duplicar uma entrega na retry/DLQ; a Inbox impede efeitos financeiros duplicados. Não há garantia exactly-once.

A topologia de retry usa filas classic duráveis e dead lettering TTL, assim como a referência do Execution. O destino precisa estar disponível; garantias de HA/at-least-once para dead lettering interno em falhas de cluster exigem políticas/quorum apropriados, provisionados de forma coordenada antes da produção.

## Outbox e leases

V3 cria outbox_events, com PK event_id, sequência técnica BIGSERIAL, envelope imutável, referências, status, contagem de tentativas, datas, lease e último erro. Não há FK para bancos externos; aggregate_id referencia o orçamento quando criado, ou a OS quando sua criação falha.

Aquisição SQL usa FOR UPDATE SKIP LOCKED em lote, retornando no máximo o primeiro evento não publicado de cada OS. Eventos com estado FAILED, lease ativo ou backoff futuro bloqueiam eventos posteriores da mesma OS; outras OS progridem. Uma sequência global ordena as inserções; locks transacionais por OS no EventRecorder serializam a gravação e o commit dos eventos dessa OS.

O publisher processa até OUTBOX_BATCH_SIZE eventos por ciclo, adquirindo um lease por vez para não deixá-lo expirar enquanto outros envios do lote aguardam confirmação. A porta suporta aquisição de vários eventos de OS distintas; os testes exercitam esse modo. Publicação e espera de confirm ocorrem depois do commit da aquisição, fora da transação PostgreSQL.

Cada aquisição incrementa attempts e gera lease_token novo. A marcação PUBLISHED e o reagendamento exigem status PROCESSING, token correspondente e lease ainda válido. Workers antigos não podem alterar o resultado de leases novos. Leases expirados são recuperados; expiração na última tentativa gera FAILED.

A mensagem é persistente, mandatory e possui CorrelationData exclusivo por tentativa de publicação. ACK do broker junto com basic.return é falha: unroutable não vira PUBLISHED.

Backoff: min(initialBackoff × 2^(attempts-1), maxBackoff), com limite para evitar overflow. Após OUTBOX_MAX_ATTEMPTS o evento fica FAILED. last_error contém classe/código seguro, sem credenciais ou corpo de mensagem.

Entrega é **at-least-once**: uma queda após confirm e antes de salvar PUBLISHED pode republicar o mesmo eventId/payload. O OS deduplica por eventId. Eventos antigos em voo após lease expirado também podem chegar como duplicatas; fencing protege o banco, não promete ordenação física absoluta dessas duplicatas. Não se promete exactly-once.

## Configuração e execução local integrada

Variáveis principais:

| Variável | Padrão |
| --- | --- |
| RABBITMQ_HOST / RABBITMQ_PORT | localhost / 5672 no Java; host.docker.internal / 5672 no Compose |
| RABBITMQ_USERNAME / RABBITMQ_PASSWORD | billing / senha fornecida pelo ambiente |
| RABBITMQ_VHOST | / |
| RABBITMQ_CONFIRM_TIMEOUT | 5s |
| COMMAND_CONSUMER_ENABLED | true |
| COMMAND_MAX_DELIVERIES / COMMAND_RETRY_DELAY | 5 / 5s |
| OUTBOX_PUBLISHER_ENABLED / OUTBOX_FIXED_DELAY | true / 5s |
| OUTBOX_BATCH_SIZE / OUTBOX_LEASE_DURATION | 10 / 2m |
| OUTBOX_MAX_ATTEMPTS | 5 |
| OUTBOX_INITIAL_BACKOFF / OUTBOX_MAX_BACKOFF | 5s / 5m |

Mantenha lease confortavelmente maior que confirm-timeout e connection-timeout. Readiness continua baseada na aplicação e PostgreSQL, não no broker: com RabbitMQ indisponível, decisões locais são persistidas e aguardam publicação, podendo atingir FAILED. Monitorar filas, Inbox REJECTED e Outbox FAILED separadamente.

### Usar o broker compartilhado do OS/Execution

1. Inicie o RabbitMQ já utilizado pelo OS e Execution.
2. Configure no .env RABBITMQ_HOST=host.docker.internal e RABBITMQ_PORT com a porta desse broker (5672 no OS ou 5673 no broker opcional do Execution).
3. Configure usuário, senha e vhost válidos **desse mesmo broker**, com permissões do Billing. O projeto não cria usuários em brokers externos.
4. Execute docker compose up --build -d --wait, sem local-broker.

Em uma rede Docker compartilhada, use o alias do broker como RABBITMQ_HOST e a porta interna 5672; a participação nessa rede deve ser configurada explicitamente pelo operador. Mesmo nome de exchange em brokers diferentes não integra serviços. Não foram modificados os Compose dos outros serviços.

### Broker opcional, somente para desenvolvimento isolado

O profile local-broker cria RabbitMQ 4 nas portas locais 5674 e 15674, com volume persistente e healthcheck. Ele é opcional e **não integra automaticamente o OS/Execution**.

```bash
RABBITMQ_HOST=rabbitmq RABBITMQ_PORT=5672 docker compose --profile local-broker up --build -d --wait
```

PowerShell:

```powershell
$env:RABBITMQ_HOST='rabbitmq'
$env:RABBITMQ_PORT='5672'
docker compose --profile local-broker up --build -d --wait
```

Credenciais de exemplo são billing/billing_local, exclusivas de desenvolvimento e alteráveis no .env. Para que OS/Execution usem esse mesmo broker, eles precisam apontar para a porta 5674 no host (ou alias/5672 em rede compartilhada), com as mesmas credenciais/vhost. A configuração desses serviços é responsabilidade do operador.

Em produção, provisionar identidade do Billing com consumo somente das filas próprias e publicação em oficina.events/retry/DLQ. Restringir configure após provisionar topologia com identidade administrativa. Somente o OS deve poder produzir comandos. Não usar guest, não expor management publicamente nem registrar senhas/payloads completos.

## Recuperação operacional

Inbox REJECTED e DLQ não devem ser apagadas para esconder falhas. Corrija a causa e coordene com o OS: uma correção semântica deve usar novo eventId e preservar OS/Saga/correlação. Uma mensagem transitória cujo processamento foi revertido pode reutilizar o mesmo eventId após restaurar a infraestrutura. Não republicar comandos de uma Saga já compensada sem decisão operacional coordenada.

Outbox FAILED bloqueia a sequência daquela OS. Inspecione motivo, tentativas, roteamento, permissões, estado da Saga no OS e possível recebimento anterior. Registre a autorização de recuperação em auditoria operacional. Só então reabilite **o mesmo evento**, preservando event_id, sequence e payload. Exemplo administrativo, sem endpoint HTTP:

```sql
UPDATE outbox_events
SET status = 'PENDING', attempts = 0, next_attempt_at = now(),
    locked_until = NULL, lease_token = NULL,
    last_error = 'Redrive autorizado apos correcao operacional'
WHERE event_id = '<event-id-validado>' AND status = 'FAILED';
```

O reset abre uma nova janela limitada de tentativas; guarde a contagem anterior na auditoria externa. Não exclua/recrie o evento nem mude eventId para contornar a Inbox do OS. Se a infraestrutura de recuperação estiver indisponível, restaure exchanges/filas/conexão antes de retomar.

## Testes e limites

Testcontainers usa PostgreSQL 17 e RabbitMQ 4 reais. A suíte cobre envelope ISO, comandos, valores decimais, deduplicação concorrente, divergências de payload/Saga, quatro eventos quote.*, decisões REST, pagamento PENDING, rollbacks, retry limitado, DLQ, unroutable, workers concorrentes, leases expirados e queda/recuperação do broker. O JSON publicado é lido por um contrato espelho do SagaEnvelope do OS e por assertions de routing key/source/payload.

A evidencia historica da 7.4 comprova contrato e transporte reais no broker de teste, sem substituir a Saga ponta a ponta. Etapas posteriores acrescentaram Mercado Pago simulado/webhook (7.5) e JWT (8.3.4), preservando os contratos de mensageria. Nao ha outro orquestrador ou confirmacao financeira baseada somente em notificacao.
