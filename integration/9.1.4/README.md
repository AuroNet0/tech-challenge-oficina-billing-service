# E2E e BDD 9.1.4

Execucao local com os quatro repositorios irmaos em `homolog`, Java 21, Maven e
Docker Desktop Linux. Scripts derivados da 8.3.5; o runtime Auth, mock do provedor,
imagens, certificados mTLS, bancos e runner HTTP existentes sao reutilizados.
Os arquivos e volumes da 8.3.5 permanecem preservados. Cada preparacao cria um
projeto `oficina-e2e914-*` e evidencias privadas em `artifacts/<id>/`.
`memory-analysis.md` compara a configuracao dos harnesses e documenta a subida
sequencial monitorada, sem requisito fixo de 4 GiB livres no Windows.
Os JARs copiados sao imutaveis durante a execucao: retomadas conferem seus hashes
e nao sobrescrevem arquivos montados em JVMs ativas.
Cada JVM executa uma copia identica do JAR no filesystem interno do container,
reduzindo leituras aleatorias no bind mount Windows. O coletor confere tambem
o hash dessa copia efetivamente executada.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File integration/9.1.4/prepare.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File integration/9.1.4/verify.ps1 -OutputDirectory '<RunRoot>/verify'
powershell -NoProfile -ExecutionPolicy Bypass -File integration/9.1.4/start.ps1 -RunRoot '<caminho emitido>'
powershell -NoProfile -ExecutionPolicy Bypass -File integration/9.1.4/summarize-memory.ps1 -RunRoot '<caminho emitido>'
powershell -NoProfile -ExecutionPolicy Bypass -File integration/9.1.4/collect.ps1 -RunRoot '<caminho emitido>' -VerificationDirectory '<RunRoot>/verify'
```

`verify.ps1` executa `mvn -B clean verify` separadamente nos quatro servicos e
exige sucesso, testes executados, zero falhas/erros/ignorados e cobertura de linhas
maior ou igual a 80%. Logs privados ficam em `artifacts/verify/`.
`prepare.ps1` utiliza o JAR Auth ja disponivel para gerar chaves locais. Caso ele
ainda nao exista, executar primeiro `verify.ps1` com um diretorio novo e exclusivo
e usar esse mesmo diretorio em `collect.ps1 -VerificationDirectory`.
Para novas execucoes, preparar primeiro um RunRoot e passar
`verify.ps1 -OutputDirectory '<RunRoot>/verify'`, seguido de
`collect.ps1 -RunRoot '<RunRoot>' -VerificationDirectory '<RunRoot>/verify'`.
Isso preserva os logs historicos; os relatorios anteriores de target tambem sao
copiados antes de `clean`. O caminho de saida e resolvido antes de trocar de repositorio.
`watch-memory.ps1 -RunRoot '<RunRoot>'` registra metricas periodicas ate existir
`<RunRoot>/monitor-stop`. `start.ps1` tambem mede cada etapa e exige readiness
de cada container antes de iniciar o seguinte. O runner e construido antes da stack.
Encerrar o monitor antes do resumo final. `recover.ps1` permite repetir somente
os cenarios de indisponibilidade, preservando os resultados anteriores em uma
pasta nova; `finalize.ps1` exige os cenarios aprovados e os dez containers saudaveis.
`collect.ps1` copia somente evidencias selecionadas para `evidence/<runId>/`,
confere hashes dos JARs, saude dos containers e preservacao dos containers anteriores.
Se Docker estiver indisponivel, `collect.ps1 -RunRoot '<caminho>' -VerificationOnly`
coleta somente testes/cobertura e revisoes. `collection-status.json` registra
`REGRESSION_ONLY`, sem declarar E2E, BDD ou compensacoes aprovados.
Nao publicar `artifacts`, JWTs, chaves, `private.env` ou logs completos.

## Inspecao do dominio

- `diagnostic.start.v1` e `repair.start.v1` agora enfileiram trabalhos `PENDING`.
  Os POSTs `/inicio` autorizados publicam os eventos `*.started.v1` e movem a OS.
- Billing V5 registra uma notificacao por orcamento; `GET /api/v1/notificacoes?osId=`
  exige cliente proprietario e retorna o estado atual do orcamento.
- Rejeicao do orcamento: `quote.rejected.v1`, OS `CANCELADA`, Saga `COMPENSATED`;
  nenhum comando de reparo nem pagamento deve existir.
- Falha de reparo iniciado: `repair.failed.v1`, comando `repair.cancel.v1`, evento
  `repair.cancellation-failed.v1`, Saga `FAILED`, reparo `FAILED`, OS `EM_EXECUCAO`.
  O historico e `lastError` explicitam intervencao manual; nao ha reversao fisica.
- `diagnostic.failed.v1` fora de `WAITING_DIAGNOSTIC/DIAGNOSTIC` e rejeitado na Inbox.
  O teste compara OS, historico, Saga e Outbox completos antes/depois, com novo eventId.

## BDD executavel

`oficina.feature` define o cenario Dado/Quando/Entao. `start.ps1` executa a fase
`python /runner.py bdd` dentro do runner Docker. `validate_feature()` rejeita
passos desconhecidos; `run_bdd()` vincula cada passo a uma funcao executada:
`bdd_given()` faz login e consulta OS/diagnostico, `flow()` executa o Quando por
HTTP/mensageria e `bdd_then()` consulta os estados finais de pagamento/OS/Saga.
Nenhuma dependencia BDD adicional e necessaria. Erros encerram o processo com
codigo 1 e gravam `bdd.json`/`result.json` com falha; o coletor exige ambos aprovados.

O runner descarta deliberadamente a primeira resposta bem sucedida de aprovacao
somente nesta fixture. O orquestrador retoma pelo checkpoint validado, faz novo
login, consulta decisao persistida e nao aprova novamente. Reparo so inicia se
`PENDING`; trabalhos `IN_PROGRESS`/`COMPLETED` nao recebem outro POST de inicio.
Checkout repetido deve reutilizar a preferencia. `http-trace.json` registra a perda
e retomada sem armazenar headers ou corpos sensiveis. A opcao manual
`-ContinueAfterApproval` continua disponivel para esse checkpoint especifico.

## Falha controlada de dominio

Nao existe endpoint HTTP de falha de reparo. `RepairFailureFixture.java` e
compilada e executada somente no container isolado Execution, a partir dos mesmos
JARs verificados, e invoca o caso de uso transacional existente `RepairUseCase.fail`.
Exige banco `e2e_execution` e reparo `IN_PROGRESS`. Nao altera SQL/Mongo diretamente
nem adiciona rotas produtivas. O contexto Spring temporario usa porta aleatoria;
o evento e a compensacao percorrem Outbox, RabbitMQ e Inbox reais.
Os unicos writes SQL do runner provisionam/revogam grants e atribuicoes tecnicas.

Testes de autenticacao, RS256/JWKS, certificados invalidos, cliente terceiro,
mecanico sem atribuicao, deduplicacao, retry/DLQ, dependencia OS indisponivel e
recuperacao de broker continuam presentes. Nenhuma porta da stack e exposta no host.
O mock Mercado Pago permanece em loopback do Billing.

Para parar somente a nova stack, preservando os volumes:

```powershell
$manifest = Get-Content '<RunRoot>/manifest.json' -Raw | ConvertFrom-Json
& 'C:/Program Files/Docker/Docker/resources/bin/docker.exe' compose --env-file '<RunRoot>/private.env' -p $manifest.project -f integration/9.1.4/compose.yaml stop
```

Nao usar `prune` ou `down -v`. Certificados duram dois dias. Nao reutilizar uma
execucao parcial fora dos checkpoints documentados. Nao executar commit/push
antes da revisao. `relatorio.md` preserva as tentativas anteriores. A execucao de
29/10/2026 foi aprovada com 206 verificacoes integradas e 641 testes Maven;
o resultado atual consta em
`evidence/41341594-ea06-4522-a7e5-804f9497e4a7/relatorio.md`.
