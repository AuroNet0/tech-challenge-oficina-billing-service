# Etapa 7.4.1 — reteste real após a correção do OS

## Resultado: APROVADO nos cenários executados

Execuções realizadas em **2026-10-20, 22:31–22:37 America/Sao_Paulo** (2026-10-21 01:31–01:37 UTC), em dois projetos Docker novos e independentes. Fluxo principal, validação financeira, reentregas originais, concorrência, recuperação do RabbitMQ e recusa passaram. O bloqueio anterior de occurredAt numérico não voltou a ocorrer.

Foram concluídas **136 verificações no harness Main e 75 em Rejection**, além de conferências complementares de IDs, timestamps e HTTP. São asserções de cenários E2E, não 211 métodos JUnit nem uma nova medição de cobertura. Todos os passos descritos como executados abaixo possuem evidências reais; não foram usados eventos artificiais de sucesso.

## 1. Revisões efetivamente construídas

HEAD local comparado a refs/heads/homolog remoto em cada preparação, com snapshots git archive HEAD e sem incorporar alterações locais:

| Serviço | Commit homolog |
| --- | --- |
| OS | `665aea6bffc92d2d2d6183a56d20393745d208b4` |
| Execution | `c1769c390825d12f35841e1b0d8f9508afa926a1` |
| Billing | `aa72d36da9ad1b516ed7ee9b6632ed26b3740f67` |

A correção dos dois serializers do OS com ObjectWriter sem WRITE_DATES_AS_TIMESTAMPS já estava commitada pelo usuário. Foram inspecionados os contratos atuais, APIs, segurança e serializers. O teste verificou **cada envelope real capturado**: occurredAt string ISO-8601, source, routing key, osId, sagaId e correlationId. Não foi relaxado o codec nem transformado o corpo original dos comandos.

## 2. Arquitetura Docker e isolamento

| Cenário | Projeto novo | Portas loopback OS / Execution / Billing / Rabbit management |
| --- | --- | --- |
| Principal | `billing-e2e-20261020-223000-c9e5a9` | 21080 / 21081 / 21082 / 15678 |
| Recusa | `billing-e2e-20261020-223342-8aada6` | 22080 / 22081 / 22082 / 15679 |

**Cada projeto** contém sete containers, rede privada própria e quatro volumes novos:

```text
OS --------- PostgreSQL oficina_os
Execution -- MongoDB oficina_execution (replica set rs0)
Billing ---- PostgreSQL oficina_billing
  \             |             /
       RabbitMQ único daquele projeto
       oficina.commands / oficina.events
```

Os três serviços de cada cenário compartilham um único broker; não há ligação entre os brokers dos dois cenários ou com ambientes normais. PostgreSQL e AMQP não têm portas publicadas no host. As portas HTTP/management estão vinculadas exclusivamente a 127.0.0.1. O Compose adicional não altera os Compose originais. Os Dockerfiles dos snapshots foram usados sem alteração. O broker de teste limita schedulers Erlang para conter uso de CPU em execuções paralelas.

Infraestrutura: PostgreSQL 17 Alpine, MongoDB 8.0, RabbitMQ 4 Management Alpine. OS aplica Flyway V1–V4; Billing aplica V1–V3, todas com success=true, mantendo Hibernate validate. MongoDB executa como rs0 com primary. As verificações iniciais exigiram zero OS, zero orçamento e zero diagnóstico em cada ambiente.

| Serviço/sufixo do container | Principal, ao concluir | Recusa, ao concluir |
| --- | --- | --- |
| os-1 | running / healthy | running / healthy |
| execution-1 | running / healthy | running / healthy |
| billing-1 | running / healthy | running / healthy |
| os-db-1 | running / healthy | running / healthy |
| billing-db-1 | running / healthy | running / healthy |
| mongodb-1 | running / healthy | running / healthy |
| rabbitmq-1 | running / healthy, após recuperação | running / healthy |

As três readiness retornaram HTTP 200 nos dois ambientes ao final. As seis rotas de negócio consultadas sem Basic retornaram 401. POST autenticado de aprovação sem CSRF retornou 403 em cada cenário; as decisões válidas usaram cookie de sessão e X-CSRF-TOKEN reais obtidos por GET financeiro, sem desabilitar CSRF.

Os seis containers normais do usuário permaneceram ativos, com os mesmos IDs. Os projetos E2E anteriores, incluindo a baseline bloqueada, foram **apenas parados**, sem apagar containers/volumes, para liberar recursos; seus dados não foram reutilizados. Os dois projetos aprovados permanecem ativos para inspeção. Nenhum banco de outro serviço foi acessado por uma aplicação; o harness fez somente consultas administrativas de leitura para verificar os resultados.

## 3. Fluxo principal — IDs

Cliente, veículo e filial foram cadastrados pela API autenticada do OS, antes da abertura da ordem. Os cadastros e os seus IDs estão em registration.json nos artefatos locais.

| Recurso | ID real |
| --- | --- |
| OS | `7458b7e9-fcd7-47d3-935f-a31ac8c39515` |
| Saga | `0887024b-b90a-46a5-884f-1ff4f41b26b2` |
| CorrelationId | `8c43dfd0-454a-474e-bfcd-142a8e8d563f` |
| Diagnóstico | `ce72fd3f-4acd-4125-98a8-63b1ed25d02c` |
| Orçamento | `6649b3da-beff-447b-a0ba-e5c63a0860ec` |
| Aprovação | `311032af-a968-4d05-b25a-d26e0af6c5b9` |
| Reparo | `8e887090-aede-451c-9076-ba07368ea251` |
| Pagamento | `893db737-1f71-49d7-8e7a-1c11ad066cae` |
| Tentativa inicial | `13c7d592-56d1-4f67-a46b-6bdf39bcfa7a` |

### Sequência real de comandos e eventos

A fila observadora e2e.audit foi ligada aos dois exchanges antes da abertura da OS. A tabela lista os dez eventIds distintos; foram capturadas 16 entregas, incluindo uma cópia concorrente de quote.create e os cinco replays deliberados.

| occurredAt UTC | Tipo | EventId |
| --- | --- | --- |
| 01:31:19.962604826 | diagnostic.start.v1 | `08a5eb86-2535-40fe-b801-21bc8c98bb7d` |
| 01:31:21.448301946 | diagnostic.started.v1 | `9885b1cf-8e6a-4c3c-9310-2978cc7de164` |
| 01:31:27.688276395 | diagnostic.completed.v1 | `d9463662-af54-47d5-bd9f-a80ecbb690ed` |
| 01:31:29.615648550 | quote.create.v1 | `e3e0c486-7af8-4ecb-9137-2a1ed2290149` |
| 01:31:46.699263216 | quote.created.v1 | `728c3416-b1b6-4aef-a7ae-1e57086e0499` |
| 01:32:00.951158 | quote.approved.v1 | `107c17f9-2290-4708-9acb-aa8f5ddbac9d` |
| 01:32:22.591525133 | repair.start.v1 | `4aa3c8fc-9fa7-43e2-9633-bb3d59e4390b` |
| 01:32:26.508660645 | repair.started.v1 | `dd302ac4-52f2-4113-86b6-69d995209593` |
| 01:32:30.983421055 | repair.completed.v1 | `df260e2e-e6eb-48a4-b6a9-9748583a245e` |
| 01:32:34.841476162 | payment.request.v1 | `68148df8-b030-4571-8671-3222643227d0` |

Os valores completos nos JSONs incluem a data e o sufixo Z; a tabela abrevia somente a apresentação. Os comandos vieram do OS, os resultados técnicos do Execution e os eventos quote do Billing. quote.created contém o ID real do orçamento; payment.request contém o repairId real acima.

### Checkpoints de estado confirmados por REST

1. Abertura: Saga WAITING_DIAGNOSTIC.
2. Após diagnostic.started: OS EM_DIAGNOSTICO, diagnóstico IN_PROGRESS.
3. Itens adicionados por REST autenticado; conclusão real do diagnóstico gera diagnostic.completed e quote.create.
4. Após quote.created: OS AGUARDANDO_APROVACAO, Saga WAITING_APPROVAL, orçamento PENDING_APPROVAL.
5. Aprovação via REST persiste APPROVED e Outbox; após recuperação do broker, segue quote.approved → repair.start.
6. Após repair.started: OS EM_EXECUCAO, Saga WAITING_REPAIR, reparo IN_PROGRESS.
7. Conclusão real do reparo via REST: repair.completed → payment.request.
8. Estado final: **OS AGUARDANDO_PAGAMENTO / Saga WAITING_PAYMENT / pagamento PENDING**. A OS não foi finalizada.

## 4. Itens e validação financeira executada

| Item | Tipo / unidade | Quantidade preservada | Preço unitário preservado | Total BRL |
| --- | --- | --- | --- | --- |
| SVC-E2E | SERVICO / HORA | 1.250000 | 123.456000 | 154.32 |
| PECA-E2E | PECA / UNIDADE | 2.500000 | 12.340000 | 30.85 |
| MAT-E2E | MATERIAL / KIT | 0.750000 | 19.960000 | 14.97 |
| **Total** | | | | **200.14** |

Os itens vieram da API de diagnóstico, não de INSERT financeiro ou endpoint de simulação. Foram comparados itemId, descrição, tipo, unidade, quantidade incluindo escala, preço e produto exato no orçamento. As APIs transportaram strings decimais. O harness usou System.Decimal para esses valores de teste, sem conversão a double ou arredondamento. A persistência e os cálculos da aplicação continuam BigDecimal/NUMERIC.

Uma única aprovação APPROVED foi persistida. decisionAt e approvedAt são exatamente **2026-10-21T01:32:00.951158Z**. A decisão REST não informou X-Correlation-Id e reutilizou corretamente a correlação original da Saga. responsavelId=e2e-provisorio é identificação provisória, não prova de identidade ou autorização por cliente.

Pagamento lógico único por orçamento, BRL 200.14, com uma tentativa inicial PENDING e chave idempotente consistente entre pagamento e tentativa. providerPaymentId e confirmedAt permanecem nulos. O texto MERCADO_PAGO no registro é apenas o rótulo preparado para integração futura: **não existe integração de cobrança nesta revisão**. Não houve chamada ou resultado financeiro simulado, payment.confirmed, payment.failed ou finalização da OS.

Contagens finais do Billing principal: **1 orçamento, 3 itens, 1 aprovação, 1 pagamento, 1 tentativa, 2 Inbox processadas e 2 Outbox publicadas**.

## 5. Idempotência e concorrência executadas

### Duas entregas do comando original de criação

Antes da conclusão do diagnóstico, somente o Billing do projeto principal foi parado. O quote.create original foi produzido pelo OS e permaneceu na fila. O harness leu com ack_requeue_true, comparou seu corpo aos bytes do payload persistido e publicou uma cópia idêntica, com o mesmo eventId e envelope.

Foram verificadas duas mensagens idênticas aguardando consumo e Inbox ainda vazia. Ao reiniciar o Billing, havia dois consumidores na fila billing.quote.create. Resultado: um orçamento, três itens, uma Inbox PROCESSED e um único registro/eventId quote.created. Trata-se de concorrência de entrega para dois consumidores, sem instrumentação interna para impor um instante específico de sobreposição das transações SQL.

### Aprovação concorrente

Dois POSTs assíncronos HTTP Basic + sessão/CSRF válidos foram disparados para o mesmo orçamento ainda pendente, durante indisponibilidade do broker. Respostas: **200 e 409**. Persistidos: uma aprovação e uma Outbox quote.approved. Repetição REST posterior retornou 409, sem nova decisão/evento.

### Cinco reentregas originais

Após WAITING_PAYMENT/PENDING, foram republicados os envelopes completos originais de:

- quote.create.v1 e payment.request.v1 no exchange de comandos;
- quote.created.v1, quote.approved.v1 e repair.completed.v1 no exchange de eventos.

Corpo, eventId, occurredAt e payload não foram alterados. Mensagens foram persistentes e o roteamento foi confirmado. O harness aguardou drenagem/ACK das filas normais e todas as Outboxes publicadas. Os snapshots completos de **OS/PostgreSQL, Billing/PostgreSQL e Execution/MongoDB ficaram byte a byte idênticos antes/depois**: não apenas contagens, mas registros, versões, timestamps, históricos, Inbox, Outbox, itens e tentativas. Os SHA-256 correspondentes estão na evidência selecionada.

## 6. Queda e recuperação real do RabbitMQ

O RabbitMQ **somente do projeto principal** foi parado antes dos POSTs de aprovação. A decisão foi confirmada no PostgreSQL apesar da indisponibilidade. A Outbox permaneceu persistida, observada PROCESSING com attempts=2, last_error=BrokerPublishException; readiness do Billing continuou UP por considerar o banco, não o broker.

O broker foi reiniciado com seu próprio volume preservado. A mesma Outbox, sem intervenção no banco, foi publicada com o mesmo eventId e corpo, na tentativa **3**, publishedAt **2026-10-21T01:32:22.578782Z**. O OS consumiu o resultado, gerou repair.start e o fluxo chegou normalmente ao pagamento pendente. Não houve perda da decisão confirmada, redrive manual ou recriação do evento.

Ao concluir: todas as Outboxes publicadas; filas de retry/DLQ vazias; nenhuma mensagem não confirmada nas filas normais. A fila observadora mantém as cópias de auditoria deliberadamente. A evidência é compatível com entrega at-least-once e deduplicação, **não promessa de exactly-once**.

## 7. Recusa — outro ambiente e outros dados

| Recurso | ID real |
| --- | --- |
| OS | `f20ac177-a636-4bb4-9e47-9ea6f47f0299` |
| Saga | `c53ae33b-7ec0-4dd4-9971-1adae6d6945f` |
| CorrelationId | `468aa09a-467c-4b2a-9e72-673a21dc5925` |
| Diagnóstico | `3413b978-fd99-4df0-be3e-302a7150d680` |
| Orçamento | `abf6934d-f5ee-4eb8-918f-29f37bbb4171` |
| Decisão | `7d66b911-7a0f-4668-a871-ee511bc3e84f` |
| Evento quote.rejected | `8df1862a-7d34-4254-94ba-c1e95bf9459f` |

Sequência real observada: diagnostic.start → diagnostic.started → diagnostic.completed → quote.create → quote.created → quote.rejected, com seis eventIds distintos. Diagnóstico e orçamento usaram os mesmos valores representativos, mas em banco novo e com IDs próprios.

Recusa via REST Basic + CSRF real, motivo **Recusa sintetica do cenario E2E**. O evento transportou reason não vazio e correlação original. rejectedAt e decisionAt coincidem em **2026-10-21T01:36:38.898621Z**.

Resultado confirmado: **OS CANCELADA / Saga COMPENSATED**, uma decisão REJECTED. Nenhum repair.start na Outbox do OS nem no tráfego observado, zero reparos no Mongo, zero pagamentos e zero tentativas no Billing. Contagens: 1 orçamento, 3 itens, 1 decisão, 1 Inbox e 2 Outbox publicadas. Todas as DLQs vazias.

## 8. Evidências e reprodução

- [Harness e comandos reproduzíveis](../integration/7.4.1/README.md): prepare.ps1, run.ps1 e export-run.ps1; Main e Rejection exigem preparação separada e bases vazias.
- [Evidência principal](evidence/7.4.1/corrected-main.json): IDs, valores, decisão, pagamento, concorrência 200/409, Outbox durante a queda e após recuperação, timelines, hashes dos replays/snapshots, HTTP 401/200, bindings e containers.
- [Evidência da recusa](evidence/7.4.1/corrected-rejection.json): decisão, reason, estados finais, contagens, tráfego, autenticação, filas e containers.
- [Baseline histórica bloqueada](integration-7.4.1.md): mantida para demonstrar a causa original e sua resolução, sem sobrescrever evidências anteriores.
- Artefatos completos locais: target/integration-7.4.1/20261020-223000-c9e5a9/ e target/integration-7.4.1/20261020-223342-8aada6/. Incluem logs, snapshots, manifests, APIs e audit-deliveries. Maven clean os remove; a evidência selecionada em docs permanece.

Os JSONs selecionados não contêm senhas, tokens CSRF ou cookies de sessão. Os payloads financeiros são sintéticos e pertencem apenas aos ambientes de teste. A documentação do harness descreve parada por nome exato de projeto, sem apagar volumes normais.

## 9. Problemas, correções e limites

- **Problema funcional anterior resolvido:** occurredAt numérico do OS. A revisão corrigida produziu strings ISO em todos os comandos capturados, inclusive quote.create, repair.start e payment.request.
- **Ajustes somente no harness:** decodificação UTF-8 de respostas byte[] no Windows PowerShell, correção de sombreamento de variável no polling, enumeração de arrays JSON e portas configuráveis. O ensaio 20261020-222200-9d5e83 foi interrompido por falha do harness antes do fluxo financeiro; seus dados foram abandonados e não reutilizados nos cenários aprovados. Não foi classificado como teste funcional aprovado.
- Não foram encontrados novos bloqueios funcionais nos cenários efetivamente executados. Não são propostas alterações adicionais no código dos serviços para este escopo.
- Não houve mudança de código/configuração original dos três serviços, alteração de contrato, atualização manual de status, integração Mercado Pago, evento de pagamento artificial, commit ou push.
- Autenticação Basic/identidade provisória continuam limitadas a desenvolvimento; a ausência de autorização definitiva por cliente permanece fora do escopo. A API não é apresentada como pronta para acesso público.
- Não se testaram indisponibilidade prolongada até FAILED, queda dos bancos, partição de cluster, confirmação financeira ou todas as compensações possíveis. Isso não é inferido como aprovado a partir destes testes.
- Maven clean verify/JaCoCo dos três serviços não foram reexecutados nesta rodada E2E; não há nova porcentagem de cobertura ou alegação de regressão unitária completa. Os resultados aqui comprovam os fluxos distribuídos explicitamente listados.
