# Oficina Billing Service

Tech Challenge FIAP — Fase 4, etapas 7.1 e 7.2. Microsserviço responsável pelo domínio de orçamentos, decisões e cobranças, com PostgreSQL próprio. Esta etapa implementa domínio e persistência; não disponibiliza APIs financeiras.

## Tecnologias e compatibilidade

Java 21, Spring Boot 4.1.1, Maven Wrapper 3.3.4/Maven 3.10.0, Web MVC, Data JPA, Validation, Security, Actuator, PostgreSQL 17, Flyway e Springdoc OpenAPI 3.1.1. Os testes utilizam Spring Test, Mockito, Spring Security Test, Testcontainers 2.0.5 e JaCoCo 0.8.15.

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
├── application/port/out/
│   ├── OrcamentoRepositoryPort.java
│   ├── AprovacaoRepositoryPort.java
│   └── PagamentoRepositoryPort.java
└── infrastructure/
    ├── config/       SecurityConfig, OpenApiConfig
    └── persistence/
        ├── entity/   Cinco entidades JPA
        ├── repository/
        ├── mapper/
        └── adapter/
src/main/resources/db/migration/
├── V1__bootstrap_schema.sql
└── V2__create_billing_domain.sql
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

A aprovação/rejeição deve salvar orçamento e decisão **na mesma transação PostgreSQL**. Os adapters usam propagação REQUIRED e participam da transação do futuro caso de uso. Não existe caso de uso/API de aprovação nesta etapa. Os testes usam `TransactionTemplate` para comprovar commit e rollback atômicos.

Triggers de constraint, diferidos até o commit, verificam itens/total e correspondência entre estado e decisão. Isso permite inserir/atualizar os registros em uma única transação sem tolerar estado inconsistente após o commit. Triggers também bloqueiam alteração de orçamento terminal e de seus itens.

### Pagamento lógico e tentativas

`Pagamento.criar` exige um orçamento aprovado e copia seu valor, OS e Saga. O banco verifica novamente estado, valor e referências. Provider e referência externa são obrigatórios; não há chamada a qualquer provedor nesta etapa.

Um pagamento lógico possui N tentativas. A unicidade por orçamento evita dois pagamentos lógicos para o mesmo valor aprovado; **não há UNIQUE(os_id) em pagamentos**. Novas tentativas são registradas como novos filhos, sem apagar ou substituir a falha anterior.

Cada tentativa segue `PENDING → PROCESSING → CONFIRMED/FAILED`, admitindo confirmação ou falha diretamente de PENDING. CONFIRMED é terminal. Uma tentativa FAILED também é terminal: `novaTentativa(novaChave, instante)` cria outro registro PENDING, mantendo o mesmo pagamento lógico. O resumo do pagamento acompanha a última tentativa, e suas propriedades públicas de estado/resultado são derivadas dela.

A tentativa anterior continua com ID, chave, falha e timestamps originais. O banco impede alteração/exclusão de tentativas finalizadas, aceita no máximo uma tentativa não falha por pagamento e valida no commit a igualdade entre resumo e última tentativa. Não existe compensação de reparo físico.

### Idempotência e concorrência

A chave é obrigatória e única globalmente nas tentativas deste Billing, escopo conservador adequado ao uso futuro de UUIDs no provedor. A primeira cobrança tem sua própria chave; cada nova tentativa exige uma nova chave. Repetir `novaTentativa` com chave já conhecida é um no-op e não cria cobrança adicional.

`findByIdempotencyKey` resolve tanto a chave atual quanto qualquer chave histórica para o pagamento lógico. O futuro consumidor deverá consultar e comparar OS/Saga/orçamento/provider/referência antes de reprocessar. A persistência não transforma conflito de chave com outro pagamento em sucesso: retorna violação de unicidade.

Resultados exigem a chave da tentativa atual. A mesma confirmação com o mesmo ID de provedor é idempotente e preserva o timestamp; um ID diferente é conflito. Repetição de falha com o mesmo motivo é idempotente. Resultados de tentativa histórica são rejeitados: reconciliação de confirmações tardias do provedor será modelada antes da integração externa. Referência externa e ID de confirmação são únicos no escopo do provider.

`@Version` protege ambos os agregados mutáveis. Concorrência entre aprovação e rejeição é resolvida por versão e unicidade da decisão no banco. O retorno de `save` deve ser utilizado, pois contém a nova versão. Não existem locks distribuídos nem transações entre microsserviços.

## Banco e variáveis de ambiente

| Variável | Padrão / finalidade |
| --- | --- |
| `DB_HOST` | `localhost` no host; `postgres` no Compose |
| `DB_PORT` | `5434` no host, configurável; `5432` dentro do Compose |
| `DB_NAME` | `oficina_billing` |
| `DB_USERNAME` | `billing` |
| `DB_PASSWORD` | Obrigatória |
| `BILLING_SECURITY_USERNAME` | `billing` |
| `BILLING_SECURITY_PASSWORD` | Obrigatória |

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
export BILLING_SECURITY_PASSWORD=billing_local
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
```

No PowerShell, defina `$env:DB_PASSWORD='billing_local'`, `$env:BILLING_SECURITY_PASSWORD='billing_local'` e execute `./mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.port=8082'`.

O Dockerfile multi-stage usa JDK 21 no build, JRE 21 na execução e usuário não root. A porta interna é 8080, publicada localmente em **8082**. O PostgreSQL publica em `127.0.0.1:5434`, configurável por DB_PORT, e mantém volume persistente. A aplicação aguarda o banco saudável, usa `postgres:5432` na rede isolada e tem healthcheck de readiness. `docker compose down` preserva o volume; mudanças no .env não alteram usuários de um banco já inicializado. V2 é aplicada também sobre volumes que já possuam V1.

## Health, Swagger e segurança

| URL local | Comportamento |
| --- | --- |
| http://localhost:8082/actuator/health | Público; 200 quando saudável |
| http://localhost:8082/actuator/health/liveness | Público; estado de vida da aplicação |
| http://localhost:8082/actuator/health/readiness | Público; considera PostgreSQL; 503 se indisponível |
| http://localhost:8082/swagger-ui/index.html | Swagger UI público |
| http://localhost:8082/v3/api-docs | OpenAPI JSON público, sem operações financeiras |

Somente os três caminhos exatos de health, `/swagger-ui/**` e `/v3/api-docs/**` são públicos. As demais rotas exigem HTTP Basic temporário. `/swagger-ui.html` continua protegido: utilize a URL indicada. Somente health é exposto no Actuator, sem detalhes internos. CSRF permanece habilitado. Credenciais são mantidas em memória pela autoconfiguração do Spring Security; use HTTPS fora do desenvolvimento local.

```bash
curl -i http://localhost:8082/actuator/health
curl -i http://localhost:8082/actuator/health/readiness
curl -i http://localhost:8082/v3/api-docs
curl -i http://localhost:8082/api/pagamentos
curl -i -u billing:billing_local http://localhost:8082/api/pagamentos
```

As últimas chamadas retornam 401 sem credenciais e 404 com credenciais válidas: não há endpoint financeiro.

## Testes e cobertura

`./mvnw clean verify` executa testes de domínio, integração PostgreSQL e bootstrap; exige mínimo de **80% por linhas no JaCoCo, sem exclusões**. Docker é obrigatório; não existem skips automáticos nem banco em memória.

A suíte verifica precisão e escala, quantidades decimais, overflow, itens duplicados, estados terminais, decisões auditáveis, elegibilidade da cobrança, resultados idempotentes/conflitantes, retries e histórico. Os testes PostgreSQL exercitam adapters/consultas, BigDecimal sem perda, constraints, FKs locais, rollback atômico, versões obsoletas e decisões simultâneas. O bootstrap continua validando contexto, Flyway/Hibernate, health/readiness, falha/recuperação de conexão, Swagger e autenticação.

Relatórios: `target/surefire-reports/` e `target/site/jacoco/index.html`, com XML em `target/site/jacoco/jacoco.xml`. Logs locais ignorados pelo Git: `bootstrap-domain.log` e `bootstrap-compose.log`.

### Validação da etapa 7.2 em 2026-10-20

- `./mvnw.cmd -B -ntp clean verify`: **BUILD SUCCESS**, Java 21, 57 testes, zero falhas, erros ou skips: 27 de domínio, 12 de persistência PostgreSQL e 18 do bootstrap.
- JaCoCo: **100% de linhas — 351/351**, sem exclusões; mínimo de 80% aprovado.
- `docker compose up --build -d --wait`: PostgreSQL e Billing saudáveis. O volume existente preservou V1 e recebeu V2 com sucesso; Hibernate iniciou com validação do schema.
- Health, liveness, readiness, Swagger e OpenAPI pela porta 8082 retornaram HTTP 200; caminhos protegidos retornaram 401 sem credenciais e a rota financeira inexistente retornou 404 com credenciais válidas.
- Processo no container como usuário `billing`, UID 100. Tabelas financeiras vazias no Compose; os dados de teste ficaram exclusivamente nos Testcontainers.

Os erros de conexão e violações de constraints registrados durante testes negativos são esperados. Os ajustes encontrados durante a implementação foram resolvidos: compatibilidade JUnit/Spring, nomes explícitos das colunas de associação e proteção de campos financeiros terminais permitindo a atualização técnica de `version` pelo Hibernate. Não há bloqueios pendentes.

## Contratos futuros

Referências obrigatórias consultadas na branch homolog:

- [OS: eventos](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/events.md).
- [OS: Saga](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/saga.md).
- [Execution: mensageria](https://github.com/AuroNet0/tech-challenge-oficina-execution-service/blob/homolog/docs/messaging.md).

O OS Service continua sendo o único orquestrador. Futuramente, `quote.create.v1` originará Orcamento e `quote.created.v1` identificará o orçamento. As decisões corresponderão a `quote.approved.v1` e `quote.rejected.v1`. `payment.request.v1` originará a cobrança do valor aprovado; seus resultados serão `payment.confirmed.v1` ou `payment.failed.v1`. Uma falha mantém a Saga aguardando pagamento, sem desfazer reparo físico.

Itens do Execution fornecem quantidade e precoPrevisto em strings decimais; o Billing utiliza modelos próprios e não consulta o banco de diagnóstico. IDs de OS/Saga permitem preservar a correlação no futuro envelope. Não foi implementado consumo/publicação nem alterado outro microsserviço.

## Limitações atuais

Sem controllers ou casos de uso de negócio, API de aprovação, RabbitMQ, Inbox/Outbox, Mercado Pago, Saga Orchestrator, JWT definitivo, Terraform, Kubernetes ou CI/CD. Não há dados financeiros fictícios em migrações ou startup. A identidade definitiva do responsável, reconciliação do provedor, autorização por cliente e integração dos comandos/eventos ficam para etapas posteriores. Os triggers representam invariantes locais PostgreSQL, não uma implementação de Saga.
