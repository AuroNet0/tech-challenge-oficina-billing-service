# Oficina Billing Service

Tech Challenge FIAP — Fase 4, etapa 7.1. Terceiro microsserviço de negócio da oficina, preparado para assumir orçamentos, aprovações e pagamentos. Esta entrega contém exclusivamente o bootstrap técnico, sem operações financeiras.

## Tecnologias e compatibilidade

- Java 21, Spring Boot 4.1.1 e Maven 3.10.0 via Wrapper 3.3.4.
- Spring Web MVC, Data JPA, Validation, Security e Actuator.
- PostgreSQL 17 e Flyway, incluindo o módulo específico para PostgreSQL e o starter Flyway do Boot 4.
- Springdoc OpenAPI 3.1.1, compatível com Boot 4, conforme a [documentação oficial](https://springdoc.org/).
- JUnit Jupiter **5.13.4** explicitamente fixado: o BOM do Boot 4.1.1 adota JUnit 6 por padrão. Spring Test, Mockito e Spring Security Test são fornecidos pelos starters de teste.
- Testcontainers PostgreSQL/JUnit Jupiter 2.0.5, gerenciados pelo BOM do Boot; JaCoCo 0.8.15.

As demais versões são gerenciadas pelo Spring Boot. A compatibilidade efetiva é validada por `clean verify`, com contexto real e PostgreSQL em container. A extensão JUnit do Spring Test gerenciado pelo Boot 4.1.1 exige APIs do JUnit 6: para preservar o requisito de JUnit 5 sem rebaixar o Spring, a suíte inicia e fecha a aplicação com `SpringApplication.from(BillingApplication::main)` em `@BeforeAll`/`@AfterAll` e usa Spring Test MockMvc com os filtros reais de segurança. O DataSource recebe um spy de teste via BeanPostProcessor.

## Arquitetura e estrutura

A arquitetura seguirá o modelo hexagonal: domínio independente, casos de uso e portas na aplicação, adaptadores na infraestrutura. Nesta etapa existem somente o ponto de entrada e as configurações necessárias; diretórios vazios, entidades e portas artificiais não são criados.

```text
src/main/java/com/oficina/billing/
├── BillingApplication.java
└── infrastructure/config/
    ├── OpenApiConfig.java
    └── SecurityConfig.java
src/main/resources/
├── application.yml
└── db/migration/V1__bootstrap_schema.sql
src/test/java/com/oficina/billing/
└── BillingApplicationTests.java
.mvn/wrapper/maven-wrapper.properties
Dockerfile
compose.yaml
mvnw / mvnw.cmd
```

## Banco e variáveis de ambiente

O Billing utiliza exclusivamente seu PostgreSQL. Não há conexão com tabelas ou bancos do OS Service e do Execution Service.

| Variável | Padrão / finalidade |
| --- | --- |
| `DB_HOST` | `localhost` ao executar Java no host; `postgres` no Compose |
| `DB_PORT` | `5434` no host, configurável; `5432` dentro da rede Compose |
| `DB_NAME` | `oficina_billing` |
| `DB_USERNAME` | `billing` |
| `DB_PASSWORD` | Obrigatória |
| `BILLING_SECURITY_USERNAME` | `billing` |
| `BILLING_SECURITY_PASSWORD` | Obrigatória |

Copie `.env.example` para `.env` para desenvolvimento e ajuste as senhas. O Compose carrega esse arquivo; o Maven/Spring **não** o carrega automaticamente. Ao executar Java no host, exporte as variáveis no shell. Valores de exemplo são exclusivos de desenvolvimento.

`spring.jpa.hibernate.ddl-auto=validate` e `spring.jpa.open-in-view=false` permanecem ativos. A migração `V1__bootstrap_schema.sql` executa somente `SELECT 1`: valida a conexão e o ciclo de migrações sem criar tabelas artificiais. Apenas `flyway_schema_history` é criada pelo Flyway. Ainda não existem entidades JPA; a validação de mapeamentos passará a ter efeito com a modelagem futura.

## Maven

Pré-requisitos: JDK 21 e Docker com containers Linux para os testes. O Wrapper baixa o Maven no primeiro uso.

```bash
./mvnw clean verify
docker compose up -d --wait postgres
export DB_PASSWORD=billing_local
export BILLING_SECURITY_PASSWORD=billing_local
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
```

No Windows PowerShell, use `Copy-Item .env.example .env`, `$env:DB_PASSWORD='billing_local'`, `$env:BILLING_SECURITY_PASSWORD='billing_local'` e `./mvnw.cmd clean verify`. Para iniciar: `./mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.port=8082'`.

## Docker Compose

```bash
cp .env.example .env
docker compose up --build -d --wait
docker compose ps
docker compose logs billing
docker compose down
```

O Dockerfile faz build com JDK 21, executa com JRE 21 e usuário sem privilégios. A porta interna é 8080 e a publicação local é **8082**, coexistindo com OS em 8080 e Execution em 8081. O PostgreSQL publica em `127.0.0.1:5434` por padrão; altere `DB_PORT` se necessário. A aplicação usa sempre o banco `postgres:5432` da sua rede isolada. Não há rede compartilhada com outros serviços.

O volume `billing-postgres-data` persiste os dados. `docker compose down` preserva o volume. A aplicação aguarda o PostgreSQL saudável; seu healthcheck consulta a readiness HTTP. Alterações de credenciais no `.env` não alteram usuários de um volume já inicializado.

## Health, Swagger e segurança

| URL local | Comportamento sem autenticação |
| --- | --- |
| http://localhost:8082/actuator/health | HTTP 200 quando saudável |
| http://localhost:8082/actuator/health/liveness | Estado de vida da aplicação |
| http://localhost:8082/actuator/health/readiness | Estado da aplicação **e conexão PostgreSQL**; HTTP 503 se indisponível |
| http://localhost:8082/swagger-ui/index.html | Swagger UI público |
| http://localhost:8082/v3/api-docs | OpenAPI JSON público |

Somente os três caminhos exatos de health, `/swagger-ui/**` e `/v3/api-docs/**` são públicos. Os demais exigem HTTP Basic temporário, incluindo caminhos inexistentes. `/swagger-ui.html` não é público; use `/swagger-ui/index.html`. Somente health é exposto pelo Actuator; detalhes internos não são publicados. CSRF permanece habilitado conforme o padrão do Spring Security.

```bash
curl -i http://localhost:8082/actuator/health
curl -i http://localhost:8082/actuator/health/readiness
curl -i http://localhost:8082/v3/api-docs
curl -i http://localhost:8082/api/pagamentos
curl -i -u billing:billing_local http://localhost:8082/api/pagamentos
```

As últimas chamadas retornam 401 sem credenciais e 404 com credenciais válidas, pois não existe endpoint financeiro. HTTP Basic é provisório e requer HTTPS fora do ambiente local. Não há JWT nesta etapa.

## Testes e cobertura

`./mvnw clean verify` executa os testes JUnit 5, empacota o serviço e exige cobertura mínima de **80% de linhas** no JaCoCo, sem exclusões de classes. Docker é obrigatório: a suíte não ignora testes automaticamente se estiver indisponível.

Os testes inicializam a aplicação pelo `main`, usam PostgreSQL 17 real via Testcontainers e verificam JPA, banco isolado, histórico Flyway V1, ausência de tabelas de negócio, health/liveness/readiness, Swagger e OpenAPI, allowlist de segurança, credenciais Basic válidas/inválidas e inexistência de operações financeiras. Uma falha de conexão é simulada com Mockito sobre o DataSource real para comprovar readiness 503 e liveness 200, seguida de recuperação.

Relatórios: `target/surefire-reports/` e `target/site/jacoco/index.html` (XML em `target/site/jacoco/jacoco.xml`). Não há testes artificiais para preencher cobertura nem banco em memória.

### Validação executada em 2026-10-20

- `./mvnw.cmd -B -ntp clean verify` com Java 21: **BUILD SUCCESS**, 18 testes, zero falhas, erros ou skips.
- JaCoCo: **21 de 21 linhas cobertas (100%)**, incluindo o ponto de entrada e ambas as configurações; limite de 80% aprovado.
- `docker compose up --build -d --wait`: PostgreSQL e Billing saudáveis, V1 registrada com sucesso, processo executando como usuário `billing` (UID 100).
- Pela porta 8082: health, liveness, readiness, Swagger UI e OpenAPI retornaram HTTP 200; caminhos protegidos retornaram 401 sem credenciais; `/api/pagamentos` retornou 404 com credenciais válidas.
- Logs locais, ignorados pelo Git: `bootstrap-verify.log` e `bootstrap-compose.log`.

O ajuste de compatibilidade necessário foi inicializar o contexto diretamente no ciclo de vida JUnit 5, pois a extensão JUnit do Spring Test utiliza uma API presente no JUnit 6. A validação final não tem bloqueios pendentes.

## Relação futura com OS e Execution

Referências consultadas na branch `homolog`:

- [OS: eventos e comandos](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/events.md).
- [OS: Saga](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/saga.md).
- [Execution: mensageria](https://github.com/AuroNet0/tech-challenge-oficina-execution-service/blob/homolog/docs/messaging.md).

O OS Service permanece como único orquestrador da Saga. Futuramente, o Billing receberá `quote.create.v1` e `payment.request.v1` e publicará os resultados de orçamento, aprovação e pagamento previstos pelos contratos do OS. O diagnóstico do Execution fornece itens com quantidade e preço em strings decimais, permitindo gerar o orçamento sem consultar bancos externos. Envelope, correlação, idempotência e persistência transacional serão tratados em etapas posteriores.

`docs/messaging.md` no OS e `docs/events.md`/`docs/saga.md` no Execution não estavam presentes nas referências consultadas. Nenhum dos outros microsserviços foi modificado.

## Limitações do bootstrap

Não há domínio financeiro, entidades, repositórios de negócio, APIs financeiras, aprovação, Mercado Pago, RabbitMQ, Inbox/Outbox, orquestrador de Saga, JWT definitivo, Terraform, Kubernetes ou CI/CD. As próximas etapas dependem da modelagem de negócio. As credenciais Basic são mantidas em memória pela autoconfiguração do Spring Security.
