# CI independente do Billing

O workflow `.github/workflows/ci.yml` atende PRs para `homolog`, pushes em
`homolog` e `workflow_dispatch`. Actions permanece desabilitado no repositorio;
a inclusao deste arquivo nao altera essa configuracao. Nao ha deploy nem
publicacao de imagens. A unica permissao declarada e `contents: read`.

O padrao segue o CI academico do Execution, adaptado para PostgreSQL e Billing.
O verificador `scripts/ci/verify.py` executa, em ordem:

1. Scanner basico de fontes/configuracoes versionadas e arquivos novos nao
   ignorados. Detecta chaves privadas, formatos de tokens conhecidos, URLs com
   credenciais e valores literais sensiveis nas fontes de producao e no CI.
   Os achados identificam somente caminhos, nunca valores. Referencias a
   variaveis de ambiente e NULL de SQL sao aceitas. O scanner e heuristico;
   nao garante ausencia absoluta de segredos. Nao inspeciona bibliotecas
   binarias, nao cria excecoes Netty/GnuTLS e nao depende da Infra.
2. Java 21 e Maven Wrapper `-B -ntp clean verify`, sem alterar versoes.
3. Relatorios Surefire/Failsafe e JaCoCo: exige testes presentes, zero falhas,
   erros e skips e cobertura LINE agregada >=80%. O POM tambem aplica esse
   limite no `verify`; XML ausente ou invalido reprova o verificador.
4. Build Docker `--platform=linux/amd64`, independente de `target/` local.
   Confere plataforma, usuario, ENTRYPOINT, SIGTERM, HEALTHCHECK e variaveis
   da imagem. O contexto Docker permite apenas fontes e arquivos do build,
   excluindo `.env`, evidencias, logs e relatorios locais.
5. Compose isolado com PostgreSQL 17, RabbitMQ 4 e Billing, sem portas no
   host e com rede interna. Usa senha sintetica aleatoria por execucao,
   espera servicos saudaveis e verifica Java 21 e UID efetivo nao root.
   Exige `UP` em `/actuator/health`, `/actuator/health/liveness` e
   `/actuator/health/readiness`, migrations Flyway aplicadas e pelo menos
   dois consumidores RabbitMQ. Como a saude padrao do Billing nao inclui
   RabbitMQ, essa verificacao do broker e independente dos endpoints HTTP.
6. Envia SIGTERM via `docker compose stop --timeout 30` e exige saida 0 ou
   143, sem OOM; saida 137 reprova. Remove containers, rede e volumes desse
   projeto Compose no `finally`, inclusive em falhas.

Mercado Pago e reconciliacao ficam desabilitados no smoke test, sem tokens
reais nem chamadas ao provedor. Checkout, webhooks, reconciliacao, Inbox/Outbox,
idempotencia e contratos da Saga continuam cobertos pelos testes existentes;
o smoke test nao substitui uma validacao oficial do provedor.

## Executar localmente

Pre-requisitos: Java 21, Python 3, Git e Docker com Compose e containers Linux.
As ferramentas precisam estar no PATH; o Wrapper resolve o Maven configurado.

```sh
python3 -B -m unittest discover -s scripts/ci -p 'test_*.py'
python3 -B scripts/ci/verify.py --output ci-reports
git diff --check
```

No Windows, use `python` em vez de `python3` quando necessario. Para validar
YAML com PyYAML disponivel: `python3 -B scripts/ci/validate_yaml.py`.

O verificador retorna 1 na primeira falha e marca gates posteriores como
`not_run`. Sempre escreve `verification.json` no diretorio solicitado, com
revisao, resultados dos gates, totais Maven, cobertura e evidencias do runtime.
O workflow coleta tambem `job.json` e publica somente JSON sanitizado como
artefato GitHub com retencao de sete dias, usando `if: always()`. A coleta nao
converte falhas em sucesso. Logs brutos e credenciais sinteticas nao entram
nos artefatos. `ci-reports/` e ignorado pelo Git e pelo contexto Docker.

Sugestao de commit apos revisao: `ci: add independent Billing verification`.

## Validacao local em 2026-11-08

Base: `homolog`, HEAD `c87c72f69c44978ff1211e19c582dd0c6ec2a6ad`, origin
`AuroNet0/tech-challenge-oficina-billing-service`. Arvore inicialmente limpa.

- Verificador completo: aprovado, com todos os seis gates executados.
- Maven Java 21: 245 testes, zero falhas, erros e skips.
- JaCoCo LINE: 1524 linhas cobertas de 1596, equivalente a 95,4887%.
- Docker: build linux/amd64, Java 21, usuario billing, UID efetivo 100.
- Runtime: PostgreSQL/RabbitMQ saudaveis, cinco migrations Flyway aplicadas,
  quatro consumidores RabbitMQ e tres endpoints de saude UP.
- SIGTERM: saida 143, sem OOM ou encerramento forcado.
- Oito testes dos scripts aprovados; workflow/Compose YAML validos.
- Scanner basico aprovado; nenhum segredo real detectado.
- Evidencia JSON local em `ci-reports/verification.json`, ignorada pelo Git.

Nao houve alteracao de logica de negocio ou versoes. Nenhum bloqueador de CI
foi identificado. A validacao nao executou cobrancas reais nem acesso oficial
ao Mercado Pago. Actions nao foi reabilitado ou executado remotamente; nao
houve AWS, deploy, commit ou push.
