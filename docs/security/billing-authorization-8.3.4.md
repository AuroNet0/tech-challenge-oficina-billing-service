# Billing: JWT e autorização financeira

Este documento substitui o retrato histórico da etapa 8.3.4 pelo comportamento atual de `SecurityConfig`, `AuthJwtDecoder`, `FinancialAuthorization` e dos adapters de autorização. O roteiro distribuído atual está em [E2E/BDD 9.1.4](../../integration/9.1.4/README.md), com [runner próprio](../../integration/9.1.4/runner.py).

## Autenticação JWT

O canal financeiro usa Spring Security OAuth2 Resource Server com Bearer JWT, sem sessão, request cache ou autenticação Basic configurada. CSRF está desabilitado nesse canal.

[AuthJwtDecoder](../../src/main/java/com/oficina/billing/infrastructure/web/security/AuthJwtDecoder.java) exige:

- Assinatura RS256, `typ=JWT`, `kid` não vazio e apenas headers `alg`, `typ`, `kid`; token com no máximo 8192 caracteres.
- `iss` exatamente igual a `BILLING_JWT_ISSUER`; `aud` como lista de strings contendo `BILLING_JWT_AUDIENCE`.
- `sub` e `jti` UUID canônicos.
- `roles` como lista sem duplicatas contendo somente `CLIENTE`, `ATENDENTE`, `MECANICO`, `GERENTE`. Lista vazia autentica sem conceder acesso financeiro.
- Datas inteiras: `iat >= 0` e não futuro, `nbf=iat`, `exp>iat`, `exp>agora`, duração máxima de 900 segundos e tolerância de relógio zero.

`roles` é convertido exatamente para authorities `ROLE_<nome>`, sem hierarquia. `jti` não implementa revogação por requisição.

## JWKS e ambiente

| Variável | Finalidade |
| --- | --- |
| `BILLING_JWT_ISSUER` | Emissor confiável esperado |
| `BILLING_JWT_AUDIENCE` | Audiência esperada |
| `BILLING_JWKS_URI` | JWKS público por HTTPS |

`SecurityConfig` exige HTTPS, host, ausência de credenciais na URL e ausência de fragmento. O decoder usa timeouts de conexão/leitura de dois segundos e renova o decoder Nimbus ao vencer o cache de 60 segundos, medido por relógio monotônico. Falha de resolução de chave necessária rejeita o Bearer; não concede acesso sem assinatura.

## Matriz de endpoints

| Método e caminho | Papel elegível | Exigência adicional |
| --- | --- | --- |
| GET `/api/v1/orcamentos?osId=` | CLIENTE, ATENDENTE, GERENTE | READ na OS do agregado |
| GET `/api/v1/orcamentos/{id}` | CLIENTE, ATENDENTE, GERENTE | READ |
| GET `/api/v1/orcamentos/{id}/aprovacao` | CLIENTE, ATENDENTE, GERENTE | READ antes de consultar decisão |
| POST `/api/v1/orcamentos/{id}/aprovar` | CLIENTE | DECIDE |
| POST `/api/v1/orcamentos/{id}/rejeitar` | CLIENTE | DECIDE |
| GET `/api/v1/pagamentos?osId=` | CLIENTE, ATENDENTE, GERENTE | READ; lista vazia também exige autorização |
| GET `/api/v1/pagamentos/{id}` | CLIENTE, ATENDENTE, GERENTE | READ |
| POST `/api/v1/pagamentos/{id}/checkout` | CLIENTE | CHECKOUT antes do workflow |
| GET `/api/v1/notificacoes?osId=` | CLIENTE | DECIDE antes da consulta |
| POST `/api/v1/webhooks/mercado-pago` | Sem JWT humano | HMAC do provedor |
| GET health/liveness/readiness, Swagger UI e OpenAPI | Público | Allowlist em SecurityConfig |
| Outras rotas/métodos | Negado | denyAll |

Os caminhos públicos de health são `/actuator/health`, `/actuator/health/liveness` e `/actuator/health/readiness`. A configuração também permite `/swagger-ui/**` e `/v3/api-docs/**`; uma política de não publicação de Swagger/OpenAPI não foi identificada.

`MECANICO` não recebe acesso financeiro. ATENDENTE/GERENTE não aprovam, rejeitam ou iniciam checkout apenas com seus papéis operacionais. A notificação usa `DECIDE` para exigir o papel de cliente também na autorização por OS, sem alterar orçamento.

## Autorização atual por OS

O controller resolve o agregado e sua OS persistidos e chama `FinancialAuthorization`, que exige decisão positiva de `FinancialAccessPort` para ator, papéis, OS e ação. Conhecer o UUID ou possuir papel não comprova propriedade.

Há duas implementações:

| Perfil | Adapter | Comportamento |
| --- | --- | --- |
| `mtls` | `MtlsFinancialAccessAdapter` | Consulta o endpoint confiável da OS |
| `!mtls` | `UnavailableFinancialAccessAdapter` | Retorna UNAVAILABLE |

O [adapter mTLS](../../src/main/java/com/oficina/billing/infrastructure/web/security/MtlsFinancialAccessAdapter.java) exige `oficina.internal.os-uri` HTTPS com caminho exato `/internal/financial-authorization`, sem credenciais, query ou fragmento. Encaminha o JWT original e IDs de ator, OS, ação e correlação. A resposta deve ter status 200, corpo de até 1024 caracteres, `Cache-Control: no-store` e os mesmos identificadores/ação/correlação. Redirects são desabilitados.

O adapter usa o HttpClient padrão da JVM; certificados e truststore precisam ser configurados no runtime. O [compose 9.1.4](../../integration/9.1.4/compose.yaml) demonstra identidade TLS, truststore, perfil `mtls` e URI do OS. Não há consulta direta ao banco de OS/Auth nem cache local de decisões positivas. O compose local padrão não habilita esse canal.

Negação retorna 403; indisponibilidade, erro ou decisão nula retornam 503. Token ausente/inválido no canal financeiro retorna 401. Resolução de recurso inexistente pode retornar 404 após RBAC.

## Projeções e decisões

`FinancialAuthorization.operational()` verifica a presença de `ROLE_ATENDENTE`. Nesse caso, consultas retornam `OperationalResponse` com id, osId e status; isso também se aplica a atores com múltiplos papéis que incluam ATENDENTE. GERENTE sozinho recebe o DTO financeiro após autorização.

Em aprovar/rejeitar, `responsavelId` é opcional; quando presente, deve ser exatamente o `sub` autenticado. O comando usa o ator do JWT. Negação ocorre antes da decisão e da gravação correspondente na Outbox.

Checkout exige autorização antes de reservar lease ou chamar o provedor, inclusive ao recuperar uma preferência. O corpo não aceita parâmetros financeiros. Não existe endpoint REST de confirmação financeira manual.

## Webhook, mensageria e Saga

A cadeia exclusiva do POST exato do webhook permite passagem sem JWT humano; o controller mantém a verificação HMAC-SHA256, janela temporal e consistência do recurso. A notificação gera receipt durável; resultado financeiro depende de consulta validada ao provedor. Consulte [Mercado Pago](../mercado-pago.md).

Consumidores RabbitMQ e reconciliação não exigem JWT humano. Inbox/Outbox preservam deduplicação, atomicidade e entrega at-least-once. O OS é o orquestrador; consulte [mensageria](../messaging.md).

## Testes e limites

Os testes existentes incluem `AuthJwtDecoderTest` (assinatura/JWKS, claims e cache), `FinancialAuthorizationTest` (papéis e decisões), `FinancialApiTests` e `ProviderIntegrationTests` (API, autorização e provedor), além das suítes de persistência e mensageria. Os testes de integração usam Testcontainers e exigem Docker.

```sh
./mvnw clean verify
```

No Windows, use `./mvnw.cmd clean verify`. O gate JaCoCo é de 80% de linhas. O fluxo distribuído usa Gherkin e runner Python próprio, sem Cucumber-JVM, conforme o [roteiro 9.1.4](../../integration/9.1.4/README.md).

Esta atualização não executa testes. O texto anterior tratava o adapter indisponível como único em todos os perfis, descrevia a integração distribuída como futura e generalizava a projeção operacional para todos os funcionários. O código atual contém o adapter `mtls` e aplica a projeção pela presença de ATENDENTE. Validação oficial do provedor e estado atual de implantação não são comprovados por esses testes locais.
