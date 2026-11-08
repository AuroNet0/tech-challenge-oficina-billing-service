# Etapa 7.4.1 — integração real OS + Execution + Billing

> Relatório histórico da baseline anterior à correção do OS. Para a nova execução com homolog corrigida, consulte [o relatório de reteste](integration-7.4.1-retest.md). As evidências originais deste bloqueio foram preservadas; este não é o veredito da reexecução.

## Resultado: BLOQUEADO, não aprovado ponta a ponta

Execução em **2026-10-20, aproximadamente 22:02–22:04 America/Sao_Paulo** (2026-10-21 01:02–01:04 UTC). Os sete containers iniciaram saudáveis e as três aplicações responderam readiness UP. Uma OS foi aberta pela API real. O comando real `diagnostic.start.v1` chegou ao Execution, mas foi rejeitado por incompatibilidade de serialização de `occurredAt` e encaminhado à DLQ.

O fluxo foi interrompido antes de qualquer intervenção nos serviços, conforme a restrição de apresentar causa raiz e correção mínima antes de prosseguir. **Não foram executados o fluxo financeiro, os replays, a concorrência, a queda do broker ou o cenário de recusa.** Não confundir este resultado com os testes de integração internos da etapa 7.4.

## 1. Revisões e preservação das fontes

Os HEADs locais foram comparados com `git ls-remote ... refs/heads/homolog` durante a preparação e coincidiram:

| Serviço | Commit homolog efetivamente construído |
| --- | --- |
| OS | `b4c98bc08d573534dc680ab26ad5edfcba9fbb38` |
| Execution | `c1769c390825d12f35841e1b0d8f9508afa926a1` |
| Billing | `aa72d36da9ad1b516ed7ee9b6632ed26b3740f67` |

Foram lidos os contratos desses snapshots: OS `docs/events.md`, `docs/saga.md`, serializers, configuração Jackson e topologia; Execution `docs/messaging.md`, codec, consumer e APIs; Billing APIs, segurança e mensageria. Os exemplos não substituíram a inspeção do JSON realmente publicado.

O checkout do OS já continha alterações locais em `docs/events.md`, nos dois serializers e em testes, além de um teste não rastreado. **Não foram criadas por esta execução, incorporadas à imagem, sobrescritas nem validadas.** Os três builds usaram exclusivamente snapshots `git archive HEAD`. Não houve checkout, commit, push ou mudança no código/configuração dos serviços. As únicas adições são este relatório, evidências e o harness externo `integration/7.4.1/` no repositório Billing.

## 2. Arquitetura Docker isolada

Projeto novo: `billing-e2e-20261020-215858-f96d90`.

```text
host 127.0.0.1
  :18080 -> OS -------- PostgreSQL oficina_os
  :18081 -> Execution - MongoDB oficina_execution, replica set rs0
  :18082 -> Billing --- PostgreSQL oficina_billing
                 \        |        /
                    RabbitMQ único
                  oficina.commands
                  oficina.events
  :15675 -> management do broker de teste
```

Todos os serviços compartilham somente a rede privada do projeto e o broker. Cada aplicação aponta exclusivamente para seu próprio banco. Bancos e AMQP não têm portas publicadas no host. As três aplicações e management são expostas somente em loopback. Credenciais fixas são exclusivamente locais/sintéticas e estão no Compose do harness, não neste relatório.

Os Dockerfiles originais foram usados sem alteração; imagens das aplicações foram reconstruídas dos snapshots. Infraestrutura: `postgres:17-alpine`, `mongo:8.0` e `rabbitmq:4-management-alpine`. Foram criados quatro volumes novos com prefixo do projeto: `os-data`, `billing-data`, `mongo-data`, `rabbit-data`. Não foram reutilizados dados de tentativas anteriores.

| Sufixo do container, após o prefixo do projeto | Estado capturado | HTTP readiness |
| --- | --- | --- |
| `os-1` | running / healthy | UP em 18080 |
| `execution-1` | running / healthy | UP em 18081 |
| `billing-1` | running / healthy | UP em 18082 |
| `os-db-1` | running / healthy | pg_isready |
| `billing-db-1` | running / healthy | pg_isready |
| `mongodb-1` | running / healthy | rs0, writable primary |
| `rabbitmq-1` | running / healthy | rabbitmq-diagnostics ping |

Flyway do OS: V1–V4 com success=true. Billing: V1–V3 com success=true; Hibernate/JPA iniciou nas duas aplicações PostgreSQL. Mongo confirmou `replicaSet=rs0` e `primary=true`. O banco de OS tinha **zero ordens** antes dos cadastros.

Os seis containers normais encontrados antes da preparação permaneceram em execução, com os mesmos IDs. Não houve stop, restart, alteração de rede ou remoção de volume desses ambientes. O ambiente E2E permanece ativo para inspeção; o harness documenta sua parada por nome de projeto, sem afetar os demais.

## 3. IDs e cadastros reais

Cadastros sintéticos realizados com HTTP Basic pela API autenticada do OS:

| Identificador | Valor |
| --- | --- |
| Cliente | `7d236980-fbb2-4d9d-aa4b-a31096fd4254` |
| Veículo | `430bf1bd-7c25-4316-8af7-d7383fecfc55` |
| Filial | `3b8b2b27-6629-44ca-a033-816aca080b55` |
| OS | `be45668d-a320-485a-95d5-cd924a4a256c` |
| Saga | `2f32de03-80fa-4e97-bae2-72ebc4f10a02` |
| CorrelationId | `390b2176-33ff-4e6d-acf9-15aa2259d85c` |
| EventId diagnostic.start | `10099661-f9b8-424f-8308-5a7ccf689bc3` |

Nenhum status foi alterado diretamente no banco. As consultas SQL/Mongo usadas no diagnóstico foram somente leitura.

## 4. Sequência efetivamente observada

| Horário UTC | Evidência |
| --- | --- |
| 01:02:49.571 | Banco novo: zero OS; readiness UP nos três serviços |
| 01:02:51.395 | `POST /api/v1/ordens-servico` cria OS RECEBIDA |
| 01:02:51.423 | Saga persistida WAITING_DIAGNOSTIC / DIAGNOSTIC |
| 01:02:51.461 | Comando diagnostic.start.v1 registrado na Outbox do OS |
| 01:02:55.268 | Publisher do OS inicia publicação do eventId acima |
| 01:02:55.304 | Log de publicação confirmada; Outbox PUBLISHED |
| 01:02:55.354 | Execution registra `result=rejected`, antes de construir o comando tipado |
| 01:02:55.372 | Execution encaminha o mesmo messageId para DLQ, `attempt=1`, `result=dlq` |
| 01:04:12.560 | Probe termina com status BLOCKED, sem diagnóstico criado |

O comando passou pelo binding `oficina.commands` → `execution.diagnostic.start`, routing key `diagnostic.start.v1`. A evidência na DLQ mantém a mesma routing key no exchange `execution.commands.dlx`. Mensagem persistente (`delivery_mode=2`), `execution-attempt=1`, `execution-result=rejected`.

Não houve `diagnostic.started.v1`, `diagnostic.completed.v1` ou qualquer evento financeiro. A publicação confirmada e o status PUBLISHED da Outbox comprovam aceitação pelo broker, **não sucesso do processamento pelo consumidor**.

### Filas e conexões

As conexões dos três IPs de aplicações foram observadas no mesmo broker/vhost `/`, destino `172.23.0.4:5672`.

| Fila | Consumidores | Mensagens prontas |
| --- | --- | --- |
| execution.diagnostic.start | 1 | 0 |
| execution.repair.start | 1 | 0 |
| execution.repair.cancel | 1 | 0 |
| billing.quote.create | 2 | 0 |
| billing.payment.request | 2 | 0 |
| os.saga.events | 1 | 0 |
| execution.commands.dlq | 0 | **1** |
| billing.commands.dlq / os.saga.events.dlq | 0 | 0 |

Os bindings corretos de quote.create/payment.request e `oficina.events` → `os.saga.events` (`#.v1`) foram verificados. Não houve PRECONDITION_FAILED ou indicação de broker isolado incorreto. A leitura da DLQ reencaminhou a própria mensagem com `ack_requeue_true`, sem removê-la definitivamente ou republicá-la como sucesso.

## 5. Causa raiz comprovada e correção mínima proposta

Trecho **real** do envelope persistido/publicado, sem conversão para string:

```json
"occurredAt": 1792544571.431446804
```

O contrato do OS exemplifica `occurredAt` como string ISO-8601. O codec homolog do Execution exige expressamente texto e executa `Instant.parse(requiredText(root, "occurredAt"))`. Um JSON number é rejeitado antes da transação Inbox. Por isso os logs iniciais do consumidor têm IDs nulos; os IDs foram recuperados do envelope original na Outbox/DLQ, não inventados a partir dos logs.

Referências do commit construído:

- OS `infrastructure/config/JacksonConfig.java:12`: `new ObjectMapper().findAndRegisterModules()` sem desabilitar WRITE_DATES_AS_TIMESTAMPS do Jackson 2.
- OS `application/usecase/DiagnosticStartMessageSerializer.java:26`: serializa o envelope diretamente com esse mapper.
- OS `application/saga/SagaMessageSerializer.java:23`: usa o mesmo caminho para comandos posteriores; o risco nos comandos posteriores foi identificado por inspeção, **não executado neste cenário**.
- Execution `infrastructure/messaging/MessageCodec.java:42` e `:57`: valida string e interpreta ISO-8601.

**Correção proposta, não aplicada:** os dois serializers do OS devem usar um ObjectWriter dedicado:

```java
objectMapper.writer().without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
```

As chamadas de serialização desses envelopes passam a usar esse writer, sem alterar o mapper compartilhado nem as APIs REST. Adicionar testes que exijam nó JSON textual ISO-8601 em diagnostic.start e comandos subsequentes, usando a configuração Jackson real do OS.

Não relaxar o consumidor para aceitar números, não transformar a mensagem original mantendo eventId e não atualizar Outbox/OS/Saga manualmente. As alterações locais pré-existentes no OS relacionadas a esse tema precisam de revisão/validação e autorização explícita antes de serem usadas. Após a correção autorizada, repetir o fluxo completo em **novo projeto e novos volumes**, com a revisão corrigida identificada no relatório.

## 6. Estados finais e validação financeira

| Recurso | Resultado real |
| --- | --- |
| OS | RECEBIDA, version=0, startedAt/finishedAt nulos |
| Saga | WAITING_DIAGNOSTIC, currentStep=DIAGNOSTIC, completedAt nulo |
| Outbox OS | 1 diagnostic.start.v1, PUBLISHED, last_error nulo |
| Execution | 0 diagnósticos, 0 reparos, 0 Inbox, 0 Outbox |
| Billing | 0 orçamentos, 0 aprovações, 0 pagamentos, 0 tentativas, 0 Inbox, 0 Outbox |

Não há orçamento/itens, aprovação, reparo ou pagamento PENDING a apresentar: o fluxo não chegou a essas etapas. A integridade financeira distribuída **não foi validada**. Não foi gerado payment.confirmed, não houve finalização da OS, integração Mercado Pago, chamada financeira externa ou evento de sucesso artificial.

## 7. Cenários planejados, NÃO executados

Todos dependem da correção autorizada do contrato inicial. Esta lista não é evidência de aprovação.

### Fluxo principal e valores financeiros

1. Repetir cadastros e abertura em banco novo; confirmar WAITING_DIAGNOSTIC, consumo válido, diagnostic.started e OS EM_DIAGNOSTICO.
2. Consultar `/api/v1/diagnosticos?osId=...`; adicionar itens com POST autenticado `/api/v1/diagnosticos/{id}/itens` e concluir por `/{id}/conclusao`. As APIs /api do OS e Execution dispensam CSRF nos commits inspecionados; Basic continua obrigatório.
3. Usar estes **dados planejados**, com strings decimais, sem arredondamento arbitrário:

| itemId | tipo / unidade | quantidade | precoPrevisto | Total BRL esperado |
| --- | --- | --- | --- | --- |
| SVC-E2E | SERVICO / HORA | 1.250000 | 123.456000 | 154.32 |
| PECA-E2E | PECA / UNIDADE | 2.500000 | 12.340000 | 30.85 |
| MAT-E2E | MATERIAL / KIT | 0.750000 | 19.960000 | 14.97 |

Total esperado **BRL 200.14**. Comparar os decimais exatamente com BigDecimal/decimal, itemIds, descrição, tipo e unidade no diagnóstico, evento original, orçamento persistido e API; não usar double.

4. Capturar diagnostic.completed → quote.create → quote.created, o quoteId real e OS AGUARDANDO_APROVACAO / Saga WAITING_APPROVAL.
5. No Billing, GET financeiro autenticado com sessão de cookies; preservar o header X-CSRF-TOKEN e o JSESSIONID da mesma sessão. POST `/{quoteId}/aprovar` com Basic, sessão e token, responsavelId somente como identificação provisória. Verificar correlação original e uma única aprovação + Outbox na mesma transação.
6. Acompanhar quote.approved → repair.start → repair.started, consultar `/api/v1/reparos?osId=...` e verificar OS EM_EXECUCAO. Concluir reparo pelo POST autenticado `/{repairId}/conclusao`.
7. Capturar repair.completed → payment.request; verificar pagamento lógico único por orçamento, BRL 200.14, PENDING, tentativa inicial PENDING e chave idempotente. OS AGUARDANDO_PAGAMENTO / Saga WAITING_PAYMENT; nenhuma confirmação fictícia nem FINALIZADA.

### Idempotência e concorrência

- Capturar os **bytes originais** das Outboxes/filas observadoras antes de republicar quote.create, payment.request, quote.created, quote.approved e repair.completed com seus próprios eventIds originais. Nunca recriar envelopes com novas datas/payloads sob o mesmo ID.
- Comparar snapshots de contagem, IDs, versões, decisões, itens, pagamentos, tentativas e Outboxes antes/depois da reentrega; aguardar processamento/ACK, não apenas a resposta de publish.
- Para concorrência de criação original, interromper temporariamente somente o consumidor Billing do projeto de teste antes de diagnostic.completed, capturar o quote.create real sem processamento e entregar duas cópias exatas simultaneamente. Aguardar conclusão dos dois consumidores e verificar um orçamento, um conjunto de itens, uma Inbox finalizada e um quote.created único. Não chamar replay sequencial de teste concorrente.
- Em orçamento ainda pendente, realizar duas aprovações concorrentes com sessões/CSRF válidos, exigir uma decisão e uma Outbox; registrar os códigos HTTP reais, incluindo o conflito esperado.

### Resiliência

- Após orçamento criado, parar somente o RabbitMQ desse projeto, aprovar por REST e comprovar decisão/Outbox preservadas enquanto indisponível. Registrar eventId/status/attempts e reiniciar o mesmo broker antes do esgotamento do retry; não apagar o volume.
- Aguardar publicação, deduplicação, reparo e pagamento PENDING; verificar ausência de perda de alterações confirmadas e IDs preservados. Esse cenário de interrupção **não foi executado** na baseline bloqueada.

### Recusa em outro ambiente novo

- Novo projeto/volumes; nova OS até WAITING_APPROVAL; rejeitar via REST autenticado e CSRF real, com motivo não vazio.
- Capturar quote.rejected; exigir OS CANCELADA / Saga COMPENSATED, uma decisão REJECTED, nenhum repair.start para aquela OS, nenhum reparo/cobrança. Não consultar somente filas vazias para inferir ausência de evento: consultar Outboxes e histórico persistidos também.

## 8. Reprodutibilidade e evidências

- Harness e comandos: [integration/7.4.1/README.md](../integration/7.4.1/README.md).
- Compose adicional: [integration/7.4.1/compose.yaml](../integration/7.4.1/compose.yaml). Nenhum Compose original foi alterado.
- Evidência selecionada: [baseline-blocked.json](evidence/7.4.1/baseline-blocked.json), com revisões, IDs, estados, filas, bindings, migrações, logs e envelope original **como string**, preservando inclusive os dígitos do timestamp numérico.
- Envelope na DLQ: 539 bytes UTF-8, SHA-256 `c9f51f2bd956ce7a8adbdf8d5b094938dd0bab39d139ca4b3ad34a0e3a0e1b8b`.
- Artefatos brutos locais: `target/integration-7.4.1/20261020-215858-f96d90/`, incluindo manifesto, snapshots, build, logs completos, Outbox OS, DLQ, contagens e readiness. `Maven clean` remove esse diretório; a evidência selecionada permanece em docs.
- `prepare.ps1`: concluído com sucesso na execução registrada. `probe.ps1`: saída não zero intencional ao detectar **falha real do fluxo distribuído**, status BLOCKED. `export-evidence.ps1`: executado para gerar a evidência selecionada.

Problemas do próprio harness encontrados e corrigidos antes da abertura da OS: política PowerShell restrita (Bypass somente no processo), stderr informativo do Docker sendo interpretado como erro terminante e conversão CRLF do git archive sob configuração Windows. A exportação final usou LF; não houve correção de código de serviço para contornar o resultado.

Nenhuma suíte Maven dos serviços foi reexecutada nesta etapa: não houve alteração de código de aplicação, e `clean` apagaria as evidências locais. Não é apresentada nova medição JaCoCo nem os testes internos anteriores como substitutos de E2E real. **Próximo passo exige autorização para a correção mínima do OS e repetição em ambiente limpo.**
