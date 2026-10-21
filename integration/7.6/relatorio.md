# Etapa 7.6 — Saga completa com Mercado Pago HTTP simulado

Data: **21/10/2026**. Branch dos três serviços: **homolog**.

## Resultado e escopo

O critério principal foi comprovado pelo processamento real das aplicações: **Pagamento CONFIRMED → payment.confirmed.v1 via RabbitMQ → OS FINALIZADA → Saga COMPLETED**.

O harness distribuído terminou com **409 verificações aprovadas**, em cinco cenários independentes por OS. Foram observados **54 envelopes reais, 54 eventIds distintos**, no broker compartilhado: 20 comandos do OS, 20 eventos do Execution e 14 eventos do Billing. Não houve publicação de payment.confirmed pelo harness, atualização manual de status em bancos ou cobrança real.

Essa aprovação do E2E **não apaga falhas das suítes Maven**. A primeira execução de clean verify do Billing teve dois testes falhos; a reexecução isolada, sem mudanças de fontes, continuou com **um teste falho**. Portanto, a **validação global permanece BLOQUEADA**, embora o critério distribuído principal tenha sido atingido.

## Revisões e ambiente

HEAD local conferido com refs/heads/homolog remoto por git ls-remote, sem checkout, commit, push ou alteração dos outros serviços:

| Serviço | Commit |
| --- | --- |
| OS | 665aea6bffc92d2d2d6183a56d20393745d208b4 |
| Execution | c1769c390825d12f35841e1b0d8f9508afa926a1 |
| Billing | 616dd5276f36a66458fbef96f27ec3f5ae708faa |

Foram consultados os contratos events.md/saga.md, SagaEventHandler/SagaStateMachine do OS; messaging.md, CommandHandler/OutboxPublisher do Execution; messaging.md/mercado-pago.md, PaymentWorkflow, ProviderTransactions, adapter, verificador HMAC e controllers/configurações de segurança atuais do Billing. Os contratos utilizados vieram desses snapshots, não de exemplos presumidos.

Projeto executado: **billing-e2e76-20261021-125335-4c899f**. Os oito containers ficaram healthy antes do fluxo, com readiness UP dos três serviços:

| Serviço Compose | Tecnologia / isolamento |
| --- | --- |
| os | Spring Boot 4.1.1, Java 21, imagem do Dockerfile original |
| os-db | PostgreSQL 17, banco/volume próprios oficina_os |
| execution | Spring Boot 4.1.1, Java 21, imagem original |
| mongodb | MongoDB 8.0, replica set rs0, PRIMARY comprovado |
| billing | Spring Boot 4.1.1, Java 21, imagem original |
| billing-db | PostgreSQL 17, banco/volume próprios oficina_billing |
| rabbitmq | RabbitMQ 4 management, único broker do E2E, vhost / |
| mercado-pago-mock | Python 3.13/stdlib, HTTP determinístico, decimais exatos |

Portas somente em loopback: OS 21080, Execution 21081, Billing 21082, mock 21099, Rabbit management 15680. Bancos e AMQP não foram publicados no host. Cada preparação gerou rede e volumes novos. Os brokers normais existentes não foram utilizados pelos serviços do E2E.

O mock compartilha somente o namespace de rede do Billing (`network_mode: service:billing`), permitindo http://127.0.0.1:8099 sem alterar o allowlist do adapter. allow-insecure-local foi ativado exclusivamente pelo Compose E2E, via SPRING_APPLICATION_JSON. Credenciais Mercado Pago fictícias foram geradas por execução em artifacts ignorado pelo Git. HMAC, Basic, CSRF, leases, constraints e idempotência permaneceram habilitados. A role BILLING_OPERATOR foi configurada apenas para o operador de testes.

Flyway foi validado nos dois PostgreSQL, incluindo V1–V4 no Billing; as aplicações iniciaram com Hibernate validate. Foram conferidos PRIMARY, bindings/exchanges, consumidores ativos e filas inicialmente vazias. Evidências: manifest.json, containers.jsonl, arquivos *-migrations.json e preflight-*.json em evidence/.

## Cenários executados

| Cenário | Billing final | OS final | Saga final | Resultado |
| --- | --- | --- | --- | --- |
| Approved | CONFIRMED | FINALIZADA | COMPLETED | Aprovado |
| Rejected | FAILED | AGUARDANDO_PAGAMENTO | WAITING_PAYMENT | Aprovado |
| BrokerOutage | CONFIRMED | FINALIZADA | COMPLETED | Aprovado após recuperação |
| UnknownRecovered | CONFIRMED | FINALIZADA | COMPLETED | Aprovado; preferência recuperada |
| UnknownUnproven | PENDING, checkout UNKNOWN | AGUARDANDO_PAGAMENTO | WAITING_PAYMENT | Aprovado; retenção segura sem inventar resultado |

Todos percorreram cadastro HTTP de cliente/filial/veículo, abertura da OS, diagnóstico com itens, orçamento, aprovação REST autenticada/CSRF, reparo concluído por REST e intenção PENDING criada pelo comando payment.request. Nenhum dado financeiro foi inserido diretamente para preparar esses cenários HTTP.

### IDs das execuções

| Cenário | OS | Saga | Pagamento |
| --- | --- | --- | --- |
| Approved | b3bf9e28-d426-4c44-9c8a-959df30a6964 | 9b0d6a72-3b94-4124-8add-e256b6aed736 | f010f16a-f8cb-4949-8dc8-9ed11096dcb8 |
| Rejected | 28038d3e-736e-4106-9149-dc9f87ab9f94 | 1ac679c3-0277-4d60-b702-b776c248004a | 0f3fd0d0-49c5-4062-b2bd-4e1d5a7fb863 |
| BrokerOutage | f6ec3f17-9799-443d-b0ea-e29306f860e0 | e46bd17b-916a-4a94-8acf-34dee75e686d | 60355122-dd62-4c36-9936-d9cc25c98c67 |
| UnknownRecovered | 9e8cf12f-ed1d-4ef7-a133-5f6d5405a2ff | d32f66df-0428-4a6d-b593-5984251002df | dbf939b6-1877-468c-80e9-84e53f336782 |
| UnknownUnproven | b83fb8eb-3e7a-48da-9bb5-c3e550d98f92 | 65890f0a-bcb7-441a-b6af-65b1827fd2d8 | 337c8e75-7308-408c-9aad-7f7b4cbf7305 |

No cenário principal:

- Diagnóstico: 1b5685d5-b5cf-402d-8ed7-f02a45995bfe.
- Orçamento: d3be50a0-d4b9-416e-bfb4-3167d81cb4b2.
- Aprovação única: c8a31164-58f6-47a1-a9e9-f90852becc4c, 2026-10-21T15:56:05.497750Z.
- Reparo: 3d28f65b-c133-4a2f-b56a-868244c1b0fc.
- Tentativa única: 8fbc8f47-390d-4fdc-8ff0-862468f48651.
- correlationId: 41660435-d63d-46cc-9f63-edb7e5409742, preservado nos eventos/decisão.
- externalReference: billing:d3be50a0-d4b9-416e-bfb4-3167d81cb4b2.
- idempotencyKey: e48f4796-1a5a-3e68-992e-eae93b02540e.
- preferenceId: e2e-preference-1; providerPaymentId: 200001 (recurso do mock, não do Mercado Pago oficial).

IDs complementares e registros de todos os cenários estão em evidence/result.json. O DTO HTTP não expõe todos os identificadores operacionais; os identificadores de auditoria foram lidos diretamente do PostgreSQL, sem alterar dados nem criar endpoints.

## Sequência real de eventos

```text
diagnostic.start.v1 → diagnostic.started.v1 → diagnostic.completed.v1
→ quote.create.v1 → quote.created.v1 → aprovação REST → quote.approved.v1
→ repair.start.v1 → repair.started.v1 → conclusão REST → repair.completed.v1
→ payment.request.v1 → Pagamento PENDING → checkout HTTP
→ estado consultado no mock → payment.confirmed.v1
→ Inbox OS PROCESSED → OS FINALIZADA / Saga COMPLETED
```

O cenário Rejected termina em payment.failed.v1 e não finaliza a OS. Pending e in_process foram efetivamente consultados no cenário principal: não produziram sucesso; in_process levou a PROCESSING. Os webhooks não forneceram status confiável para o domínio: o Billing fez GETs autenticados ao mock, resolveu merchant order/preferência, validou valor, BRL, referência, metadata, collector_id e live_mode=false. As chamadas estão em evidence/mock-state.json e o histórico externo conhecido em evidence/provider-ledger.json.

Cada mensagem auditada teve routing key igual a eventType, source compatível, occurredAt textual ISO-8601 e IDs/correlação da OS/Saga original. O paymentId publicado corresponde ao pagamento lógico do Billing, não ao número externo. Os eventIds financeiros estão comprovados na Inbox PROCESSED do OS. Nenhuma mensagem financeira de sucesso foi criada pelo harness.

## Integridade financeira

Os cinco orçamentos utilizaram os mesmos valores representativos, cada um com seus próprios IDs:

| Item | Quantidade | Preço unitário BRL | Total BRL |
| --- | --- | --- | --- |
| SERVICO / HORA | 1.250000 | 123.456000 | 154.32 |
| PECA / UNIDADE | 2.500000 | 12.340000 | 30.85 |
| MATERIAL / KIT | 0.750000 | 19.960000 | 14.97 |
| Total | — | — | **200.14** |

Quantidade e preço conservaram as strings/escala do diagnóstico. Os totais foram comparados com System.Decimal no harness e Decimal no mock, sem double/float e sem arredondamento arbitrário. O JSON financeiro do Billing usa strings decimais; a API simulada entrega números JSON decimais exatos ao adapter e converte decimais em strings somente na exportação administrativa de evidências.

Conferidos valor/moeda da preferência e do pagamento externo, valor aprovado/local, OS, Saga, correlação, referência, chave idempotente e tentativa. Foram criados **5 pagamentos lógicos, 5 tentativas e 5 preferências**, um conjunto por orçamento. Apenas 4 recursos de pagamento externo foram criados no mock; o quinto caso retém uma preferência UNKNOWN sem resultado financeiro.

## Idempotência, segurança e concorrência

- Duas criações de checkout concorrentes retornaram **200 e 409**, com uma única preferência válida. Reuso posterior devolveu a mesma preferência.
- Dois webhooks idênticos/assinados concorrentes retornaram 200. Reentrega do mesmo manifesto e nova notificação sobre a aprovação já conhecida não criaram nova tentativa, preferência ou confirmação lógica, nem incrementaram novamente a versão da Saga concluída.
- Permaneceram **7 notificações duráveis PROCESSED** no conjunto dos cenários. O número inclui recursos distintos e notificações novas; entregas idênticas foram deduplicadas.
- Basic ausente foi rejeitado com 401 na consulta financeira; POST sem CSRF com sessão limpa foi rejeitado com 403, mantendo a proteção efetiva do checkout e da aprovação.
- Assinatura inválida, request-id ausente e timestamp expirado retornaram 401; body/query com IDs divergentes, JSON malformado e tipo incompatível retornaram 400. Não criaram recibos ou sucesso financeiro.
- A concorrência transacional entre processNotification e processDue foi exercitada com barrier/threads na suíte ProviderIntegrationTests, **não por dois workers distribuídos neste Compose**. Esse teste passou na execução Maven, embora outros dois testes da mesma classe tenham falhado na primeira suíte. Não se apresenta o simples overlap de webhook HTTP e scheduler como prova de duas transações de conciliação concorrentes.
- Não foi induzida perda de publisher-confirm após aceite no broker, nem republicado manualmente payment.confirmed. O E2E comprovou retry real por indisponibilidade e preservação do envelope; não promete exactly-once ou comprovação E2E de todas as janelas de duplicação de transporte.

## Recuperação

### Broker indisponível

Somente o RabbitMQ do projeto E2E foi parado. O pagamento 60355122-dd62-4c36-9936-d9cc25c98c67 foi confirmado pelo fluxo HTTP real durante a interrupção. A Outbox e97e5770-82ef-44af-a5e6-bc5c28abd6de permaneceu PROCESSING, attempts=1, enquanto OS/Saga ainda aguardavam pagamento. Após restaurar o broker, o publisher publicou com o mesmo eventId/envelope; o OS consumiu e chegou a FINALIZADA/COMPLETED, sem desfazer a confirmação financeira.

### Resultado desconhecido

O mock persistiu a preferência antes de atrasar a resposta por 8s, acima do timeout HTTP de 3s. Billing respondeu **202/UNKNOWN**. No caso recuperável, a pesquisa por externalReference encontrou a preferência existente; somente um POST externo foi necessário e a Saga depois concluiu por confirmação verificada. No caso não demonstrável, a pesquisa foi ocultada deliberadamente: o Billing manteve UNKNOWN/PENDING e não repetiu a criação às cegas. Nenhum estado foi forçado no banco.

## Confirmação tardia — evidência de aplicação, não E2E HTTP

Não existe operação HTTP autorizada para abrir uma segunda tentativa. Conforme a exceção permitida no enunciado, foi utilizado o teste existente lateApprovalOfTheFirstAttemptSettlesTheLogicalPaymentAndPreservesFailureHistory, executado com PostgreSQL/RabbitMQ reais, factories/adapters e provedor HTTP simulado.

Esse teste passou e verificou FAILED inicial, nova tentativa idempotente, confirmação verificada da tentativa antiga, preservação de previous_attempt com status FAILED, liquidação do pagamento lógico, fechamento das duas preferências, bloqueio de novo checkout e um único payment.confirmed. Uma segunda aprovação externa provocou MULTIPLE_EXTERNAL_SETTLEMENTS sem nova confirmação. O teste twoExternalApprovalsRequireReviewRatherThanAnIncorrectSagaResult também passou. Evidência sanitizada: evidence/application-integration.json.

Não se afirma ter concluído uma Saga distribuída por confirmação tardia com segunda tentativa através de HTTP: essa superfície ainda não existe e não foi criada artificialmente.

## Inbox e Outbox finais

| Serviço | Inbox | Outbox |
| --- | --- | --- |
| OS | 34 PROCESSED | 20 PUBLISHED |
| Execution | 10 PROCESSED | 20 PUBLISHED |
| Billing | 10 PROCESSED | 14 PUBLISHED |

Billing publicou 5 quote.created, 5 quote.approved, 3 payment.confirmed e 1 payment.failed. Não restaram Outboxes pendentes na coleta final; DLQs estavam vazias. A fila observadora e2e76.audit retém os 54 envelopes de evidência; não é uma fila de comandos financeiros pendentes. UNKNOWN é estado de checkout, não uma mensagem de sucesso descartada ou Outbox perdida. A entrega continua at-least-once.

## Maven e cobertura

Comando executado com Java 21 em cada repositório: `mvnw.cmd -B -ntp clean verify`.

| Serviço | Primeira execução | Testes | JaCoCo por linhas |
| --- | --- | --- | --- |
| OS | BUILD SUCCESS | 69, zero falhas/erros/ignorados | 87,77% (1134/1292) |
| Execution | BUILD SUCCESS | 119, zero falhas/erros/ignorados | 97,69% (718/735) |
| Billing | BUILD FAILURE | 193, 2 falhas, zero erros/ignorados | check não alcançado nessa execução |

Reexecução isolada do Billing, depois de parar os oito containers E2E e sem alterar fontes: **BUILD FAILURE às 13:05:46 -03:00, 193 testes, 1 falha, zero erros/ignorados**, em 4:00. A falha remanescente foi reconciliationFindsAnApprovalWithoutAnyWebhook. O teste de rollback passou nessa repetição; a variação confirma que não se deve esconder o problema repetindo a suíte até ficar verde.

Após a falha, `mvnw.cmd -B -ntp jacoco:report` foi executado somente para obter a cobertura observada: **97,72% (1373/1405 linhas)** no Billing, 86 classes analisadas. Isso **não equivale a clean verify aprovado**: o gate JaCoCo do ciclo verify não foi alcançado por causa da falha Surefire. evidence/maven.json registra buildSuccess=false e coverageGateReached=false para Billing, preservando a distinção.

Os dois testes falhos foram reconciliationFindsAnApprovalWithoutAnyWebhook (processDue retornou false) e outboxFailureRollsBackPaymentAttemptObservationAndNotificationProcessing (nenhuma confirmação na chamada imediata de retry). Ambos usam retryNow(), que grava prazos com now() do PostgreSQL, enquanto o worker compara com Clock Java. A primeira execução também coincidiu com a preparação Docker; o retry isolado foi diagnóstico, não correção ou apagamento do resultado anterior.

**Diagnóstico temporal confirmado no ambiente:** ClockProbe.java consultou um PostgreSQL 17 descartável em tmpfs, sem dados financeiros, utilizando JDBC 42.7.13 e Java 21 no Windows. Em **49 de 50 amostras**, o horário do banco ainda estava no futuro em relação a Instant.now() Java **depois de a resposta JDBC chegar**; maior diferença positiva: **1.341.500 ns (1,3415 ms)**. Essa divergência basta para retryNow() criar um prazo que o worker considera ainda não vencido. A primeira amostra teve maior latência de aquecimento; não foi omitida. Dados: evidence/clock-probe.json. O container de diagnóstico foi parado/removido automaticamente, com armazenamento temporário, sem tocar em volumes normais.

A menor correção proposta é tornar o helper retryNow determinístico: injetar/utilizar o mesmo Clock da aplicação, construir um Timestamp seguramente anterior (por exemplo, um segundo antes) e parametrizar os dois UPDATEs de prazo. Manter todas as asserções, limites, leases e regras financeiras. Não é necessário alterar o processamento de produção para esse sintoma.

Foi solicitada autorização para corrigir **somente o helper de teste**, usando o Clock da aplicação com prazo seguramente anterior. Sem autorização recebida nesta execução, **nenhum teste ou código de produção foi alterado**. Logs/Surefire da primeira tentativa foram preservados em artifacts; métricas selecionadas em evidence/maven-first-attempt.json. A reexecução está em evidence/maven.json e bootstrap-7.6-billing-retry-verify.log. Não foi feita terceira tentativa para obter verde por sorte.

## Problemas e correções do harness

1. Windows reservava as portas 24047–24146. A primeira preparação encontrou bind proibido; o harness passou a usar portas 21080/81/82/99. Os containers daquela preparação foram parados, sem apagar volumes.
2. Um teste negativo de CSRF inicialmente reutilizava X-CSRF-TOKEN guardado automaticamente em WebRequestSession.Headers pelo PowerShell. Confirmado comportamento do cliente (`Session retained CSRF header: True`) e comprovado POST sem CSRF em sessão limpa retornando 403. Corrigido exclusivamente o helper HTTP para remover o header herdado antes de montar cada request. Foi preparado outro ambiente com bases vazias e reexecutado o fluxo completo, resultando nas 409 verificações acima. **Não foi encontrada falha de CSRF no serviço.**
3. O override allow-insecure-local foi configurado pela chave canônica em SPRING_APPLICATION_JSON, preservando as validações existentes. O mock usa loopback compartilhado, não um host Docker arbitrariamente liberado no adapter.

Nenhuma funcionalidade nova, contrato, entidade, endpoint ou código de produção dos três serviços foi alterado. Os únicos ajustes são scripts/Compose/evidências de integração e exclusões de artifacts em Git/Docker.

## Reprodução, preservação e limites

Ver integration/7.6/README.md e executar prepare.ps1, run.ps1, export.ps1 **antes de parar o ambiente**, e stop.ps1 com o RunRoot gerado. Os scripts usam IDs próprios, polling com timeout, validação de HTTP/estados e exit code 1 para falha obrigatória. Logs brutos e snapshots/secrets fictícios ficam em artifacts ignorado pelo Git; evidências selecionadas sem headers/cookies/assinaturas ficam em evidence/.

Os relatórios/evidências anteriores 7.4.1 foram preservados; não foram reutilizados para compor esta aprovação. Os ambientes E2E foram parados após a coleta, sem down -v, prune ou operação nos volumes normais. O resultado da comparação de identidade dos containers normais e dos estados de parada está em evidence/cleanup.json.

Limitações explícitas: Mercado Pago **simulado**, sem teste oficial ou cobrança real; sem autorização de proprietário integrada; sem E2E HTTP de nova tentativa/confirmacão tardia; sem falha induzida de ACK do publisher após aceite; disputa dos workers comprovada na integração de aplicação, não entre duas instâncias no Compose; estabilidade da suíte Billing depende do diagnóstico temporal acima.

Próxima fase: resolver o helper temporal autorizado e repetir a validação global; homologar sandbox oficial com credenciais de teste/HTTPS fornecidos por canal seguro; planejar operação autorizada de nova tentativa, monitoramento/revisão financeira e testes multi-instância/ACK perdido, sem contornar Outbox, HMAC ou regras de domínio.
