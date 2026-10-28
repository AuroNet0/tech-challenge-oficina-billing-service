# Notificação interna de orçamento — etapa 9.1.3

## Inspeção e solução

`CommandHandler` consome `quote.create.v1`, identifica a OS pelo `osId` do envelope e cria o orçamento `PENDING_APPROVAL` a partir dos itens do diagnóstico. O orçamento mantém `osId` único e `sagaId`; o contrato RabbitMQ permanece igual. `ConsultarOrcamentoPort`, os DTOs financeiros e os endpoints de consulta/aprovação/rejeição continuam sendo reutilizados.

O contrato `FinancialAccessPort` autoriza uma OS por vez e não fornece uma lista de OS pertencentes ao usuário. A solução mínima compatível é uma consulta de notificações com `osId` obrigatório. Não há caixa global de notificações de todas as OS. A OS é apenas um filtro: o parâmetro não comprova propriedade.

## Identidade e autorização

`GET /api/v1/notificacoes` exige JWT válido e papel `CLIENTE`. O ator é exclusivamente o `sub` autenticado. Antes de consultar qualquer registro, inclusive uma lista vazia, `FinancialAuthorization.require(osId, DECIDE)` exige autorização positiva atual. `DECIDE` é reutilizado como verificação de propriedade porque `READ` também permite escopo operacional de funcionários. Consultar essa autorização não decide nem altera o orçamento; o contrato existente verifica propriedade, sem depender do status financeiro.

No perfil `mtls`, `MtlsFinancialAccessAdapter` encaminha o Bearer original ao endpoint HTTPS confiável `/internal/financial-authorization` do OS, validando ator, OS, ação, correlação e `Cache-Control: no-store` da resposta. O OS consulta o vínculo atual via Auth. Atendentes, gerentes e mecânicos sem papel `CLIENTE` recebem 403; múltiplos papéis não dispensam a propriedade exigida por `DECIDE` no OS. Nenhum `clienteId`/`usuarioId` enviado pela API é usado como identidade.

Não há consulta direta aos bancos de Auth/OS, cópia de dados pessoais, cache de autorização positiva ou alteração de contratos externos. Negação retorna 403; falha ou indisponibilidade retorna 503; ausência de autenticação retorna 401. O perfil padrão mantém o adapter indisponível e falha fechado: exige a configuração confiável já usada pelas demais APIs financeiras para liberar a consulta.

## Persistência, transação e idempotência

A migração incremental `V5__create_quote_notifications.sql` cria `notificacoes_orcamento` no PostgreSQL do Billing. Registra identificador, referência única ao orçamento, OS, tipo e data. Uma chave estrangeira composta `(orcamento_id, os_id)` garante que orçamento e OS correspondem. V1–V4 não são alteradas.

Na criação, a notificação é inserida após a persistência do orçamento e antes de `quote.created.v1`, dentro da mesma transação do `CommandHandler`, da Inbox e da Outbox. A operação JDBC participa da transação JPA existente. Qualquer falha de notificação, Outbox ou finalização provoca rollback, permitindo a repetição segura do comando.

O lock existente por OS serializa os comandos. A Inbox elimina a repetição do mesmo `eventId`; comandos equivalentes com outro `eventId` encontram o orçamento existente e não criam outra notificação. `UNIQUE (orcamento_id)` e `ON CONFLICT DO NOTHING` oferecem uma proteção adicional. O INSERT seleciona somente orçamento persistido em `PENDING_APPROVAL`; comandos inválidos e orçamentos terminais não geram um novo aviso pendente.

Ao aplicar V5, somente orçamentos já pendentes recebem um aviso inicial, datado da migração. Novos avisos usam a data de criação do orçamento. Não há reconstrução de avisos de orçamentos anteriormente aprovados/rejeitados/cancelados.

## API

```http
GET /api/v1/notificacoes?osId=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa&page=0&size=20
Authorization: Bearer <JWT do cliente proprietário>
```

```json
[
  {
    "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    "orcamentoId": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    "osId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    "tipo": "ORCAMENTO_DISPONIVEL",
    "createdAt": "2026-10-28T12:00:00Z",
    "mensagem": "Existe um orcamento aguardando sua aprovacao.",
    "statusOrcamento": "PENDING_APPROVAL"
  }
]
```

A resposta usa `Cache-Control: no-store`. A página começa em zero; tamanho padrão 20, mínimo 1, máximo 100. Ordenação por `created_at DESC, id DESC`; página sem resultados retorna `[]`. Parâmetros inválidos retornam 400. Como há um orçamento por OS e um aviso por orçamento, atualmente cada OS produz no máximo um resultado. A consulta é somente leitura e não marca avisos como lidos.

Após aprovação/rejeição/cancelamento, o mesmo aviso mantém identificador/data e retorna a situação atual do orçamento por JOIN, sem duplicar estado financeiro. A mensagem histórica passa a `Orcamento disponibilizado anteriormente; consulte a situacao atual.`; não afirma que um orçamento terminal ainda aguarda aprovação.

Os detalhes são consultados em `GET /api/v1/orcamentos/{orcamentoId}`. Aprovação e rejeição usam os endpoints existentes; cálculos, Saga, checkout, Mercado Pago e webhook permanecem nos fluxos atuais.

## Demonstração FIAP

1. Registrar o diagnóstico no fluxo existente da OS/Execution e aguardar `quote.create.v1` ser processado pelo Billing.
2. Autenticar o cliente proprietário no Auth e obter seu JWT válido.
3. Consultar `GET /api/v1/notificacoes?osId={osId}` com o Bearer; verificar referência ao orçamento e `PENDING_APPROVAL`.
4. Consultar `GET /api/v1/orcamentos/{orcamentoId}` usando a referência retornada.
5. Enviar `POST /api/v1/orcamentos/{orcamentoId}/aprovar` ou `/rejeitar`, com `Content-Type: application/json` e corpo `{}` (ou motivo na rejeição).
6. Consultar novamente o aviso: identificador/data preservados e situação atual `APPROVED` ou `REJECTED`. Outro cliente e funcionários não proprietários não obtêm o aviso.

## Validação e limites

Os testes existentes com Testcontainers PostgreSQL/RabbitMQ cobrem criação, referência, autorização, isolamento entre OS, autenticação, funcionários, paginação, histórico, mensagens repetidas, rollback e repetição após falha. A suíte completa inclui aprovação, checkout e webhook; o gate JaCoCo permanece em 80% de linhas, sem exclusões adicionais.

Executar com Java 21 e Docker disponível:

```bash
mvn clean verify
```

O mecanismo atende exclusivamente ao aviso interno consultável por API. Não há envio de e-mail, WhatsApp, SMS, push, entrega externa, frontend, confirmação de leitura ou garantia de que o cliente visualizou o aviso. Uma descoberta global por usuário exigiria contrato de listagem de OS autorizadas e não integra esta etapa. Não foram adicionados serviços, bancos, brokers, filas, deploy ou mudanças em CI/CD/Kubernetes/Terraform.

## Testes da etapa

| Requisito | Evidência automatizada |
| --- | --- |
| Criação, referência correta, leitura sem mutação | `FinancialApiTests.notificationReferencesCreatedQuoteAndReadDoesNotChangeFinancialState` |
| Proprietário, outro cliente e falha fechada | `FinancialApiTests.notificationAccessRequiresCurrentOwnerEvenForEmptyResults` |
| Autenticação e restrição a funcionários | `FinancialApiTests.notificationEndpointRequiresAuthenticationAndClientRole` |
| Escopo operacional com múltiplos papéis não basta | `FinancialApiTests.operationalScopeDoesNotGrantNotificationsToUserWithBothRoles` |
| Paginação, parâmetros e lista vazia | `FinancialApiTests.notificationPaginationIsBoundedAndEmptyListStillRequiresOwnership` |
| Histórico após aprovação/rejeição e repetição de criação | `FinancialApiTests.notificationHistorySurvivesDecisionAndRepeatedCreation` (dois casos) |
| Estado terminal não gera aviso pendente | `FinancialApiTests.terminalQuoteWithoutNotificationDoesNotGeneratePendingNotice` |
| Falha de inserção, rollback, retry e constraints | `FinancialApiTests.notificationWriteFailureRollsBackQuoteInboxAndOutboxAndAllowsRetry` |
| Migração de banco V4 com dados existentes | `BillingApplicationTests.upgradesExistingDatabaseAndNotifiesOnlyPendingQuotes` |
| RabbitMQ repetido, inclusive concorrência e outro eventId | `MessagingIntegrationTests.concurrentConsumersDeduplicateBothSameAndDifferentEventIds` ampliado |
| Falha posterior na Outbox desfaz também notificação | `MessagingIntegrationTests.quoteCreationRollsBackInboxItemsAndOutboxOnWriteFailure` ampliado |
| Diagnóstico inválido não notifica | `MessagingIntegrationTests.semanticInvalidQuoteHasAtomicFailureOutboxAndDlq` ampliado |
| Aprovação, checkout e webhook existentes | Suítes completas `FinancialApiTests`, `MessagingIntegrationTests`, `ProviderIntegrationTests` e `ProviderUnitTests` |

## Arquivos da etapa

Novos arquivos:

- `src/main/resources/db/migration/V5__create_quote_notifications.sql`
- `src/main/java/com/oficina/billing/domain/model/NotificacaoOrcamento.java`
- `src/main/java/com/oficina/billing/application/port/out/NotificacaoOrcamentoRepositoryPort.java`
- `src/main/java/com/oficina/billing/application/port/in/ConsultarNotificacoesPort.java`
- `src/main/java/com/oficina/billing/application/usecase/ConsultarNotificacoesUseCase.java`
- `src/main/java/com/oficina/billing/infrastructure/persistence/adapter/NotificacaoOrcamentoPersistenceAdapter.java`
- `src/main/java/com/oficina/billing/infrastructure/web/controller/NotificacaoOrcamentoController.java`
- `src/main/java/com/oficina/billing/infrastructure/web/dto/NotificacaoOrcamentoResponse.java`
- `docs/notificacoes-orcamento.md`

Arquivos existentes alterados:

- `README.md`
- `src/main/java/com/oficina/billing/application/usecase/CommandHandler.java`
- `src/main/java/com/oficina/billing/infrastructure/config/SecurityConfig.java`
- `src/test/java/com/oficina/billing/BillingApplicationTests.java`
- `src/test/java/com/oficina/billing/messaging/MessagingIntegrationTests.java`
- `src/test/java/com/oficina/billing/messaging/MessagingUnitTests.java`
- `src/test/java/com/oficina/billing/web/FinancialApiTests.java`

## Resultado da validação e Git

Validação final executada em 28/10/2026, com Microsoft JDK 21.0.12.1, Maven 3.10.0 e Testcontainers PostgreSQL/RabbitMQ:

- `mvn -B clean verify`: **BUILD SUCCESS**, 245 testes, zero falhas, zero erros, zero skips; duração de 4min01s.
- Foram adicionados 10 casos executados (incluindo os dois parâmetros de aprovação/rejeição) e ampliadas as verificações existentes de mensagens, rollback e schema.
- JaCoCo: **95,49% de linhas** (1.524 cobertas de 1.596); gate existente de 80% aprovado, sem alterações no POM ou exclusões. Instruções: 94,26%; branches: 72,22% (não é o contador do gate).
- Evidências locais: `bootstrap-9.1.3-verify.log`, `target/surefire-reports/` e `target/site/jacoco/index.html`; são artefatos locais ignorados pelo Git.
- A primeira execução encontrou somente a expectativa antiga de schema V4 no teste de bootstrap; a expectativa foi atualizada para V5 e a suíte completa foi repetida com sucesso.
- `git diff --check`: sem erros de whitespace.
- Branch final: `homolog`, acompanhando `origin/homolog`, sem alteração de branch. Árvore inicial limpa; ao final, sete arquivos existentes modificados e nove arquivos novos, listados nesta documentação. Nenhum arquivo staged, commit ou push.

A autorização remota foi simulada por `FinancialAccessPort` nos testes desta etapa, com JWTs assinados no suporte de testes existente. O novo endpoint não foi demonstrado contra OS/Auth reais nesta execução; reutiliza o contrato mTLS existente. Não houve deploy, Terraform ou modificação de outros serviços.
