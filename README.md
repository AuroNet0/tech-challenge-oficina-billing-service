# Oficina Billing Service

Tech Challenge FIAP — Fase 4, etapas 7.1 a 7.5. Microsserviço de orçamentos, decisões e cobranças, com PostgreSQL próprio e Inbox/Outbox RabbitMQ. A Saga cria a intenção PENDING; Checkout Pro de teste pode ser disponibilizado por operação autenticada, e somente consultas verificadas ao Mercado Pago geram resultados financeiros. Integração externa desligada por padrão, sem confirmação simulada.

## Checkout Pro — etapa 7.5

- [Documentação completa, segurança e roteiro oficial sandbox](docs/mercado-pago.md).
- POST `/api/v1/pagamentos/{id}/checkout`: Bearer RS256, ROLE_CLIENTE e propriedade atual da OS; não aceita valores/IDs externos no corpo. Retorna 200 READY ou 202 UNKNOWN; não cria outra intenção financeira.
- POST `/api/v1/webhooks/mercado-pago`: cadeia exclusiva sem JWT de usuario e sem CSRF, exigindo assinatura HMAC-SHA256 válida, headers/IDs consistentes e receipt durável antes de HTTP 200.
- Credenciais/URLs são variáveis `MERCADO_PAGO_*` descritas em `.env.example`, sem defaults secretos. `MERCADO_PAGO_ENABLED=false` e `MERCADO_PAGO_RECONCILIATION_ENABLED=false`; só o modo financeiro de testes é permitido nesta etapa.
- Reserva/HTTP/finalização, leases e pesquisa por referência protegem criação repetida/resultado desconhecido. Não se presume idempotência de POST /checkout/preferences. UNKNOWN não repete POST automaticamente.
- Webhook e reconciliação compartilham validação financeira e gravação atômica de pagamento/tentativa/receipt/Outbox. Confirmação tardia preserva o histórico; múltiplos créditos, valores divergentes e estados não mapeados exigem revisão.
- V4 adiciona `provider_sync`, `provider_checkouts`, `provider_notifications`, `provider_payments`, `provider_observations`; V1–V3 não são alteradas. Infraestrutura financeira continua JPA/PostgreSQL; o workflow especializado usa JDBC transacional, sem expor entidades.
- Não há confirmação manual, captura de cartão, integração de produção ou autorização definitiva por proprietário. Teste oficial com credenciais e HTTPS público permanece pendente; Maven usa API local simulada, PostgreSQL e RabbitMQ Testcontainers.

## Tecnologias e compatibilidade

Java 21, Spring Boot 4.1.1, Maven Wrapper 3.3.4/Maven 3.10.0, Web MVC, Data JPA, Validation, Security, Actuator, PostgreSQL 17, Flyway e Springdoc OpenAPI 3.1.1, Spring AMQP e RabbitMQ 4. Os testes utilizam Spring Test, Mockito, Spring Security Test, Testcontainers PostgreSQL/RabbitMQ 2.0.5 e JaCoCo 0.8.15.

Foi removida a fixação em JUnit 5.13.4 e adotado **JUnit Jupiter 6.0.3**, gerenciado pelo BOM do Boot 4.1.1. Na etapa 7.1, JUnit 5 exigia inicialização manual do contexto porque a extensão Spring utilizava uma API do JUnit 6. Agora são usados `@SpringBootTest`, `@AutoConfigureMockMvc` e `@MockitoSpyBean`, preservando os 18 cenários do bootstrap, com as verificações de schema atualizadas para V2. O ponto de entrada continua exercitado com `useMainMethod=ALWAYS`. Não foram rebaixados componentes Spring nem excluídas classes da cobertura.

## Arquitetura

`com.oficina.billing.domain` contém somente Java, sem dependências de Spring/JPA. As portas em `application.port.out` retornam modelos do domínio. A infraestrutura contém entidades JPA, repositórios Spring Data, mappers explícitos e adapters transacionais.

```text
src/main/java/com/oficina/billing/
├── BillingApplication.java
├── domain/
│   ├── model/        Orcamento, OrcamentoItem, Aprovacao, Pagamento,
│   │                 PagamentoTentativa, FinancialRules
│   ├── enums/        OrcamentoStatus, OrcamentoItemType, PriceUnit,
│   │                 AprovacaoStatus, PagamentoStatus
│   └── exception/    DomainException
├── application/port/in/  ConsultarOrcamentoPort, ConsultarPagamentoPort, DecidirOrcamentoPort
├── application/usecase/ ConsultarOrcamentoUseCase, ConsultarPagamentoUseCase, DecidirOrcamentoUseCase
├── application/port/out/
│   ├── OrcamentoRepositoryPort.java
│   ├── AprovacaoRepositoryPort.java
│   └── PagamentoRepositoryPort.java
└── infrastructure/
    ├── config/       SecurityConfig, OpenApiConfig, TimeConfig
    ├── web/          Controllers, DTOs, ProblemDetail e autorizacao JWT
    ├── messaging/    Codec, consumidor, broker, publisher e topologia RabbitMQ
    └── persistence/
        ├── entity/   Cinco entidades JPA
        ├── repository/
        ├── mapper/
        └── adapter/
src/main/resources/db/migration/
├── V1__bootstrap_schema.sql
├── V2__create_billing_domain.sql
└── V3__create_messaging_tables.sql
```

As entidades JPA são restritas à infraestrutura. Os agregados mutáveis são `Orcamento` e `Pagamento`; itens, decisões e snapshots de tentativas são imutáveis. Não há setters públicos de status, edição financeira ou exclusão pelos ports. As fábricas `restaurar` são destinadas à reidratação pelos mappers e validam a estrutura dos snapshots; o adapter e o banco também validam a elegibilidade da cobrança contra o orçamento persistido.

## Modelo financeiro e relacionamentos

| Tabela | Papel e restrições principais |
| --- | --- |
| `orcamentos` | Um orçamento por OS, `UNIQUE(os_id)`; IDs de OS/Saga externos são referências sem FK remota; `version BIGINT` |
| `orcamento_itens` | N itens por orçamento, FK local; `UNIQUE(orcamento_id, item_id)`; posição preserva a ordem |
| `aprovacoes` | No máximo uma decisão por orçamento; FK composta garante a mesma OS; decisão imutável |
| `pagamentos` | Um pagamento lógico por orçamento aprovado; FK composta garante orçamento/OS/Saga; `version BIGINT` |
| `pagamentos_tentativas` | Histórico de tentativas por pagamento, ordenado por posição; chave de idempotência única em todo o Billing |

Todos os IDs são UUID e as datas são TIMESTAMPTZ. A precisão de armazenamento dos instantes é a de microssegundos do PostgreSQL. Há índices para consultas por OS, Saga, orçamento/FKs e estados; as constraints únicas também fornecem índices. Não existem conexões, joins ou FKs para bancos externos.

V1 permanece intacta e executa `SELECT 1`. V2 cria as cinco tabelas, constraints, índices e funções/triggers de integridade. `spring.jpa.hibernate.ddl-auto=validate` e `spring.jpa.open-in-view=false` continuam ativos.

### Cálculo e precisão BRL

- Somente `BigDecimal`, nunca `double` ou `float`.
- Quantidade e preço unitário: `NUMERIC(19,6)`, até 13 dígitos inteiros e 6 decimais; valores de entrada com escala negativa ou acima de 6 são rejeitados, inclusive zeros excedentes.
- A quantidade é estritamente positiva. O preço unitário pode ser zero e representa o futuro `precoPrevisto` do diagnóstico, sem conversão monetária.
- A quantidade original é preservada no domínio. A coluna adicional `quantidade_escala` permite recuperar também a escala original após leitura do NUMERIC fixo.
- Totais de itens, orçamento e pagamento: `NUMERIC(19,2)`, até 17 dígitos inteiros e 2 decimais.
- O total do item é a multiplicação exata; o orçamento soma os totais dos itens. Nenhum total é recebido livremente pelas fábricas.
- A conversão para centavos usa `RoundingMode.UNNECESSARY`: frações de centavo e overflow são rejeitados. Não existe arredondamento implícito. Exemplo: `1.25 × 80.00 = 100.00`; `1 × 0.001` é rejeitado; `100 × 0.000100 = 0.01` é aceito.
- Somente BRL é permitido. Entradas devem passar pelo domínio antes da persistência: tipos NUMERIC fixos do PostgreSQL podem arredondar valores enviados diretamente por SQL. CHECKs também validam positividade e a igualdade exata do total do item.

### Orçamento e decisão

Um orçamento nasce `PENDING_APPROVAL`, com ao menos um item e sem `itemId` ou UUID de item duplicados. Pode avançar uma única vez para `APPROVED`, `REJECTED` ou `CANCELLED`. Todos são terminais para a decisão. Aprovação e rejeição registram `approvedAt` ou `rejectedAt`; cancelamento registra `updatedAt`. Decisões anteriores à criação são rejeitadas.

`Aprovacao.registrar` captura a decisão já tomada no orçamento, o instante, responsável opcional, motivo opcional e correlação opcional. Não cria identidade de cliente. O registro é imutável inclusive no banco.

Os casos de uso de aprovação/rejeição salvam orçamento e decisão **na mesma transação PostgreSQL** com `@Transactional`. Os adapters usam propagação REQUIRED e participam da transação do caso de uso. O relógio UTC é injetado; um único instante, truncado a microssegundos, é utilizado para a alteração do orçamento e os campos decisionAt/createdAt da decisão. Os testes REST comprovam commit, rollback e concorrência, preservando também os testes PostgreSQL anteriores.

Triggers de constraint, diferidos até o commit, verificam itens/total e correspondência entre estado e decisão. Isso permite inserir/atualizar os registros em uma única transação sem tolerar estado inconsistente após o commit. Triggers também bloqueiam alteração de orçamento terminal e de seus itens.

### Pagamento lógico e tentativas

`Pagamento.criar` exige um orçamento aprovado e copia seu valor, OS e Saga. O banco verifica novamente estado, valor e referências. Provider e referência externa são obrigatórios; não há chamada a qualquer provedor nesta etapa.

Um pagamento lógico possui N tentativas. A unicidade por orçamento evita dois pagamentos lógicos para o mesmo valor aprovado; **não há UNIQUE(os_id) em pagamentos**. Novas tentativas são registradas como novos filhos, sem apagar ou substituir a falha anterior.

Cada tentativa segue `PENDING → PROCESSING → CONFIRMED/FAILED`, admitindo confirmação ou falha diretamente de PENDING. CONFIRMED é terminal. `novaTentativa(novaChave, instante)` cria outro registro PENDING após falha, mantendo o mesmo pagamento lógico. Na etapa 7.5, liquidação tardia verificada pode corrigir FAILED para CONFIRMED, arquivando automaticamente o snapshot anterior em ledger imutável. O resumo segue a tentativa confirmada quando existir; caso contrário, a última tentativa.

ID/chave e histórico anterior são preservados. O banco impede alteração/exclusão terminal arbitrária e só admite a correção tardia com evidência approved correspondente já persistida, mantendo a imagem FAILED no ledger. Há no máximo uma tentativa não falha; o commit valida o resumo contra a tentativa efetiva. Uma tentativa local ativa supersedida pela liquidação é encerrada sem evento financeiro fictício. Não existe compensação de reparo físico.

### Idempotência e concorrência

A chave é obrigatória e única globalmente nas tentativas deste Billing, escopo conservador adequado ao uso futuro de UUIDs no provedor. A primeira cobrança tem sua própria chave; cada nova tentativa exige uma nova chave. Repetir `novaTentativa` com chave já conhecida é um no-op e não cria cobrança adicional.

`findByIdempotencyKey` resolve tanto a chave atual quanto qualquer chave histórica para o pagamento lógico. O consumidor consulta os registros existentes e valida Saga, itens do orçamento, valor do pagamento e repairId do comando original antes de deduplicar. A persistência não transforma conflito de chave com outro pagamento em sucesso: retorna violação de unicidade.

As operações ordinárias exigem a chave efetiva; a reconciliação verificada resolve explicitamente uma tentativa histórica pela preferência/metadata, sem presumir que seja a mais recente. A mesma confirmação preserva timestamp; outro crédito é retido para revisão. Referência externa e ID de confirmação continuam únicos no escopo do provider. As regras detalhadas de eventos atrasados, múltiplos pagamentos e expiração de preferências estão em docs/mercado-pago.md.

`@Version` protege ambos os agregados mutáveis. Concorrência entre aprovação e rejeição é resolvida por versão e unicidade da decisão no banco. O retorno de `save` deve ser utilizado, pois contém a nova versão. Não existem locks distribuídos nem transações entre microsserviços.

## API REST financeira — etapa 7.3

As portas de entrada ficam em `application.port.in`, os casos de uso em `application.usecase`, e controllers/DTOs/tratamento de erros em `infrastructure.web`. Controllers apenas validam o contrato HTTP, mapeiam DTOs e chamam portas. Modelos JPA não são serializados.

| Método e rota | Resposta |
| --- | --- |
| GET `/api/v1/orcamentos/{id}` | 200, orçamento completo; 404 se inexistente |
| GET `/api/v1/orcamentos?osId={uuid}` | 200, orçamento único da OS; 404 se inexistente |
| POST `/api/v1/orcamentos/{id}/aprovar` | 200, decisão APPROVED persistida |
| POST `/api/v1/orcamentos/{id}/rejeitar` | 200, decisão REJECTED persistida |
| GET `/api/v1/orcamentos/{id}/aprovacao` | 200, decisão original; 404 se orçamento ou decisão inexistente |
| GET `/api/v1/pagamentos/{id}` | 200, pagamento e tentativas; 404 se inexistente |
| GET `/api/v1/pagamentos?osId={uuid}` | 200, lista de pagamentos da OS; `[]` quando vazia |

Todas as rotas financeiras exigem autenticação. Não há POST de criação de orçamento/pagamento, edição de itens/preços, confirmação ou falha manual de cobrança. Orçamentos existentes nos testes são preparados com factories e adapters; não existe endpoint para gerar dados de teste.

### Requests e respostas

Aprovacao aceita objeto JSON; responsavelId e opcional, mas deve coincidir com JWT.sub:

```http
POST /api/v1/orcamentos/{id}/aprovar
Content-Type: application/json
X-Correlation-Id: 11111111-1111-4111-8111-111111111111
Authorization: Bearer <access-token>

{"responsavelId":"00000000-0000-4000-8000-000000000001"}
```

Recusa aceita também um motivo opcional:

```http
POST /api/v1/orcamentos/{id}/rejeitar
Content-Type: application/json
Authorization: Bearer <access-token>

{"responsavelId":"00000000-0000-4000-8000-000000000001","motivo":"Orçamento acima do esperado"}
```

`responsavelId` tem limite de 100 caracteres; `motivo`, de 500. Campos opcionais podem ser omitidos ou null; valores em branco são inválidos. O corpo JSON é obrigatório (`{}` é permitido). Campos desconhecidos, incluindo `valorTotal` e `itens`, são rejeitados com 400. Os casos de uso utilizam as transições existentes do domínio, sem receber totais ou itens do solicitante. `X-Correlation-Id` é UUID opcional, preservado na decisão e no evento. Sem header, reutiliza-se a correlação original de quote.create.v1 da mesma OS/Saga; sem Inbox original, utiliza-se fallback UUID determinístico derivado desses IDs, documentado em [mensageria](docs/messaging.md).

Exemplo ilustrativo de consulta de orçamento:

```json
{
  "id":"22222222-2222-4222-8222-222222222222",
  "osId":"33333333-3333-4333-8333-333333333333",
  "sagaId":"44444444-4444-4444-8444-444444444444",
  "status":"PENDING_APPROVAL",
  "itens":[{
    "id":"55555555-5555-4555-8555-555555555555",
    "itemId":"servico-1",
    "descricao":"Diagnóstico técnico",
    "tipo":"SERVICO",
    "quantidade":"1.2500",
    "unidadePreco":"HORA",
    "precoUnitario":"80.000000",
    "valorTotal":"100.00"
  }],
  "valorTotal":"100.00",
  "createdAt":"2026-10-20T12:00:00Z",
  "updatedAt":"2026-10-20T12:00:00Z",
  "approvedAt":null,
  "rejectedAt":null
}
```

Todos os valores monetários e quantidades são **strings decimais exatas**, produzidas com `BigDecimal.toPlainString()`. Não há conversão para double nem notação exponencial. Totais têm duas casas; o preço unitário recuperado do PostgreSQL tem seis, e a quantidade preserva sua escala original.

A resposta de decisão contém `id`, `orcamentoId`, `osId`, `status`, `decisionAt`, `responsavelId`, `motivo`, `correlationId` e `createdAt`. Os três instantes da operação (updatedAt do orçamento, decisionAt e createdAt da decisão) são iguais.

Pagamento retorna `id`, `osId`, `sagaId`, `orcamentoId`, `status`, `valor`, `moeda`, `provider`, timestamps e `tentativas` ordenadas. Cada tentativa informa seu ID, status e timestamps. A resposta usa uma lista explícita de campos permitidos: não expõe tokens, credenciais, cartões, chaves de idempotência, referência externa, IDs internos de pagamento no provedor nem texto livre de falhas do provedor, que pode conter informações sensíveis.

### Decisões repetidas e concorrência

A política é **409 para toda decisão repetida**, inclusive a mesma decisão, sem criar registro, incrementar versão ou alterar timestamps. Decisão oposta, orçamento cancelado e conflito otimista também retornam 409. A decisão original permanece consultável por GET e pela porta de consulta, inclusive para integrações futuras.

Somente PENDING_APPROVAL permite aprovação ou recusa. Os casos de uso são transacionais e reutilizam @Version, unicidade e constraint triggers de V2. Uma falha na gravação da decisão reverte também a alteração do orçamento. A disputa REST entre aprovação e recusa é testada com duas requisições concorrentes: apenas uma pode efetivar a decisão; a outra recebe 409. Não há transação distribuída.

**As decisões são persistidas atomicamente com sua Outbox e publicadas assincronamente em RabbitMQ.** Os eventos `quote.approved.v1` e `quote.rejected.v1` preservam IDs e correlação; a recusa sempre publica reason não vazio, mesmo quando motivo foi omitido. O OS Service continua sendo o único orquestrador; nenhum contrato externo foi alterado.

### Autorizacao JWT e propriedade — etapa 8.3.4

O canal de negocio exige Bearer RS256 validado pelo JWKS HTTPS do Auth. Basic e sessoes foram retirados; CSRF esta desabilitado no canal stateless. CLIENTE pode decidir e iniciar checkout somente com propriedade atual comprovada; ATENDENTE e GERENTE consultam apenas uma projecao operacional quando o escopo for autorizado; MECANICO nao recebe acesso financeiro. Sem o canal interno confiavel OS/Auth, o adapter retorna indisponivel e bloqueia essas operacoes com 503.

O campo opcional responsavelId deve coincidir exatamente com JWT.sub. A identidade persistida vem exclusivamente do token. Webhooks usam HMAC em cadeia especifica; consumidores RabbitMQ e reconciliacao nao dependem de JWT humano. Detalhes e evidencias: [etapa 8.3.4](docs/security/billing-authorization-8.3.4.md).

## RabbitMQ, Inbox e Outbox — etapa 7.4

A [documentação de mensageria](docs/messaging.md) descreve contratos, topologia, atomicidade, retries, leases, execução integrada e recuperação operacional.

- Consome `quote.create.v1` e `payment.request.v1` de `oficina.commands`.
- Publica `quote.created.v1`, `quote.creation-failed.v1`, `quote.approved.v1` e `quote.rejected.v1` em `oficina.events`.
- Filas principais: `billing.quote.create` e `billing.payment.request`; retry/DLQ próprios.
- V3 adiciona `inbox_messages` e `outbox_events`; V1/V2 e suas triggers permanecem intactas.
- Inbox combina unicidade, INSERT ON CONFLICT, lock de linha e lock transacional por OS. ACK somente após commit ou transferência confirmada para retry/DLQ.
- Outbox usa SKIP LOCKED, lease com token, backoff limitado e bloqueio da sequência de uma OS em FAILED. Publicação persistente, mandatory e com publisher confirms fora da transação.
- Entrega at-least-once: republicações preservam eventId e payload; o OS deduplica. Não há promessa exactly-once.
- Payment.request persiste pagamento e tentativa inicial PENDING com chave estável, sem chamar provedor nem publicar pagamento confirmado/falho fictício.

Novas classes: InboxMessage/OutboxEvent e enums; portas InboxRepositoryPort, OutboxRepositoryPort, MessageBrokerPort e MessageCodecPort; entidades/repositórios/adapters JPA; CommandHandler, EventRecorder, MessageCodec, CommandConsumer, RabbitMessageBrokerAdapter, OutboxPublisher e configuração de propriedades/topologia. SQL especializado utiliza o mesmo DataSource e a mesma transação JPA.

### Execução com broker compartilhado

O Compose padrão aponta a `host.docker.internal:5672`, para usar o broker do OS. Configure RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VHOST no .env com os dados **do mesmo broker utilizado pelo OS e Execution**. A conta billing não é criada automaticamente em broker externo. Se os serviços usam o broker opcional do Execution, a porta publicada é 5673.

```bash
docker compose up --build -d --wait
```

Um broker isolado opcional só é criado com o profile `local-broker`, nas portas 5674/15674. Para desenvolvimento sem os outros serviços:

```bash
RABBITMQ_HOST=rabbitmq RABBITMQ_PORT=5672 docker compose --profile local-broker up --build -d --wait
```

No PowerShell, defina `$env:RABBITMQ_HOST='rabbitmq'` e `$env:RABBITMQ_PORT='5672'` antes desse comando. Esse perfil **não integra automaticamente outros serviços**: eles precisam apontar explicitamente para o mesmo broker/vhost. Nenhum Compose externo foi alterado.

Readiness continua considerando PostgreSQL. RabbitMQ indisponível não impede decisões locais, mas a publicação pode atingir FAILED e exigir intervenção auditada. Veja recuperação de Inbox/DLQ/Outbox e limites de dead lettering em [docs/messaging.md](docs/messaging.md).

## Banco e variáveis de ambiente

| Variável | Padrão / finalidade |
| --- | --- |
| `SERVER_ADDRESS` | `127.0.0.1` no host; `0.0.0.0` somente dentro do Compose |
| `DB_HOST` | `localhost` no host; `postgres` no Compose |
| `DB_PORT` | `5434` no host, configurável; `5432` dentro do Compose |
| `DB_NAME` | `oficina_billing` |
| `DB_USERNAME` | `billing` |
| `DB_PASSWORD` | Obrigatória |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | localhost/5672 no Java; host.docker.internal/5672 no Compose |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` / `RABBITMQ_VHOST` | Identidade/vhost do broker compartilhado; senha fornecida pelo ambiente |
| `BILLING_JWT_ISSUER` | Emissor HTTPS confiavel por ambiente |
| `BILLING_JWT_AUDIENCE` | Audiencia esperada, conforme contrato Auth |
| `BILLING_JWKS_URI` | URL HTTPS do JWKS publico; cache de ate 60 segundos |

Copie `.env.example` para `.env` e ajuste as senhas. O Compose carrega esse arquivo; Maven/Spring não o carrega automaticamente. Exporte as variáveis no shell ao executar Java no host. Os valores de exemplo são somente para desenvolvimento.

## Execução Maven e Docker

Pré-requisitos: JDK 21 e Docker com containers Linux para testes PostgreSQL. O Wrapper baixa o Maven no primeiro uso.

```bash
./mvnw clean verify
cp .env.example .env
docker compose up --build -d --wait
docker compose ps
docker compose logs billing
docker compose down
```

No PowerShell, use `./mvnw.cmd clean verify` e `Copy-Item .env.example .env`.

Para executar Java no host:

```bash
docker compose up -d --wait postgres
export DB_PASSWORD=billing_local
export BILLING_JWT_ISSUER=https://auth.example.invalid
export BILLING_JWT_AUDIENCE=oficina
export BILLING_JWKS_URI=https://auth.example.invalid/.well-known/jwks.json
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
```

No PowerShell, defina DB_PASSWORD e as tres variaveis BILLING_JWT_ISSUER, BILLING_JWT_AUDIENCE e BILLING_JWKS_URI via ambiente e execute `./mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.port=8082'`.

O Dockerfile multi-stage usa JDK 21 no build, JRE 21 na execução e usuário não root. A porta interna é 8080, publicada localmente em **8082**. O PostgreSQL publica em `127.0.0.1:5434`, configurável por DB_PORT, e mantém volume persistente. A aplicação aguarda o banco saudável, usa `postgres:5432` na rede isolada e tem healthcheck de readiness. `docker compose down` preserva o volume; mudanças no .env não alteram usuários de um banco já inicializado. V2 é aplicada também sobre volumes que já possuam V1.

## Health, Swagger e segurança

| URL local | Comportamento |
| --- | --- |
| http://localhost:8082/actuator/health | Público; 200 quando saudável |
| http://localhost:8082/actuator/health/liveness | Público; estado de vida da aplicação |
| http://localhost:8082/actuator/health/readiness | Público; considera PostgreSQL; 503 se indisponível |
| http://localhost:8082/swagger-ui/index.html | Swagger UI público |
| http://localhost:8082/v3/api-docs | OpenAPI JSON público, com os contratos REST autenticados |

Os tres caminhos exatos de health, `/swagger-ui/**` e `/v3/api-docs/**` sao publicos. Negocios usam exclusivamente Bearer JWT RS256, sem Basic, sessao ou CSRF. O POST de webhook usa cadeia exclusiva com HMAC. Somente health e exposto no Actuator, sem detalhes internos. Sem canal confiavel de autorizacao OS, consultas/decisoes/checkout ficam bloqueados (503). Consulte [autorizacao 8.3.4](docs/security/billing-authorization-8.3.4.md). Exemplos invalidos de hosts devem ser substituidos pela configuracao confiavel do ambiente.

```bash
curl -i http://localhost:8082/actuator/health
curl -i http://localhost:8082/actuator/health/readiness
curl -i http://localhost:8082/v3/api-docs
curl -i "http://localhost:8082/api/v1/pagamentos?osId=33333333-3333-4333-8333-333333333333"
curl -i -H "Authorization: Bearer $ACCESS_TOKEN" "http://localhost:8082/api/v1/pagamentos?osId=33333333-3333-4333-8333-333333333333"
```

As últimas chamadas retornam 401 sem credenciais e 200 com credenciais válidas, com lista vazia se a OS não possuir pagamentos.

## Testes e cobertura

`./mvnw clean verify` executa testes de domínio, integração PostgreSQL e bootstrap; exige mínimo de **80% por linhas no JaCoCo, sem exclusões**. Docker é obrigatório; não existem skips automáticos nem banco em memória.

A suíte verifica precisão e escala, quantidades decimais, overflow, itens duplicados, estados terminais, decisões auditáveis, elegibilidade da cobrança, resultados idempotentes/conflitantes, retries e histórico. Os testes PostgreSQL exercitam adapters/consultas, BigDecimal sem perda, constraints, FKs locais, rollback atômico, versões obsoletas e decisões simultâneas. O bootstrap continua validando contexto, Flyway/Hibernate, health/readiness, falha/recuperação de conexão, Swagger e autenticação.

A suíte de mensageria usa PostgreSQL e RabbitMQ reais para validar os quatro eventos quote.*, decisões REST com Outbox, pagamentos pendentes, deduplicação e consumidores/publishers concorrentes, rollback em cada fluxo, DLQ, retry limitado, unroutable, lease expirado e queda/recuperação do broker. occurredAt é verificado explicitamente como string ISO-8601 compatível com o envelope do OS.

Relatórios: `target/surefire-reports/` e `target/site/jacoco/index.html`, com XML em `target/site/jacoco/jacoco.xml`. Logs locais ignorados pelo Git: `bootstrap-domain.log` e `bootstrap-compose.log`.

### Validação da etapa 7.4 em 2026-10-20

- `./mvnw.cmd -B -ntp clean verify`: **BUILD SUCCESS**, 143 testes, zero falhas, erros ou skips; os 92 testes anteriores foram preservados.
- JaCoCo: **98,10% de linhas — 826/842**, sem exclusões; limite de 80% aprovado.
- PostgreSQL e RabbitMQ reais via Testcontainers: comandos e eventos, decisões REST, pagamentos PENDING, concorrência, rollback, deduplicação, retry, DLQ, mandatory routing, leases e queda/recuperação do broker.
- Compose validado com o perfil opcional `local-broker`: Billing, PostgreSQL e RabbitMQ saudáveis; dois consumidores em cada fila principal. Flyway V1/V2/V3 aplicadas e schema validado pelo Hibernate.
- Health, readiness, Swagger e OpenAPI retornaram 200 na porta 8082; consulta financeira sem autenticação retornou 401. Nenhum dado financeiro fictício foi inserido no Compose.
- A Saga completa com OS e Execution não foi executada; os testes verificam transporte real e compatibilidade dos envelopes. Logs locais: `bootstrap-messaging.log` e `bootstrap-compose-final.log`.

### Histórico: validação da etapa 7.3 em 2026-10-20

- `./mvnw.cmd -B -ntp clean verify`: **BUILD SUCCESS**, 92 testes, zero falhas, erros ou skips. Os 57 cenários anteriores permanecem, com 12 novos testes unitários de casos de uso e 23 cenários REST/PostgreSQL.
- JaCoCo: **98,51% de linhas — 462/469**, sem exclusões; limite de 80% aprovado.
- Aprovação e recusa concorrentes pela API: uma resposta 200, uma 409 e exatamente uma decisão persistida. Repetições sequenciais mantêm ID, timestamps e versão originais.
- Rollback comprovado tanto com rejeição real do INSERT por trigger PostgreSQL quanto com falha inesperada durante a gravação. Respostas não expõem os detalhes internos simulados.
- Compose reconstruído e saudável, PostgreSQL próprio, Flyway V1/V2 válidas e Hibernate com `ddl-auto=validate`. Modelagem e migrações da etapa 7.2 preservadas.
- Evidencia historica anterior a 8.3.4: Basic/CSRF foi substituido por Bearer e autorizacao por OS. Consulte o documento 8.3.4 para o comportamento atual e as limitacoes.
- Tabelas financeiras permaneceram vazias no Compose. Não foram criados dados financeiros nem endpoints de preparação para a validação.

O ajuste encontrado nos novos testes foi comparar itens após a leitura PostgreSQL, que padroniza a escala do preço unitário em seis casas sem alterar seu valor. Não há bloqueios pendentes. Logs locais: `bootstrap-api.log` e `bootstrap-compose.log`.

### Histórico: validação da etapa 7.2 em 2026-10-20

- `./mvnw.cmd -B -ntp clean verify`: **BUILD SUCCESS**, Java 21, 57 testes, zero falhas, erros ou skips: 27 de domínio, 12 de persistência PostgreSQL e 18 do bootstrap.
- JaCoCo: **100% de linhas — 351/351**, sem exclusões; mínimo de 80% aprovado.
- `docker compose up --build -d --wait`: PostgreSQL e Billing saudáveis. O volume existente preservou V1 e recebeu V2 com sucesso; Hibernate iniciou com validação do schema.
- Health, liveness, readiness, Swagger e OpenAPI pela porta 8082 retornaram HTTP 200; caminhos protegidos retornaram 401 sem credenciais e a rota financeira inexistente retornou 404 com credenciais válidas.
- Processo no container como usuário `billing`, UID 100. Tabelas financeiras vazias no Compose; os dados de teste ficaram exclusivamente nos Testcontainers.

Os erros de conexão e violações de constraints registrados durante testes negativos são esperados. Os ajustes encontrados durante a implementação foram resolvidos: compatibilidade JUnit/Spring, nomes explícitos das colunas de associação e proteção de campos financeiros terminais permitindo a atualização técnica de `version` pelo Hibernate. Não há bloqueios pendentes.

## Contratos com OS e Execution

Referências obrigatórias consultadas na branch homolog:

- [OS: eventos](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/events.md).
- [OS: Saga](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/saga.md).
- [Execution: mensageria](https://github.com/AuroNet0/tech-challenge-oficina-execution-service/blob/homolog/docs/messaging.md).

O OS Service continua sendo o único orquestrador. `quote.create.v1` cria Orcamento e registra `quote.created.v1` ou `quote.creation-failed.v1` na Outbox. Decisões REST registram `quote.approved.v1` ou `quote.rejected.v1`. `payment.request.v1` cria a intenção PENDING e a tentativa inicial pelo valor aprovado, sem chamar provedor. A etapa 7.5 acrescenta checkout de teste e consulta verificável do provedor: somente um resultado financeiro validado registra `payment.confirmed.v1` ou `payment.failed.v1`. `paymentId` no evento é o UUID lógico do Billing; o identificador externo é preservado separadamente. Uma falha financeira não desfaz reparo físico.

Itens do Execution fornecem quantidade e precoPrevisto em strings decimais; o Billing utiliza modelos próprios e não consulta o banco de diagnóstico. IDs de OS/Saga e correlationId são preservados nos envelopes. Somente este repositório foi alterado; os contratos e implementações de referência permanecem intactos.

## Limitações atuais

Sem criacao REST de orcamento/pagamento, edicao ou confirmacao financeira manual, Saga propria, Terraform, Kubernetes ou CI/CD. JWT RS256 esta implementado; ownership distribuido depende do canal OS/Auth e permanece bloqueado. Nao ha dados ficticios em migracoes/startup. Cobranca de producao, refund/chargeback automatico e revisao administrativa financeira ficam fora do escopo. Checkout Pro e somente de teste, desabilitado por padrao; teste oficial depende de credenciais e HTTPS publico nao fornecidos. UNKNOWN/REVIEW podem exigir intervencao humana. Decisoes e resultados financeiros registram Outbox; somente o OS avanca a Saga. Intencao PENDING e preferencia READY nao comprovam pagamento.
