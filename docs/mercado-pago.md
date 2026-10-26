# Mercado Pago — Checkout Pro, etapa 7.5

## Produto e fontes oficiais

Implementação de **Checkout Pro hospedado, via Preferences API**, conforme o escopo solicitado. O Billing não recebe cartão/CVV e não implementa frontend completo. Uma preferência apenas disponibiliza checkout: **não confirma pagamento**. O OS continua sendo o único Saga Orchestrator.

Documentação oficial consultada em 2026-10-21:

- [Criar preferência](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/create-payment-preference) e [referência POST](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/create-preference/post).
- [Referência Checkout Pro](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/overview), cujo índice atual também apresenta Orders API. Essa recomendação não foi usada para trocar silenciosamente o produto/contrato solicitado.
- [Pesquisar preferências por referência](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/search-preferences/get).
- [Notificações](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/payment-notifications), [SDK oficial: manifesto/assinatura](https://github.com/mercadopago/sdk-java/blob/master/src/main/java/com/mercadopago/webhook/WebhookSignatureValidator.java) e [orientação oficial sobre processamento](https://github.com/mercadopago/sdk-nodejs/blob/master/docs/webhooks.md).
- [URLs de retorno](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/configure-back-urls).
- [Consultar pagamento](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/get-payment/get), [pesquisar pagamentos](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro-preferences/search-payments/get).

Contratos homolog consultados: OS [events.md](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/events.md) e [saga.md](https://github.com/AuroNet0/tech-challenge-oficina-os-service/blob/homolog/docs/saga.md), Execution [messaging.md](https://github.com/AuroNet0/tech-challenge-oficina-execution-service/blob/homolog/docs/messaging.md). Não foram alterados esses serviços.

## Configuração segura

Integração e varredura de reconciliação são **desligadas por padrão**. Não há token/secret default, inicialização de cobrança automática ou requisição oficial no bootstrap/testes Maven. .env.example contém campos vazios; .env não é versionado nem carregado automaticamente pelo Maven.

| Variável | Default / regra |
| --- | --- |
| MERCADO_PAGO_ENABLED | false; habilitação explícita de ambiente de testes |
| MERCADO_PAGO_ACCESS_TOKEN | vazio; fornecer token de teste ou de usuário vendedor de teste |
| MERCADO_PAGO_WEBHOOK_SECRET | vazio; obrigatório quando habilitado, mínimo 16 caracteres |
| MERCADO_PAGO_API_BASE_URL | https://api.mercadopago.com; host fixo permitido |
| MERCADO_PAGO_NOTIFICATION_URL | HTTPS público, rota /api/v1/webhooks/mercado-pago |
| MERCADO_PAGO_SUCCESS_URL | HTTPS público; página do operador/aplicação, não uma confirmação financeira |
| MERCADO_PAGO_FAILURE_URL | HTTPS público |
| MERCADO_PAGO_PENDING_URL | HTTPS público |
| MERCADO_PAGO_HTTP_TIMEOUT | 5s; maior que zero e no máximo 30s |
| MERCADO_PAGO_RECONCILIATION_ENABLED | false; ligar explicitamente para recuperar ausência/atraso de webhook |
| MERCADO_PAGO_SIGNATURE_TOLERANCE | 5m; 0s desliga somente janela temporal, não o HMAC/deduplicação |
| MERCADO_PAGO_WORKER_DELAY | 5s |
| MERCADO_PAGO_BATCH_SIZE | 10; limite 1–100 |
| MERCADO_PAGO_LEASE_DURATION | 2m; deve superar três timeouts e ser dimensionado para o volume de consultas |
| MERCADO_PAGO_SYNC_INTERVAL | 1m |
| BILLING_JWT_ISSUER / BILLING_JWT_AUDIENCE / BILLING_JWKS_URI | Emissor, audiencia e JWKS HTTPS confiaveis por ambiente |

Configuração habilitada valida credenciais presentes, tempos e URLs. Os callbacks não aceitam userinfo, fragmento, localhost ou HTTP. HTTP de loopback na API é permitido somente pela propriedade de teste billing.mercado-pago.allow-insecure-local, não exposta como opção operacional padrão no Compose. Não permita essa propriedade fora de testes.

Antes de criar preferência, o adapter consulta /users/me e exige token TEST- ou tag test_user. Somente sandbox_init_point em host sandbox Mercado Pago é disponibilizado; init_point de produção não é fallback. Consultas financeiras ainda rejeitam live_mode=true e collector_id diferente do vendedor autenticado. A integração deliberadamente não oferece habilitação para cobranças reais em produção nesta etapa.

## Portas e HTTP

PaymentProviderPort usa DTOs próprios: CheckoutRequest, Preference e ExternalPayment. MercadoPagoPaymentProviderAdapter utiliza HttpClient do Java 21 e Jackson 3 configurado com decimais exatos. Não depende de SDK com políticas implícitas de retry/idempotência e não modifica o ObjectMapper das APIs. Requests autenticam por Bearer no header; redirects HTTP são desativados. Erros não preservam corpo do provedor ou token em mensagens/logs.

| Endpoint externo | Uso |
| --- | --- |
| GET /users/me | vendedor e restrição ao ambiente de testes |
| POST /checkout/preferences | criação única reservada localmente |
| GET /checkout/preferences/{id} | validação/reconciliação de preferência |
| GET /checkout/preferences/search?external_reference=... | recuperar criação com resultado desconhecido; resposta elements/total |
| GET /v1/payments/{id} | fonte autoritativa de status após webhook/pesquisa |
| GET /v1/payments/search?external_reference=... | resultados paginados; cada pagamento é consultado individualmente |
| GET /merchant_orders/{id} | resolver preference_id da order do pagamento, quando fornecida |
| PUT /checkout/preferences/{id} | solicitar expiração após liquidação, seguida de GET de verificação |

Pesquisas usam páginas de 20, com limite conservador de 100 resultados por referência. Excesso/consulta incompleta não produz confirmação: exige tratamento/revisão. Não se presume consistência linearizável do índice do provedor. Casos simultâneos ainda podem exigir revisão financeira posterior.

### Endpoint de checkout

```http
POST /api/v1/pagamentos/{id}/checkout
Authorization: Bearer <access-token-RS256>
```

Somente pagamento existente e elegivel (PENDING/PROCESSING), provider MERCADO_PAGO, com orcamento APPROVED e mesmo valor/OS/Saga. O corpo e ausente ou {}; qualquer campo, inclusive preco/providerPaymentId, retorna 400. A etapa 8.3.4 exige ROLE_CLIENTE e propriedade atual comprovada pela OS antes de criar ou recuperar checkout. Sem canal interno confiavel, retorna 503 antes do workflow. Nao ha Basic, sessao, CSRF ou endpoint REST de confirmacao manual.

Resposta 200 quando pronto; 202 para criação de resultado UNKNOWN ainda não reconciliada:

```json
{
  "pagamentoId": "uuid-local",
  "provider": "MERCADO_PAGO",
  "preferenceId": "id-do-provedor",
  "checkoutUrl": "https://sandbox.mercadopago.com.br/checkout/v1/redirect?pref_id=...",
  "status": "PENDING",
  "checkoutStatus": "READY"
}
```

UNKNOWN tem preferenceId/checkoutUrl nulos. CONFIRMED, FAILED sem nova tentativa ou retenção para revisão retornam conflito; pagamento inexistente retorna 404. Desabilitado/erro externo operacional retorna 503 quando não tratado como criação desconhecida. Conflitos concorrentes retornam 409. Responses ProblemDetail não expõem SQL/stack trace.

**Limite de autorizacao 8.3.4:** JWT RS256 e politicas estao implementados; o adapter OS permanece indisponivel. Conhecer UUID ou possuir papel nao comprova propriedade. Nenhum mock positivo existe em producao. Consulte [autorizacao Billing](security/billing-authorization-8.3.4.md). A autorizacao distribuida nao esta concluida.

## Reserva, criação externa e recuperação

1. Transação curta valida o pagamento/orçamento, adquire lease por pagamento e cria checkout único por tentativa, persistindo CREATING **antes** de HTTP.
2. Fora da transação, envia uma preferência com um item consolidado pelo valor aprovado, currency_id=BRL, external_reference original e metadata com IDs locais/chave da tentativa. Nenhum dado de cartão/cliente é necessário.
3. Outra transação verifica token/prazo e salva preferenceId/link READY. Repetições retornam o registro já persistido; concorrência ocupada recebe 409.
4. Timeout/5xx/crash não são tratados como prova de não criação. CREATING recuperado ou erro de criação vira UNKNOWN. O serviço pesquisa a referência e obtém o detalhe das preferências, relacionando a metadata.billing_attempt_id.
5. Uma correspondência válida recupera o checkout; múltiplas retêm para revisão. **Zero resultados não autoriza outro POST**, porque a pesquisa pode estar atrasada. UNKNOWN pode permanecer bloqueado até resolução operacional. O worker antigo não consegue finalizar dados com token expirado.

A referência de criação consultada documenta Authorization, mas não uma garantia de X-Idempotency-Key equivalente à Payments API. Por isso o Billing não promete idempotência remota desse POST e não depende desse header. A chave existente identifica a tentativa local/metadata, com constraint única. Não há retry de POST na lógica de aplicação; retry/reconciliação utiliza GET. O custo deliberado da segurança é retenção manual quando não se consegue provar o resultado.

## V4 e rastreabilidade

V1/V2/V3 permanecem intactas. V4 acrescenta:

| Tabela | Responsabilidade |
| --- | --- |
| provider_sync | lease/fencing por pagamento, próximo sync, tentativas/erros e motivo de revisão |
| provider_checkouts | tentativa local única, preferência externa única, URL e NEW/CREATING/UNKNOWN/READY/CLOSING/CLOSED/REVIEW |
| provider_notifications | receipt durável deduplicado, recurso, request-id, processamento e lease |
| provider_payments | último estado conhecido por ID externo, vínculo explícito ao pagamento/tentativa |
| provider_observations | ledger imutável de estados observados e snapshots anteriores de correção tardia |

As FKs são exclusivamente locais; a identidade composta de checkout/tentativa e a preferência atribuída são protegidas. Nenhum token, assinatura completa, cartão ou corpo integral do provedor é persistido. Campos operacionais são curtos/códigos seguros. Pagamentos e tentativas continuam nos agregados existentes com @Version e constraints de valor/estado. Um índice único adicional protege payment.confirmed.v1 por pagamento lógico na Outbox.

## Webhook autenticado e durável

POST /api/v1/webhooks/mercado-pago tem cadeia exclusiva sem JWT humano e sem CSRF. Isso **nao dispensa autenticacao HMAC**. Mesmo um Bearer invalido adicional nao substitui nem impede a verificacao HMAC. As rotas de negocio usam Bearer stateless e autorizacao por OS.

O verificador exige x-signature (ts e v1), x-request-id e data.id da query; pagamentos deste produto usam IDs numéricos. O corpo deve ser payment e data.id deve coincidir. Manifesto:

```text
id:<data.id>;request-id:<x-request-id>;ts:<ts>;
```

HMAC-SHA256 com secret configurado, comparação por MessageDigest.isEqual. O ts textual é preservado; interpreta-se epoch de 10 dígitos como segundos e 13 como milissegundos, cobrindo os formatos apresentados em exemplos oficiais. Janela padrão de cinco minutos rejeita passado/futuro excessivos; manter relógios sincronizados. A política aceita somente assinatura v1 e campos não ambíguos. Notificações fora dessa janela podem exigir reconciliação; 0s desativa somente essa verificação temporal quando aprovado operacionalmente.

A chave de receipt é SHA-256 do manifesto assinado. Duplicatas desse receipt não inserem outra linha; novas notificações do mesmo pagamento consultam o estado autoritativo e são idempotentes financeiramente. O payload não é prova de status: mesmo que diga approved, apenas o GET autenticado do provedor pode provocar confirmação.

HTTP **200 somente após commit da receipt**. Erro de persistência retorna 500 para permitir nova entrega; assinatura inválida retorna 401; formato/ID divergente retorna 400. Corpo JSON é limitado a 16 KiB no controller e não é armazenado. O processamento posterior é assíncrono: claim SKIP LOCKED com lease, GET fora da transação e aplicação transacional de observação + agregado/tentativa + conclusão da receipt + Outbox. Se a Outbox falhar, essas alterações são revertidas; receipt fica reprocessável, não confirmada como processada.

## Estados e confirmações tardias

| Estado externo atual confiável | Ação local |
| --- | --- |
| pending | não confirma; mantém PENDING/estado financeiro sem regressão |
| in_process / authorized | PROCESSING quando ainda PENDING; sem evento final |
| approved | valida referência, BRL, valor exato, vendedor/modo e vínculo à preferência/tentativa; liquida uma vez |
| rejected / cancelled | falha financeira da tentativa atual elegível; reason seguro; sem desfazer reparo físico |
| refunded / charged_back / in_mediation / desconhecido | registra e retém para revisão, não converte genericamente em FAILED |

Expiração que o provedor reporta como cancelled segue o estado confirmado; um literal não mapeado não é presumido como falha. Respostas antigas por date_last_updated não substituem o estado mais novo. Estados contraditórios no mesmo timestamp exigem revisão. CONFIRMED não volta a FAILED.

Uma tentativa FAILED pode ser legitimamente aprovada depois de outra tentativa local ser criada. confirmarVerificado encontra **a tentativa correspondente**, não apenas a mais recente. A tentativa liquidada passa a CONFIRMED; antes dessa correção, o trigger grava o snapshot anterior FAILED no ledger imutável, e exige evidência approved já persistida para aquele ID externo. A tentativa local ainda ativa é encerrada sem gerar um segundo evento de falha financeira. O resumo passa a seguir a tentativa confirmada, mesmo histórica. Nova tentativa/checkout fica proibida após a liquidação.

Mais de um ID externo approved para o mesmo pagamento lógico é duplicidade financeira: retenção para revisão, nunca outra confirmação automática. Se descoberta antes da liquidação, não emite confirmação; se descoberta depois, mantém a confirmação original e não emite outra nem regrede para FAILED. A pesquisa completa e o ledger permitem detectar o caso; não existe transação distribuída capaz de impedir toda mudança concorrente no provedor.

Após liquidar, preferências conhecidas são marcadas CLOSING, expiradas externamente e verificadas via GET antes de CLOSED. Falha nessa operação mantém CLOSING para retry e não desfaz a liquidação. **Enquanto a expiração externa não estiver confirmada, não se pode prometer que um link já entregue ao comprador deixou de aceitar pagamento.** Reconciliar continuamente e tratar qualquer segundo crédito manualmente; refund/contestação automática não está neste escopo.

## Reconciliação e eventos da Saga

O worker sempre processa receipts quando a integração está habilitada. MERCADO_PAGO_RECONCILIATION_ENABLED controla a varredura adicional de provider_sync: pendências com checkout, resultados de criação desconhecidos, falhas operacionais, aprovações sem webhook e preferências aguardando fechamento. Pagamento lógico novo sem checkout continua aguardando o POST autenticado; a varredura não cria cobrança automaticamente.

Claims por pagamento e por receipt têm tokens/prazo, SKIP LOCKED/atualização condicional, recuperação de leases expirados e lote configurável. Consultas externas não seguram transações. Falhas operacionais usam backoff exponencial limitado: 30s–30m para sync; receipts acrescentam janela operacional de 2m (até 32m). Revisão financeira não é resolvida apagando ledger ou alterando status manualmente.

Webhook e reconciliação usam a mesma aplicação transacional. Eventos usam oficina.events, source=billing-service, occurredAt textual ISO e routing key igual ao tipo:

- payment.confirmed.v1: payload {"paymentId":"UUID-do-pagamento-logico-Billing"}. O contrato OS aceita esse identificador opaco; o ID do provedor permanece separado para auditoria.
- payment.failed.v1: payload {"reason":"Pagamento recusado ou cancelado pelo provedor."}. Timeout, 429, 5xx, DNS, UNKNOWN ou pending **não** geram esse evento.

osId/sagaId são os do agregado validado e correlationId vem primeiro da Inbox payment.request original; sem ela, usa a política existente de origem quote/fallback determinístico da Saga. CONFIRMED tem deduplicação lógica e índice único, e a Outbox mantém seus próprios eventId/payload nas republicações. At-least-once, leases, confirms e mandatory continuam os da etapa 7.4; não há exactly-once ou publicação diretamente pelo webhook.

O OS permanece WAITING_PAYMENT ao receber payment.failed conforme seu contrato. Só ele decide avançar a Saga após consumir o resultado; o Billing não atualiza status da OS ou acessa outros bancos.

### Recuperação operacional

Corrigir credenciais/rede/topologia, manter identidade e envelopes, e deixar o retry/reconciliação atuar. Não apagar notificações/ledger nem gerar eventIds novos para esconder falhas. UNKNOWN sem preferência visível exige investigação no provedor, não criação automática repetida. REVIEW por valor/identidade, múltiplos créditos ou refund exige auditoria humana e fluxo administrativo futuro, sem endpoint público para liberar confirmação. FAILED da Outbox segue o procedimento auditado de docs/messaging.md. Expiração pendente exige verificar o link no provedor e, se necessário, atuação no painel de testes.

## Testes locais, preservação E2E e Compose

```bash
./mvnw clean verify
docker compose -f integration/7.5/compose.yaml up --build -d --wait
curl -i http://127.0.0.1:23082/actuator/health/readiness
docker compose -f integration/7.5/compose.yaml stop
```

No Windows, usar mvnw.cmd. O Compose de validação é isolado (porta 23082) e inicia com Mercado Pago desligado; não altera os containers normais. Os testes usam PostgreSQL/RabbitMQ reais via Testcontainers e JDK HttpServer como equivalente local de MockWebServer. Credenciais são marcadores sintéticos de teste, nunca secrets reais. Evidências HTTP sem headers/segredos são geradas em target/provider-http-evidence.json; relatórios Surefire e JaCoCo ficam nos diretórios usuais.

O relatório E2E 7.4.1 e suas evidências versionadas foram preservados. Artefatos brutos adicionais foram arquivados antes do clean em integration/7.4.1/artifacts/e2e-backup-20261021-094942.zip (ignorado pelo Git), SHA-256 BEABD351631672A305E7A8DAC45265965604B2C3CCBA7EDC2644901DE3515583. Os projetos billing-e2e foram parados sem remover volumes; os seis containers normais não foram parados.

### Resultado desta execução

Em 21/10/2026, `mvnw.cmd clean verify` terminou com BUILD SUCCESS: 193 testes, zero falhas/erros/ignorados e JaCoCo de 97,72% por linhas (1.373/1.405). Os 50 novos testes incluem PostgreSQL/RabbitMQ reais e API HTTP simulada; os 143 anteriores foram preservados. O Compose isolado ficou saudável, aplicou V1–V4 e foi parado após a validação, sem afetar os containers normais. Detalhes e evidências sanitizadas: [relatório da execução](../integration/7.5/relatorio.md) e [chamadas HTTP simuladas](../integration/7.5/provider-http-evidence.json).

## Roteiro oficial sandbox — PENDENTE

Nenhum teste externo é declarado aprovado: não foram solicitadas/obtidas credenciais automaticamente e não há configuração HTTPS pública fornecida para esta entrega.

1. Criar/configurar a aplicação e usuários **de teste** vendedor/comprador no painel oficial, sem conta/pagamento real. Obter as credenciais e secret manualmente por canal seguro, sem versionar ou imprimir valores.
2. Configurar URLs HTTPS publicas nomeadas de teste, MERCADO_PAGO_ENABLED=true e MERCADO_PAGO_RECONCILIATION_ENABLED=true. Configurar JWT/JWKS confiaveis e o canal autorizado OS/Auth. Este roteiro permanece bloqueado enquanto nao houver comprovacao de propriedade; nao conceder BILLING_OPERATOR como substituto.
3. Usar a Saga real para criar um pagamento PENDING de orçamento aprovado. Não inserir resultado financeiro nem usar endpoint de confirmação (inexistente).
4. Quando a dependencia OS estiver disponivel, solicitar checkout com Bearer CLIENTE e propriedade comprovada. Conferir preferenceId, external_reference, metadata, idempotencia e sandbox_init_point. Confirmar vendedor de testes; nao usar URL de producao como fallback.
5. Abrir o link com comprador de teste e seguir os cenários oficiais/cartões de teste atuais, primeiro aprovado e depois rejeitado em **outro pagamento de teste**. Não fornecer cartão real ao Billing ou em logs.
6. Observar apenas status pendente após retorno do navegador até o GET autenticado do provedor comprovar o resultado. Conferir valores, moeda, IDs, receipts/observações, tentativas, Outbox e publicação do evento correto.
7. A documentação oficial informa que pagamentos com credenciais de teste podem não emitir notificações automáticas. Validar recepção/HMAC pelo simulador oficial em Suas integrações; o recurso consultado ainda precisa ser verificável pela API de teste para alterar o domínio. Se o simulador enviar ID fictício não consultável, validar apenas recebimento/assinatura e não chamar isso de confirmação financeira. Usar reconciliação para o fluxo de teste sem webhook.
8. Reentregar notificações oficiais, desligar o receptor temporariamente e validar recuperação sem duplicidade; verificar fechamento das preferências e alertas. Não publicar manualmente payment.confirmed para completar o teste.
9. Registrar IDs/eventos/status e evidência sanitizada, nunca token, secret ou assinatura completa. Encerrar ambientes de teste sem tocar nos volumes normais.

Limites atuais: testes oficiais pendentes, somente ambiente financeiro de testes, autorização por proprietário não integrada, UNKNOWN/REVIEW podem exigir intervenção humana, sem refund/chargeback automático ou suporte automático a compra dividida em múltiplos créditos. Nenhuma infraestrutura cloud/CI foi adicionada.
