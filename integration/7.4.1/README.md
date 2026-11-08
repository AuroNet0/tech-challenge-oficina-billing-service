# Integração real 7.4.1

Harness externo aos três serviços: não altera fontes, contratos, Compose originais ou volumes normais. As credenciais fixas deste Compose são exclusivamente sintéticas/locais. Não publicar estas portas em interfaces externas.

## Preparação reproduzível

Requisitos: Windows PowerShell 5.1+, Git, Docker Desktop com containers Linux e acesso ao GitHub/Maven/registry. Os três repositórios devem ser diretórios irmãos, na branch homolog, com HEAD igual ao remoto. Os caminhos de Git/Docker podem ser informados como parâmetros.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.4.1/prepare.ps1 -OsPort 21080 -ExecutionPort 21081 -BillingPort 21082 -RabbitManagementPort 15678
```

O Bypass vale somente para esse processo; não altera a política da máquina. A preparação:

1. Verifica cada HEAD contra `refs/heads/homolog` remoto, sem checkout, fetch ou alterações locais.
2. Exporta snapshots por `git -c core.autocrlf=false -c core.eol=lf archive HEAD` em `target/integration-7.4.1/<run>/sources/`, evitando conversão Windows para CRLF no wrapper Linux. Alterações locais pré-existentes não entram na imagem e são registradas no manifesto.
3. Gera um nome de projeto novo com timestamp e UUID. Os quatro volumes, a rede, as imagens e os sete containers pertencem exclusivamente àquela execução.
4. Usa os Dockerfiles originais dos snapshots. Não aplica correções de serialização nem overrides que escondam incompatibilidade de contrato.
5. Aguarda saúde do PostgreSQL de cada serviço, MongoDB replica set rs0, RabbitMQ e readiness das três aplicações.

Portas exclusivamente loopback, configuráveis por parâmetros e registradas no manifesto: no comando acima, OS 21080, Execution 21081, Billing 21082, Rabbit management 15678. Os defaults de prepare.ps1 são 19080/19081/19082/15676. AMQP e bancos não são publicados no host. Um único broker atende as três aplicações pela rede privada do projeto. Não execute dois projetos simultaneamente com as mesmas portas. O broker de teste limita os schedulers Erlang para reduzir o custo de ambientes paralelos, sem mudar contratos ou persistência.

## Cenários completos após a correção

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.4.1/run.ps1 -RunRoot '<diretorio novo da preparacao>' -Scenario Main
```

Main exige bases vazias e executa o fluxo até WAITING_PAYMENT/PENDING. Antes do consumo original de quote.create, pausa somente o Billing desse projeto, verifica o corpo original na fila, enfileira uma cópia idêntica e reinicia dois consumidores. Compara itens/quantidades/preços/totais exatos. Aprova com Basic, sessão e CSRF reais, por dois POSTs assíncronos concorrentes durante a parada do broker isolado; exige 200/409, uma decisão e uma Outbox persistidas. Recupera o broker, acompanha reparo e pagamento pendente. Reentrega cinco envelopes originais e compara snapshots completos dos três bancos antes/depois. Uma fila observadora registra o tráfego real e os checks exigem occurredAt textual ISO-8601, origem, routing key e correlação.

Para recusa, prepare **outro projeto com novos volumes**, não reutilize o RunRoot de Main:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.4.1/prepare.ps1 -OsPort 22080 -ExecutionPort 22081 -BillingPort 22082 -RabbitManagementPort 15679
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.4.1/run.ps1 -RunRoot '<novo diretorio da recusa>' -Scenario Rejection
```

Rejection exige CANCELADA/COMPENSATED, uma decisão REJECTED, quote.rejected real e ausência de repair.start, reparos e pagamentos. Nenhum cenário chama provedor ou produz resultado financeiro fictício. A identificação do responsável continua provisória e não comprova identidade/autorização definitiva.

`result.json` registra PASSED ou BLOCKED com o checkpoint, IDs e verificações concluídas. `http-trace.json` não salva cookies/tokens; status 0 representa falha de conexão durante polling de reinicialização controlada, não uma resposta HTTP. Falhas não viram sucesso silencioso. Os artefatos contêm envelopes, logs, filas e snapshots; não apague target antes de exportar as evidências.

Exporte cada execução aprovada para docs/evidence, validando os IDs e timestamps e preservando os hashes dos snapshots/replays:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.4.1/export-run.ps1 -RunRoot '<diretorio aprovado de Main ou Rejection>'
```

O feedback da reexecução corrigida está em [docs/integration-7.4.1-retest.md](../../docs/integration-7.4.1-retest.md). O relatório anterior permanece como histórico do bloqueio original, não como resultado atual.

## Probe histórico do bloqueio inicial

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.4.1/probe.ps1 -RunRoot '<diretorio informado pela preparacao>'
```

O probe histórico está fixado nas portas 18080/18081/18082/15675 da primeira baseline. Não o utilize nos manifests com portas novas; utilize run.ps1. Ele exige banco novo, verifica readiness, cadastra dados sintéticos pelas APIs HTTP Basic, abre uma OS real e confirma WAITING_DIAGNOSTIC. Aguarda até 75 segundos pelo diagnóstico no Execution. Em seguida registra estados, Outbox, bindings, filas, DLQ e logs. A leitura da DLQ usa `ack_requeue_true`: não consome definitivamente a evidência.

Este script é **somente o checkpoint inicial**; não representa a suíte completa aprovada. Se o diagnóstico não nascer, termina com erro e registra `BLOCKED`. Não modifica envelopes nem fabrica resultados para contornar o bloqueio. O relatório em `docs/integration-7.4.1.md` separa executado de planejado.

Após esse bloqueio, exporte a evidência selecionada, sem credenciais, preservando o envelope original como string e seu SHA-256:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File integration/7.4.1/export-evidence.ps1 -RunRoot '<diretorio da execucao>'
```

## Inspeção e parada seguras

```powershell
$run = '<diretorio da execucao>'
$manifest = Get-Content "$run/manifest.json" -Raw | ConvertFrom-Json
$env:E2E_SOURCE_ROOT = $manifest.sourceRoot
$env:E2E_OS_PORT = "$($manifest.ports.os)"
$env:E2E_EXECUTION_PORT = "$($manifest.ports.execution)"
$env:E2E_BILLING_PORT = "$($manifest.ports.billing)"
$env:E2E_RABBIT_MANAGEMENT_PORT = "$($manifest.ports.rabbitManagement)"
docker compose -p $manifest.project -f $manifest.compose ps
docker compose -p $manifest.project -f $manifest.compose logs --no-color os execution billing
docker compose -p $manifest.project -f $manifest.compose stop
```

`stop` preserva todas as evidências e volumes desse projeto. Para removê-lo posteriormente, valide o nome `billing-e2e-<run>` e os mounts no manifesto antes de executar `down --volumes` **somente nesse projeto**. Não usar prune nem comandos sobre os projetos normais. Cada nova tentativa usa `prepare.ps1` novamente, jamais dados da execução anterior.

Os artefatos brutos ficam em `target/` e não devem ser commitados; podem conter dados sintéticos, tokens de sessão e informações internas dos containers. Preserve-os antes de executar Maven clean. Evidências selecionadas, sem credenciais, ficam em `docs/evidence/7.4.1/`.
