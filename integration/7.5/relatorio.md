# Etapa 7.5 — evidências locais

Execução: 21/10/2026, branch homolog. Nenhum commit ou push realizado.

## Resultado automatizado

- `mvnw.cmd -B -ntp clean verify`: BUILD SUCCESS às 10:28:43 -03:00, duração 4:01.
- 193 testes, zero falhas, erros ou testes ignorados. Os 143 testes anteriores foram preservados; 50 testes novos (14 unitários e 36 de integração).
- JaCoCo: 1.373 linhas cobertas de 1.405, **97,72%**; mínimo de 80% atendido, sem excluir a nova integração da cobertura.
- PostgreSQL e RabbitMQ reais via Testcontainers; API externa simulada por JDK HttpServer. A suíte não utiliza credenciais reais.
- `provider-http-evidence.json` contém as chamadas sanitizadas por cenário, estados e tipos de eventos observados; não contém cabeçalhos de autenticação, tokens ou assinaturas.
- Relatórios detalhados: `target/surefire-reports`, `target/site/jacoco/index.html`, `target/site/jacoco/jacoco.xml` e `bootstrap-provider-verify.log` (artefatos locais regeneráveis).

Foram exercitados checkout repetido/concorrente, timeout e recuperação por referência sem repetir POST, assinatura inválida/expirada, entrega repetida, reconciliação concorrente com webhook, respostas pendentes e divergentes, rollback da Outbox, confirmação tardia de tentativa anterior, múltiplos pagamentos externos e expiração de preferências. Eventos payment.confirmed.v1 e payment.failed.v1 foram publicados no RabbitMQ de testes com envelope contratual e occurredAt ISO-8601. Nenhuma confirmação veio de retorno do navegador ou de status fornecido pelo webhook.

## Compose isolado

Comando: `docker compose -f integration/7.5/compose.yaml up --build -d --wait --wait-timeout 300`.

Projeto billing-provider-validation, sem reutilização dos bancos anteriores, contendo:

| Container | Estado na validação |
| --- | --- |
| billing-provider-validation-billing-1 | healthy |
| billing-provider-validation-postgres-1 | healthy |
| billing-provider-validation-rabbitmq-1 | healthy |

Somente HTTP foi exposto em 127.0.0.1:23082. PostgreSQL e RabbitMQ não publicaram portas no host. Mercado Pago e reconciliação externa permaneceram desabilitados; este smoke test não executou cobranças.

| Verificação | Resultado |
| --- | --- |
| /actuator/health | 200 |
| /actuator/health/readiness | 200 |
| /swagger-ui/index.html | 200 |
| /v3/api-docs | 200 |
| GET financeiro sem autenticação | 401 |
| Flyway V1, V2, V3, V4 | success=true nas quatro versões |
| Hibernate | inicialização validada |
| Orçamentos / pagamentos / notificações | 0 / 0 / 0 |
| billing.quote.create / billing.payment.request | duas inscrições consumidoras por fila, zero mensagens |
| Retrys e billing.commands.dlq | declarados, zero mensagens |

Após coletar evidências, o projeto foi encerrado com `docker compose -f integration/7.5/compose.yaml stop`, sem apagar volumes. Os seis containers normais permaneceram running/healthy: oficina-billing-rabbitmq-1, oficina-billing-billing-1, oficina-billing-postgres-1, oficina-execution-service, tech-challenge-oficina-execution-service-rabbitmq-1 e oficina-execution-mongodb. Nenhum Compose original foi executado para recriar os ambientes normais.

## Preservação e escopo

- Relatórios E2E 7.4.1 versionados preservados. Backup bruto antes do clean: integration/7.4.1/artifacts/e2e-backup-20261021-094942.zip, SHA-256 BEABD351631672A305E7A8DAC45265965604B2C3CCBA7EDC2644901DE3515583.
- Ambientes isolados billing-e2e anteriores foram parados sem apagar volumes. O fluxo distribuído 7.4.1 completo não foi reexecutado nesta etapa; as evidências antigas não são apresentadas como nova execução.
- OS e Execution sem alterações locais; V1/V2/V3 do Billing sem alterações. Contratos payment.confirmed.v1/payment.failed.v1 preservados; paymentId corresponde ao UUID lógico local, mantendo ID do provedor separado.

## Problemas resolvidos e limitações

- Ajustados casts explícitos TIMESTAMPTZ em comandos JDBC após validação em PostgreSQL real. As factories de teste respeitam a sequência de persistência exigida pelas triggers V2.
- O build Docker exigiu incluir o diretório do Docker Desktop no PATH para localizar o credential helper; nenhuma credencial foi obtida ou registrada.
- Teste oficial Mercado Pago **pendente**: não foram fornecidos credenciais de teste e endpoint HTTPS público. O roteiro está em docs/mercado-pago.md. Evidências da API simulada não substituem homologação oficial.
- Integração habilitável somente para testes financeiros nesta entrega, sem autorização por proprietário integrada. UNKNOWN/REVIEW, múltiplos créditos, chargebacks e estornos exigem investigação/intervenção; não há endpoint público para forçar confirmação.
- Não foi feita cobrança real nem confirmação fictícia. Entrega RabbitMQ continua at-least-once, não exactly-once.
