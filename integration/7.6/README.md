# E2E 7.6 — três serviços e Mercado Pago HTTP simulado

Requisitos: Docker Desktop/Linux, Java 21, Git, PowerShell 5.1 e os três repositórios irmãos na branch homolog, HEAD igual ao remoto, sem alterações em fontes/build. Nenhum contrato ou classe de produção é modificado pelo harness. Os snapshots Git usam LF, evitando modificar os wrappers locais.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.6/prepare.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.6/run.ps1 -RunRoot '<diretorio informado>'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.6/export.ps1 -RunRoot '<mesmo diretorio, ambiente ainda ligado>'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.6/stop.ps1 -RunRoot '<mesmo diretorio>'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.6/check-cleanup.ps1 -RunRoot '<mesmo diretorio>'
```

Bypass vale somente para esses processos. Prepare gera projeto, rede, bancos e volumes novos. Portas em loopback: OS 21080, Execution 21081, Billing 21082, mock 21099 e Rabbit management 15680. Não execute preparações simultâneas com essas portas; ajuste o harness se houver conflito/reserva no Windows. Não utilizar volumes de execuções anteriores nem executar down -v nos projetos normais.

O único RabbitMQ do ambiente atende os três serviços no vhost /. MongoDB 8 usa replica set com PRIMARY. Mercado Pago é um servidor HTTP determinístico Python/stdlib com decimais exatos, acessível por loopback **compartilhado com o container Billing** (`network_mode: service:billing`). Assim, o adapter continua restringindo HTTP a localhost/127.0.0.1: não foi liberado host arbitrário, alterada validação do cliente ou relaxada segurança de produção. `SPRING_APPLICATION_JSON` ativa allow-insecure-local somente neste Compose.

Tokens e chaves HMAC **fictícios** são gerados por execução em artifacts/<run>/provider-secrets.json, ignorado pelo Git; não há secrets reais nem valores de credenciais Mercado Pago versionados. Endpoints do mock exigem esse Bearer e nunca contatam APIs oficiais. Os callbacks HTTPS *.e2e.invalid são apenas dados da preferência simulada; os webhooks locais são assinados pelo harness com o manifesto do verificador real. Basic/CSRF continuam ativos e checkout exige BILLING_OPERATOR.

## Cenários

- Approved: Saga completa, itens decimais, checkout concorrente, pending/in_process não confirmam, notificações assinadas concorrentes, pagamento CONFIRMED → evento real → OS FINALIZADA/Saga COMPLETED. Reentrega idêntica e nova notificação não criam efeito lógico adicional.
- Rejected: outra OS percorre diagnóstico/reparo reais, falha financeira consultada no mock; pagamento FAILED, evento real payment.failed e OS/Saga continuam aguardando pagamento.
- BrokerOutage: outro pagamento é confirmado com o broker isolado parado; Outbox preservada, retry real com mesmo envelope após recuperação e Saga COMPLETED. Nenhum envelope financeiro de sucesso é fabricado ou publicado pelo script.
- UnknownRecovered: timeout após POST ter criado preferência; pesquisa por referência recupera sem repetir criação e o fluxo finaliza após confirmação verificável.
- UnknownUnproven: preferência escondida da pesquisa; mantém UNKNOWN/PENDING sem novo POST ou sucesso artificial.
- Assinatura errada, request-id ausente, timestamp expirado, IDs divergentes, JSON inválido e tipo incompatível são rejeitados sem persistir recibos financeiros.

O script usa polling com timeout, valida HTTP e consulta bancos somente para leitura; falhas obrigatórias produzem status BLOCKED, checkpoint e exit code 1. Não altera status pelo banco, não cria endpoints e não publica payment.confirmed diretamente no RabbitMQ. Os estados do mock são a única simulação financeira.

Não há endpoint autorizado para nova tentativa na implementação atual. Confirmação tardia após segunda tentativa e disputa entre os métodos processNotification/processDue são verificadas na suíte existente ProviderIntegrationTests (PostgreSQL/RabbitMQ reais + HTTP simulado), explicitamente **separadas do E2E HTTP distribuído**. Um único scheduler não comprova sozinho duas transações financeiras concorrentes; webhooks concorrentes no E2E comprovam recebimento/deduplicação durável, enquanto a disputa dos workers fica no teste de integração de aplicação.

## Suítes e evidências

Executar `mvnw.cmd -B -ntp clean verify` em cada repositório com Java 21 e Docker disponíveis, preferencialmente antes do ambiente E2E, para evitar competição por recursos. Não executar clean antes de preservar artefatos de testes antigos. Relatórios de JaCoCo e Surefire ficam em target de cada serviço.

Artifacts locais, incluindo secrets fictícios e logs brutos, não devem ser versionados. Evidências selecionadas/sanitizadas ficam em evidence/. O relatório diferencia execução real, testes de aplicação e pendências. O mock não comprova disponibilidade, compatibilidade operacional ou liquidação no Mercado Pago oficial. Não houve cobrança real.

`export.ps1 -MetricsOnly` atualiza métricas Maven/JaCoCo e resultados selecionados de aplicação após parar o E2E, sem consultar os bancos desligados. `check-cleanup.ps1` confere identidade dos containers originais, ausência de E2Es rodando e vazamento das credenciais fictícias desta execução nos arquivos exportados. Não versionar provider-secrets.json nem logs/Surefire brutos.

Nesta execução, o E2E passou e a validação global ficou bloqueada pelo teste temporal do Billing. Ver relatorio.md e evidence/validation-global.json; não confundir cobertura observada por jacoco:report com clean verify aprovado.
