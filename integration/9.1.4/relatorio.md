# Etapa 9.1.4 — Integracao E2E, BDD e compensacoes

## Identificacao

Execucao local: `4e2a089b-d4ba-45ee-80f5-8b0841370f44`, em 28/10/2026.
Projeto Docker: `oficina-e2e914-4e2a089bd4ba`. Branch `homolog` nos quatro repositorios.

**Resultado: regressao aprovada; aceite integrado pendente por indisponibilidade
do Docker/WSL. O fluxo E2E, o cenario BDD e as compensacoes ainda nao foram
executados nesta rodada.**

| Servico | Commit utilizado |
| --- | --- |
| Auth | `88fe949df9216fd861a2ad3844f2b0e5867883f6` |
| OS | `9391d3b9d4a5949a2c6447fc01becbb87d45142e` |
| Execution | `929c6de05e6b482d82fd097e6cd61ac1d38f9b30` |
| Billing | `bc70ca2bd5bc9032120c77812194ef50ca46c276` |

Os quatro repositorios estavam sem alteracoes locais na inspecao inicial.
Somente o harness Billing e `.gitignore` recebem alteracoes nesta etapa.

## Inspecao e adaptacao

O fluxo 8.3.5 foi inspecionado antes da adaptacao, incluindo runner, preparacao,
Compose, verificacao Maven, coleta de evidencias e retomada de aprovacao.
As implementacoes de controllers, casos de uso, modelos, notificacoes V5 e
maquina de estados da Saga foram confrontadas com as expectativas dos testes.

Foi criada uma area independente `integration/9.1.4/`, com scripts derivados do
harness anterior. Mantidos runtime Auth original, mock do provedor e imagens.
A stack anterior e seus volumes permanecem intactos; nao foram executados
AWS, Terraform, Kubernetes, deploy, workflows, commit ou push.

## Fluxos e BDD

O cenario de `oficina.feature` e executado pela fase `bdd` do runner Python.
Os passos usam login real Auth, HTTP com CA verificada, JWT RS256/JWKS e
autorizacao mTLS existente entre servicos. O inicio do diagnostico/reparo ocorre
somente por mecanico atribuido, apos confirmar `PENDING`. A notificacao e
consultada pelo proprietario; outro cliente, gerente e usuario anonimo sao negados.

A primeira resposta de aprovacao e descartada deliberadamente no runner.
A retomada consulta orcamento e decisao persistidos, sem repetir aprovacao.
O reparo so recebe inicio se `PENDING`, e so recebe conclusao se `IN_PROGRESS`.
Checkout repetido deve manter uma unica preferencia e pagamento confirmado.
Eventos de inicio operacional devem existir uma unica vez na Outbox Execution.

Os testes anteriores de autenticacao, autorizacao, revogacao de atribuicao,
deduplicacao Inbox, retry/DLQ, dependencia OS indisponivel e recuperacao de
RabbitMQ permanecem presentes. Bancos Postgres independentes e MongoDB replica
set continuam na mesma topologia do harness original.

## Compensacoes esperadas pelo dominio

| Cenario | Resultado definido pela implementacao |
| --- | --- |
| A: orcamento rejeitado | Orcamento `REJECTED`, OS `CANCELADA`, Saga `COMPENSATED`; sem reparo ou pagamento |
| B: falha fisica no reparo | Reparo `FAILED`, OS `EM_EXECUCAO`, Saga `FAILED`; intervencao manual registrada |
| C: falha tardia de diagnostico | Nova mensagem `REJECTED` na Inbox; OS, historico, Saga e Outbox preservados |

O cenario B usa uma fixture Java restrita ao ambiente isolado e invoca
`RepairUseCase.fail` com transacao, historico e Outbox reais. A compensacao
percorre `repair.failed.v1` → `repair.cancel.v1` → `repair.cancellation-failed.v1`.
Nenhum estado de negocio e forcado por SQL/Mongo. A ausencia de endpoint HTTP
de falha nao exige alterar codigo produtivo ou adicionar bypass de autorizacao.

## Comparacao com 8.3.5

| Aspecto | 8.3.5 | 9.1.4 |
| --- | --- | --- |
| Inicio operacional | Automatico no consumidor | `PENDING` e POST `/inicio` autorizado |
| Notificacao de orcamento | Nao verificada | API do proprietario, isolamento e V5 |
| BDD | Fluxo Python | `.feature` validada e executada pelo runner |
| Falhas da Saga | Gates anteriores | Rejeicao, falha fisica e evento tardio |
| Retomada | Checkpoint apos perda HTTP | Perda controlada e consulta do estado persistido |
| Evidencias | 134 checks aprovados, preservados | 0 checks E2E executados; ambiente bloqueado antes do provisionamento |

## Resultados observados

`mvn -B clean verify` concluiu com sucesso nos quatro servicos, usando Java 21.
Todos apresentam zero falhas, zero erros e zero testes ignorados.

| Servico | Testes | Cobertura de linhas |
| --- | ---: | ---: |
| Auth | 84 | 92,83% |
| OS | 155 | 92,36% |
| Execution | 157 | 94,61% |
| Billing | 245 | 95,49% |
| Total | 641 | Todos acima de 80% |

Sintaxe Python, sintaxe PowerShell e configuracao Compose aprovadas.
O parser BDD aceitou a feature declarada e rejeitou passo desconhecido.
A fixture `RepairFailureFixture.java` compilou com Java 21 contra as classes e
dependencias verificadas do Execution. As verificacoes de sintaxe/Compose foram
realizadas durante a adaptacao; a ultima mudanca de startup ainda nao foi
exercitada com Docker saudavel. `harness-validation.json` separa esses checks
da execucao BDD integrada pendente.
Os resultados separados por servico e suas suites estao em
`evidence/4e2a089b-d4ba-45ee-80f5-8b0841370f44/verification.json`; as revisoes
estao em `revisions.json`. `collection-status.json` identifica a coleta como
`REGRESSION_ONLY`, sem aprovar gates integrados.

| Fluxo | Resultado observado nesta rodada |
| --- | --- |
| E2E principal | Nao executado: startup nao atingiu readiness |
| BDD Dado/Quando/Entao | Implementado; parser verificado; cenario HTTP nao executado |
| Retomada apos aprovacao | Implementada; ainda nao exercitada nesta rodada |
| A: rejeicao | Implementado; nao executado |
| B: falha de reparo | Implementado; nao executado |
| C: falha tardia | Implementado; nao executado |
| Estado final de OS/Saga/pagamento | Nao produzido: nenhum fluxo de negocio iniciado |
| Mensageria integrada, DLQ e recuperacao | Preservados no harness; gates desta rodada nao executados |

O Billing informou schema Flyway em versao 5 na inicializacao. A validacao
integrada das migracoes Auth/OS/Billing e MongoDB ainda depende do preflight.
As suites Maven de migracao/persistencia passaram nos quatro servicos.

Os containers novos foram criados, mas os tres servicos Java nao atingiram
readiness no limite de seis minutos. Depois, Docker passou a retornar HTTP 500
ate em `ps` e `stop`, e WSL falhou com `Wsl/Service/0x8007274c` (timeout de
conexao). O host tinha 15,74 GB de RAM e apenas 0,89 GB livres na observacao.
Esses dados indicam pressao de recursos; a causa completa da indisponibilidade
da VM nao foi determinada.

A parada solicitada somente para as tres JVMs novas tambem falhou com HTTP 500.
Nao e possivel afirmar que estao paradas ou que os containers estao saudaveis.
O orquestrador pendente foi cancelado para evitar recriacao tardia. Nenhum comando
de parada/reinicio foi enviado aos containers preexistentes. Nao foi executado
restart global de Docker/WSL, que afetaria tambem o ambiente do usuario.
`environment-status.json` registra o ultimo estado observado e o bloqueio.
`legacy-integrity.json` comprova igualdade SHA-256 dos 46 arquivos de scripts
e evidencias publicadas da 8.3.5 entre a inspecao e o encerramento desta rodada.
Os artefatos privados antigos nao foram sobrescritos pelos novos scripts.

## Arquivos e seguranca das evidencias

Novos arquivos: `integration/9.1.4/{README.md,relatorio.md,oficina.feature,runner.py,
RepairFailureFixture.java,compose.yaml,Runner.Dockerfile,prepare.ps1,start.ps1,
verify.ps1,collect.ps1,export-provider.py}` e evidencias sanitizadas da execucao.
`.gitignore` exclui os novos artefatos privados. Logs completos, credenciais,
JWTs e chaves nao integram as evidencias publicaveis.

O coletor verifica versoes de runtime, containers saudaveis, preservacao dos
containers anteriores, testes/cobertura separados por servico e hashes dos JARs.

## Limites

Na primeira subida, o Auth encontrou timeout de 3 segundos no pool de conexoes
durante a inicializacao do Postgres. A primeira repeticao tambem revelou uma
fragilidade do script herdado: ele sobrescrevia os JARs montados enquanto as
JVMs iniciavam, causando falhas de carregamento de classes. `start.ps1` foi
corrigido para copiar apenas arquivos ausentes e exigir igualdade de hashes
nas retomadas. A inicializacao foi retomada antes de provisionar as fixtures
ou executar operacoes de negocio. Trata-se de correcao do harness; nao foi
necessario alterar codigo produtivo dos servicos.
Para reduzir leituras no bind mount Windows, o Compose novo tambem copia o JAR
imutavel para o filesystem interno antes de iniciar a JVM; o coletor verifica
o hash da copia executada. Essa ultima adaptacao ainda depende de validacao
integrada, pois Docker/WSL deixou de responder durante a tentativa de restart.

Para concluir o aceite, recuperar Docker/WSL com memoria disponivel e executar
novamente `start.ps1 -RunRoot <caminho desta execucao>`: o provisionamento nao
chegou a iniciar. Depois executar `collect.ps1` sem `-VerificationOnly`.
Se os certificados tiverem expirado, preparar uma execucao nova, preservando
os artefatos desta tentativa e as evidencias da 8.3.5.

O provedor de pagamento e simulado; nao ha chamada a Mercado Pago real.
A falha fisica requer intervencao manual, conforme o dominio existente.
Fixtures de identidade e atribuicao sao restritas ao ambiente de teste.
Certificados locais expiram em dois dias; uma nova rodada requer preparacao nova.

## Git

Auth, OS e Execution permanecem sem alteracoes locais, todos em `homolog`.
Billing permanece em `homolog`, com `.gitignore` modificado e apenas
`integration/9.1.4/` como nova area nao versionada. Artefatos privados estao
ignorados pelo Git. Nenhum commit, push, staging ou troca de branch executado.
Aguardar revisao do usuario; o aceite integrado permanece pendente.
