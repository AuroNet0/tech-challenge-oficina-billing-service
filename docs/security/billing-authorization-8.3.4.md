# Billing: JWT e autorizacao financeira — etapa 8.3.4

## Estado e referencias

Implementacao local academica, Java 21 e Spring Boot **4.1.1**, conforme pom.xml.
Branch inicial homolog, HEAD 6a53e4c412d666051dea5b8919027db09f70f3ab, arvore inicialmente limpa.
Sem deploy, commit, push, Terraform, AWS, mudanca de Saga ou inicio da 8.3.5.

Consultados antes da implementacao: SecurityConfig; os quatro controllers; DecidirOrcamentoUseCase;
PaymentWorkflow/ProviderTransactions; MercadoPagoSignatureVerifier; PaymentReconciliationScheduler;
CommandConsumer/CommandHandler; Inbox/Outbox; migracoes V1–V4; suites financeiras/mensageria;
docs/messaging.md; docs/mercado-pago.md; integration/7.6/relatorio.md.
Referencias locais Auth: docs/authentication.md e docs/security/authorization-8.3.1.md.
OS: docs/security/os-authorization-8.3.2-b.md, portas de autorizacao e modelo OS/filial.
Nenhum desses outros repositorios foi alterado.

**JWT e politicas implementados; autorizacao financeira distribuida ainda bloqueada.**
O OS possui clienteId e filialId, mas nao oferece o canal interno confiavel necessario a esta entrega.
Nao ha consulta direta ao banco OS/Auth, copia de clientes ou inferencia de propriedade por UUID.

## 1. Autenticacao JWT

Spring Security OAuth2 Resource Server, starter gerenciado pelo Boot utilizado.
AuthJwtDecoder usa Nimbus e fixa RS256: nao aceita HS256, RS384, none ou algoritmo escolhido pelo cliente.
Exige typ=JWT, kid nao vazio e apenas os headers alg/typ/kid; limita token a 8192 caracteres.
Valida assinatura, issuer exato, audiencia em lista de strings, sub e jti UUID canonicos.
roles deve ser lista de nomes conhecidos, sem duplicatas; lista vazia autentica sem conceder acesso.

Datas numericas inteiras obrigatorias: iat nao negativo e nao futuro, nbf=iat, exp>iat, exp>agora,
TTL maximo de 900 segundos. Clock injetado e skew zero, conforme Auth. Relogios precisam estar sincronizados.
Sem refresh token, introspeccao por requisicao, chave privada compartilhada ou consulta ao banco Auth.
jti nao constitui revogacao: um papel ainda pode refletir o token ate expirar.

## 2. JWKS e configuracao

Obrigatorias no ambiente:

| Variavel | Finalidade |
| --- | --- |
| BILLING_JWT_ISSUER | Emissor confiavel esperado, exatamente igual ao Auth |
| BILLING_JWT_AUDIENCE | Audiencia esperada, por exemplo oficina conforme contrato |
| BILLING_JWKS_URI | URL HTTPS do JWKS publico Auth |

SecurityConfig rejeita URL sem HTTPS/host, com credenciais ou fragmento. Nao ha flag de HTTP inseguro em producao.
Somente a configuracao de testes instancia decoder com servidor HTTP em loopback e RSA efemero.
Fetch tem timeout de conexao/leitura de dois segundos.

Cache de JWKS limitado a **60 segundos**, medidos por relogio monotonico. O decoder Nimbus e substituido
quando esse prazo vence, impedindo que seu cache interno continue usando chaves antigas.
Validacao/decode e renovacao sao sincronizados para impedir uso concorrente de cache vencido.
Dentro do TTL, chave conhecida continua funcionando se JWKS cair; apos TTL/cold start/kid desconhecido,
falha de resolucao rejeita o Bearer com 401. Nunca autoriza sem assinatura.
Teste com relogio monotonico controlado comprova chave em cache aos 59 s e rejeicao aos 60 s sem JWKS.

## 3. Papeis e matriz de endpoints

CLIENTE, ATENDENTE, MECANICO e GERENTE sao convertidos exatamente para ROLE_<nome>.
Sem hierarquia, aliases, scopes implicitos ou promocao para BILLING_OPERATOR.

| Metodo e rota | Papel elegivel | Exigencia de recurso |
| --- | --- | --- |
| GET /api/v1/orcamentos/{id} | CLIENTE, ATENDENTE, GERENTE | READ na OS persistida |
| GET /api/v1/orcamentos?osId= | CLIENTE, ATENDENTE, GERENTE | Resolver orcamento e autorizar sua OS |
| GET /api/v1/orcamentos/{id}/aprovacao | CLIENTE, ATENDENTE, GERENTE | READ na OS do orcamento antes de consultar decisao |
| POST /api/v1/orcamentos/{id}/aprovar | CLIENTE | DECIDE na OS persistida |
| POST /api/v1/orcamentos/{id}/rejeitar | CLIENTE | DECIDE na OS persistida |
| GET /api/v1/pagamentos/{id} | CLIENTE, ATENDENTE, GERENTE | READ na OS persistida |
| GET /api/v1/pagamentos?osId= | CLIENTE, ATENDENTE, GERENTE | Autorizar OS de cada resultado; lista vazia tambem exige autorizacao |
| POST /api/v1/pagamentos/{id}/checkout | CLIENTE | CHECKOUT na OS do pagamento persistido |
| POST /api/v1/webhooks/mercado-pago | Identidade do provedor por HMAC | Sem JWT humano |
| GET health/liveness/readiness, swagger-ui/**, v3/api-docs/** | Publico | Allowlist anterior preservada |
| Outras rotas/metodos | Negado | denyAll |

MECANICO nao recebe acesso financeiro. ATENDENTE/GERENTE nao decidem pelo cliente, nao iniciam checkout,
nao confirmam pagamentos e nao manipulam valores/tentativas. Nao foram criados endpoints administrativos.
Um ator com papel operacional e CLIENTE recebe a projecao minima nas consultas, conservadoramente.

## 4. Autorizacao por OS e arquitetura

HTTP -> filtro JWT/RBAC -> controller resolve agregado -> FinancialAuthorization -> FinancialAccessPort
-> decisao atual da OS -> operacao interna existente.

A porta da aplicacao recebe UUID do ator, conjunto de authorities autenticadas, osId e finalidade READ/DECIDE/CHECKOUT.
ALLOW deve significar propriedade atual do cliente ou escopo atual por filial para o funcionario, conforme papel/finalidade.
O futuro adapter precisa validar identidade tecnica/canal, integridade, finalidade e resposta correlacionada
ao ator/OS; nao pode aceitar osId, clienteId, filial ou decisao declarados pelo frontend como prova.
Nenhum contrato HTTP remoto foi inventado enquanto essa integracao nao esta disponivel.

UnavailableFinancialAccessAdapter e o unico adapter de producao, em todos os perfis, e sempre retorna UNAVAILABLE.
Nao existe configuracao de allow-all ou fallback positivo. Mocks positivos existem exclusivamente em src/test.
Sem cache de grants ou decisoes positivas. Cada acesso, inclusive recuperacao de checkout, reavalia a porta.
DENY gera 403; ausencia/erro/resposta nula da dependencia gera 503 sanitizado.
Bearer ausente/invalido gera 401; papel insuficiente gera 403 antes do controller.
Recurso ausente continua 404 apos RBAC; isso nao concede acesso ou comprova propriedade.

**Disponivel em producao:** validacao JWT, RBAC, probes/docs, webhook HMAC e caminhos internos Rabbit/reconciliacao.
**Bloqueado em producao:** todas as consultas financeiras, aprovacoes, rejeicoes e checkouts que dependem da OS,
inclusive consultas operacionais de ATENDENTE/GERENTE. Token valido sozinho nunca os desbloqueia.

## 5. Consultas operacionais

Reutilizados os GETs existentes. OperationalResponse contem somente id, osId e status.
Orcamentos nao expoem itens, precos ou Saga ao atendimento; pagamentos nao expoem valores,
provider, tentativas ou dados de conciliacao. Consulta de decisao operacional nao expoe responsavel/motivo.
CLIENTE autorizado mantem os DTOs existentes, que ja omitem segredos e IDs financeiros externos sensiveis.
Nao ha novos endpoints redundantes nem acesso ao estado tecnico pelo Billing.

## 6. Aprovar/rejeitar e identidade auditavel

responsavelId permanece opcional por compatibilidade; quando informado deve coincidir exatamente com JWT.sub.
Divergencia gera 403, sem mutacao. O comando sempre recebe sub, inclusive quando o campo foi omitido.
Nao existe identidade comprovada fornecida arbitrariamente pelo cliente HTTP.

DecidirOrcamentoUseCase e suas transacoes nao foram alterados: decisao unica, repeticao 409,
estados, optimistic locking, timestamps, rollback, aprovacoes e Outbox continuam atomicos.
quote.approved.v1/quote.rejected.v1 mantem o contrato e a correlacao.
Negacao/indisponibilidade ocorre antes de chamar a porta de decisao.

## 7. Checkout e invariantes financeiras

O controller resolve pagamento e sua OS persistidos, exige CLIENTE e decisao CHECKOUT positiva,
antes de iniciar PaymentWorkflow, reservar lease ou chamar o provedor.
Recuperacao de preferencia existente exige a mesma verificacao atual.
IdempotencyKey, tentativas, UNKNOWN, REVIEW, reconciliacao, confirmacao tardia, fechamento de preferencias,
unicidade e Outbox financeira permanecem nos componentes anteriores.
Link entregue anteriormente ao provedor nao e automaticamente revogado pelo bloqueio de um novo HTTP.
Nao ha confirmacao manual, cobranca real ou alteracao financeira para contornar testes.

## 8. Basic, sessao e CSRF

Basic removido do canal de negocio; sem coexistencia legada, formulario ou fallback.
SecurityFilterChain usa STATELESS, sem request cache e sem autenticacao automatica por cookie.
CSRF desabilitado nesse canal exclusivamente Bearer; nao se reutilizam cookies/tokens de sessao.
compose/.env.example, Swagger e README usam a configuracao JWT.
Scripts/evidencias historicos integration/7.x registram Basic/CSRF da epoca e nao sao roteiro atual de autorizacao.

Uma cadeia anterior corresponde **somente** ao POST exato do webhook. Sua permissao no filtro
nao remove o HMAC do controller; nenhum outro caminho casa com essa excecao.
Bearer invalido adicional no webhook nao e interpretado como identidade de usuario.

## 9. Webhook HMAC

MercadoPagoSignatureVerifier e processamento financeiro preservados.
HMAC-SHA256, janela temporal, comparacao constante, IDs query/body, deduplicacao e receipt duravel
continuam obrigatorios. Corpo sozinho nunca confirma pagamento.
Consulta autenticada ao provedor continua verificando valor, BRL, referencia externa e tentativa.
Notificacao invalida nao confirma pagamentos. Reconciliacao nao exige usuario HTTP.

## 10. RabbitMQ e Saga

CommandConsumer/CommandHandler, Inbox/Outbox, retries, DLQ, locks e deduplicacao nao foram alterados.
Comandos quote.create.v1/payment.request.v1 e eventos quote.created.v1/quote.approved.v1/quote.rejected.v1/
payment.confirmed.v1/payment.failed.v1 mantem seus envelopes, sem JWT humano.
Nao ha SecurityContext ou @PreAuthorize nos caminhos de mensageria.
O teste com Rabbit real rejeita um token HTTP expirado e ainda consome/publica quote.create/quote.created
sem consultar a autorizacao HTTP. O OS continua sendo o unico orquestrador.

## 11. Testes e evidencias

- AuthJwtDecoderTest: RSA real/JWKS local, assinatura, expiracao, issuer/audience, UUIDs, roles,
  datas/TTL, algoritmo/HS256/none, kid, fonte indisponivel e limite do cache.
- FinancialAuthorizationTest: cliente, funcionarios, papeis insuficientes, grants por finalidade,
  identidade, adapter indisponivel e erro interno.
- FinancialApiTests: PostgreSQL real, HTTP RS256 real, aprovar/rejeitar, transacoes, rollback,
  concorrencia, responsavel divergente, projecao, negacao/503 sem decisao nem Outbox, Basic recusado e stateless.
- BillingApplicationTests: adapter real indisponivel no contexto de producao; esse teste isolado usa jwt()
  apenas para testar a politica, enquanto as suites anteriores exercitam assinatura real.
- ProviderIntegrationTests: Mercado Pago simulado, checkout bloqueado antes de reserva/chamada externa,
  projecao de pagamento, webhook sem JWT, duplicidade, UNKNOWN, REVIEW, reconciliacao, valores e Outbox.
- MessagingIntegrationTests/DeliveryPolicyTests/MessagingUnitTests: Rabbit real, transporte, Inbox/Outbox,
  publicacao, reentrega, retry/DLQ e recuperacao, sem dependencia de token humano.
- Suites anteriores de dominio, casos de uso e persistencia preservadas.

Fixture FinancialTestSecurity usa decoder real com chaves efemeras e mock da porta somente em testes.
Cada suite configura explicitamente os grants necessarios e testa decisoes negativas. Nao ha credenciais AWS.
O helper retryNow usa Clock da aplicacao menos um segundo: corrige a divergencia temporal ja registrada
na 7.6, sem mudar o worker, leases, regras ou assercoes financeiras.
As expectativas antigas de identidade ausente e rotas nao autorizadas foram adaptadas ao contrato JWT.

## 12. Validacao e cobertura

Execucao final em **26/10/2026, 12:00:49 America/Sao_Paulo**:

| Evidencia | Resultado |
| --- | --- |
| mvn.cmd -B clean verify | BUILD SUCCESS, exit 0, 3 min 44 s; JAR repackaged |
| Suite completa | **235 testes, zero falhas, zero erros, zero skips**, 12 classes |
| Testes anteriores/novos | 193 anteriores preservados/adaptados ao JWT; 42 novos (30 unitarios/criptograficos e 12 de integracao) |
| JaCoCo linhas | **1490/1531 = 97,32%**, 41 linhas nao cobertas; gate 80% aprovado |
| Financeiro/provedor | ProviderIntegrationTests 38, ProviderUnitTests 14, FinancialPersistenceTests 12, FinancialUseCaseTests 12 e FinancialDomainTests 27 aprovados |
| HTTP/PostgreSQL | FinancialApiTests 32 e BillingApplicationTests 19 aprovados; inclui isolamento de OS e adapter real indisponivel |
| RabbitMQ | MessagingIntegrationTests 22, MessagingUnitTests 23 e DeliveryPolicyTests 6 aprovados; broker real, Inbox/Outbox, deduplicacao, retries/DLQ/recuperacao |
| JWT/politicas | AuthJwtDecoderTest 22 e FinancialAuthorizationTest 8 aprovados |
| Flyway | V1–V4 originais aplicadas/validadas em PostgreSQL descartavel; nenhuma migracao alterada |
| Git | diff --check exit 0; homolog e HEAD inicial preservados; 19 arquivos rastreados modificados e 10 novos |

Log completo: target/billing-authorization-final-verify.log.
Relatorios: target/surefire-reports e target/site/jacoco/index.html (XML no mesmo diretorio).
Logs das tentativas anteriores ficam em target, ignorados pelo Git.
Uma execucao intermediaria passou 234 testes antes do reforco final de isolamento e cabecalhos.
Na primeira execucao de testes, tres expectativas antigas falharam: identidade nula e dois status HTTP.
Foram ajustadas ao contrato JWT/deny-by-default, sem mudar regras financeiras ou remover cenarios.
Gate JaCoCo original de linhas 80% preservado, sem exclusoes ou skips novos.
Comando: Maven local 3.10.0, JAVA_HOME Microsoft JDK 21.0.12.1, mvn.cmd -B clean verify.
Docker Desktop fornece PostgreSQL 17 e RabbitMQ 4 reais em containers descartaveis.
Uma compilacao no sandbox falhou ao fechar recursos do javac; execucoes de validacao usam acesso autorizado
ao cache Maven/JDK/Docker locais. Avisos de metadata de repositorios opcionais e Mockito nao sao testes ignorados.

## 13. Limitacoes e dependencias OS/Auth

Nao se declara ownership distribuido concluido, canal IAM implementado, revogacao instantanea ou sandbox
oficial Mercado Pago homologado. Sem grants administrativos implicitos. Sem novo esquema/migracao.
Auth precisa prover vinculo usuario-cliente confiavel; OS precisa oferecer decisao atual de propriedade/filial
por canal autenticado entre servicos. Indisponibilidade continua fechada.
Papeis refletem o JWT ate expirar; futuro canal deve definir frescor, revogacao e janela entre decisao e commit.
Uma decisao remota positiva nao equivale a lock/transacao distribuida.

## 14. Arquivos da entrega

Configuracao: pom.xml, application.yml, compose.yaml, .env.example e OpenApiConfig/SecurityConfig.
Aplicacao: application/authorization/FinancialAccessPort.
Entrada HTTP: FinancialAuthorization, AuthJwtDecoder, UnavailableFinancialAccessAdapter, OperationalResponse,
OrcamentoController, PagamentoController, CheckoutController, anotacao OpenAPI do webhook e ApiExceptionHandler.
Testes: pacote security novo, application.properties de testes e quatro suites HTTP/mensageria existentes.
Documentacao: este documento, README, docs/messaging.md e docs/mercado-pago.md.
Nao foram alterados contratos, migracoes, use cases financeiros, provider, consumers ou Saga.

## 15. Preparacao para E2E 8.3.5

A etapa 8.3.5 nao foi iniciada. Antes dela, formalizar contrato minimo OS, identidade tecnica/canal,
grants de filial e vinculo Auth, configurar issuer/audience/JWKS por ambiente e substituir o adapter indisponivel
somente por implementacao confiavel. Preparar dois clientes e filiais em fixtures isoladas autorizadas.
Validar isolamento, revogacao/falhas, checkout e Saga completa com Mercado Pago simulado e broker real,
sem alterar estados manualmente para declarar sucesso. Nao habilitar producao por mock ou papel.
