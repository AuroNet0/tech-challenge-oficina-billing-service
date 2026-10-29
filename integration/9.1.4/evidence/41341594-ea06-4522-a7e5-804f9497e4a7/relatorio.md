# Etapa 9.1.4 — execução e análise de memória

**Resultado: APROVADO. A stack funcionou neste computador nos cenários executados.**

RunId aprovado: `41341594-ea06-4522-a7e5-804f9497e4a7`.
Projeto: `oficina-e2e914-41341594ea06`.
Data: 29/10/2026, America/Sao_Paulo.
Coletor completo: `COMPLETE`. Verificações integradas: **206 aprovadas**.

## Ambiente e versões

| Serviço | Branch | Commit | Testes Maven | Cobertura de linhas |
| --- | --- | --- | ---: | ---: |
| Auth | homolog | 88fe949 | 84 | 92,83% |
| OS | homolog | 9391d3b | 155 | 92,36% |
| Execution | homolog | 929c6de | 157 | 94,61% |
| Billing | homolog | bc70ca2 | 245 | 95,49% |
| Total | | | **641** | Todos acima de 80% |

`mvn clean verify` foi efetivamente executado, sequencialmente, com Java 21 nesta
sessão. Todos os builds terminaram com exit code zero, zero falhas, zero erros e
zero testes ignorados. Esses builds pertencem à primeira tentativa desta sessão,
`544ddf1e-8ca5-4758-827c-63a9356ecf33`. A tentativa aprovada reutilizou os mesmos
JARs verificados, com SHA-256 conferidos antes da subida e no filesystem interno
dos containers. Não houve alteração produtiva ou recompilação desnecessária entre
as tentativas. `maven-provenance.json` explicita essa origem.

O preflight confirmou Docker Engine 29.7.2, Docker Desktop 4.90.0, Compose 5.5.1,
Engine responsivo, zero containers, zero imagens e 75 volumes após a limpeza
manual previamente informada. Windows: aproximadamente 0,85 GiB livres; VM:
aproximadamente 6,98 GiB disponíveis, 2 GiB de swap livre e PSI zero. Disco C:
aproximadamente 189 GiB livres. O contador `oom_kill` era zero. Os processos
Docker/WSL e Java foram registrados nas métricas privadas.

Não foi aplicado bloqueio por 4 GiB livres no Windows. Não foram alteradas
configurações globais de memória ou swap. Os certificados foram gerados novamente
para cada tentativa e os bancos/volumes são exclusivos de cada projeto.

## Diferenças dos harnesses

Os Compose 8.3.5 e 9.1.4 possuem as mesmas imagens e os mesmos parâmetros de
memória. As diferenças da 9.1.4 são a cópia dos JARs de OS/Execution/Billing para
`/tmp/oficina-app.jar`, a montagem da fixture de falha física no Execution, a
montagem de `oficina.feature` e a simulação de perda da resposta de aprovação no
runner. O Compose não precisou ser alterado nesta sessão.

Os Dockerfiles produtivos não são utilizados pelo E2E: ele monta os JARs verificados
em containers JDK 21. Os runners 8.3.5 e 9.1.4 usam o mesmo Dockerfile Python e
as mesmas dependências fixadas. A compilação Maven terminou antes da stack; o
runner foi construído antes da subida. Testcontainers executou apenas durante a
regressão de cada serviço.

| Componente | Limite de container | Heap máximo / configuração efetiva |
| --- | --- | --- |
| Auth | Nenhum | 256 MiB; heap inicial ergonômico de aproximadamente 124 MiB |
| OS | Nenhum | 384 MiB; heap inicial de aproximadamente 124 MiB; 2 CPUs |
| Execution | Nenhum | 384 MiB; heap inicial de aproximadamente 124 MiB; 2 CPUs |
| Billing | Nenhum | 384 MiB; heap inicial de aproximadamente 124 MiB; 2 CPUs |
| Cada PostgreSQL | Nenhum | shared_buffers 128 MiB; work_mem 4 MiB; maintenance_work_mem 64 MiB; 100 conexões |
| MongoDB | Nenhum | Replica set rs0; cache WiredTiger máximo configurado 3558866944 bytes, aproximadamente 3,31 GiB |
| RabbitMQ | Nenhum | 2 schedulers; watermark de memória relativo 0,6 |
| Mock / runner | Nenhum | Python, sem parâmetros de memória explícitos |

Nenhuma JVM define `-Xms`, `JAVA_TOOL_OPTIONS` ou `MaxRAMPercentage` no Compose.
`jcmd` confirmou `UseContainerSupport=true` nas quatro JVMs e os `-Xmx` esperados.
O `MaxRAMPercentage` efetivo permanece no default de 25%, com os heaps máximos
determinados explicitamente por `-Xmx`. Heap máximo não é limite de memória total
do processo. A fixture física adiciona temporariamente uma JVM de 256 MiB ao
Execution. Nenhum limite ou parâmetro de memória foi ajustado.

## Subida gradual e memória disponível

Cada serviço foi iniciado individualmente, com healthcheck/readiness e medições.
O mock subiu após Billing porque compartilha seu namespace de rede e atende em
loopback; subir o mock antes dele exigiria mudar essa topologia.

Primeiras amostras após readiness na execução aprovada, em MiB:

| Etapa | Windows livre | VM disponível | Swap livre |
| --- | ---: | ---: | ---: |
| PostgreSQL Auth | 1701 | 6965 | 2048 |
| PostgreSQL OS | 1769 | 6943 | 2048 |
| PostgreSQL Billing | 1693 | 6926 | 2048 |
| MongoDB | 1358 | 6815 | 2048 |
| RabbitMQ | 1051 | 6698 | 2048 |
| Auth | 1380 | 6625 | 2048 |
| OS | 876 | 6232 | 2048 |
| Execution | 2296 | 5914 | 2048 |
| Billing | 1795 | 5334 | 2048 |
| Mercado Pago Mock | 2107 | 5518 | 2048 |

Houve pausa para corrigir a instrumentação entre as medições de OS e Execution;
nenhum container saudável foi reiniciado nessa retomada. A memória livre do Windows
variou independentemente da adição de carga. As métricas registram valores reais,
não uma reserva garantida de capacidade.

## Consumo real dos containers

Valores em MiB de `docker stats --no-stream`; picos são máximos **amostrados** e
não necessariamente simultâneos. Incluem processos auxiliares dentro dos containers.

| Container | Amostra final | Pico amostrado |
| --- | ---: | ---: |
| Auth | 187,20 | 218,40 |
| PostgreSQL Auth | 37,39 | 41,13 |
| OS | 460,00 | 460,00 |
| PostgreSQL OS | 56,36 | 58,05 |
| Execution | 353,60 | 494,50 |
| MongoDB | 213,00 | 405,20 |
| Billing | 422,00 | 422,00 |
| PostgreSQL Billing | 60,83 | 61,28 |
| RabbitMQ | 89,84 | 180,00 |
| Mercado Pago Mock | 13,74 | 23,23 |
| **Total simultâneo final** | **1893,96** | Não somar picos individuais |

A execução aprovada tem 128 amostras registradas no resumo final. Mínimos:
Windows livre 701,35 MiB; VM disponível 5077,08 MiB; swap livre 2047,25 MiB.
PSI full avg10 máximo: 1,27%. Última amostra: Windows livre 1470,16 MiB e VM
disponível 5230,27 MiB. Não houve novo OOM: contador do kernel zero antes/depois.
A primeira tentativa também manteve zero OOM, mesmo com Windows chegando a
256,39 MiB livres. Isso refuta o uso de 4 GiB como bloqueio automático para estes
cenários; não estabelece capacidade para qualquer carga ou duração.

## E2E, BDD e compensações

| Validação | Resultado efetivamente observado |
| --- | --- |
| Fluxo principal | Aprovado: autenticação Auth, JWT RS256, diagnóstico PENDING/início/conclusão, orçamento/notificação, aprovação, reparo PENDING/início/conclusão e pagamento pelo mock |
| Estados principais | Pagamento CONFIRMED; OS FINALIZADA; Saga COMPLETED |
| BDD | Given/When/Then executados pelo runner de `oficina.feature`, 127 verificações no fluxo principal |
| Falha e retomada HTTP | Resposta de aprovação descartada; processo BDD retornou 1; retomada retornou 0, sem duplicar aprovação ou reiniciar reparo |
| A — orçamento rejeitado | REJECTED, OS CANCELADA, Saga COMPENSATED; sem reparo ou cobrança |
| B — falha física | Reparo FAILED, OS EM_EXECUCAO, Saga FAILED, intervenção manual registrada; sem cobrança |
| C — diagnóstico tardio | Inbox REJECTED; OS, histórico, Saga e Outbox integralmente preservados |
| Autorização | JWT inválido/expirado, certificado inválido, papel indevido, cliente terceiro, mecânico sem atribuição e revogação negados sem mutações |
| Idempotência | Checkout repetido com preferência única; webhook repetido; Inbox deduplicando redelivery sem mutações |
| Retry / DLQ | Comando inválido encaminhado à DLQ, preservando domínios |
| Dependência indisponível | OS parada de forma controlada: respostas 503 e fingerprints preservados; readiness restaurada |
| Broker indisponível | OS aceita comando, Outbox retém evento; após retorno, diagnóstico PENDING e Outbox PUBLISHED |

Foram aprovadas **206 verificações integradas**, além dos 641 testes Maven.
Os eventos percorreram RabbitMQ e os bancos reais e independentes; não foram
forçadas transições de negócio diretamente no banco. A falha física utilizou o
caso de uso existente pela fixture, sem endpoint produtivo adicional.

## Falhas de harness diagnosticadas e corrigidas

1. Primeira tentativa: o evento legítimo `quote.created.v1` alterou a OS durante
   uma comparação de ausência de mutação de requisição negada. O histórico e a
   Inbox confirmaram a transição. O runner agora aguarda Saga WAITING_APPROVAL e
   OS AGUARDANDO_APROVACAO antes dos fingerprints. As autorizações e comparações
   integrais não foram enfraquecidas. A tentativa e seus volumes foram preservados.
2. A escrita concorrente das métricas e a leitura durante a coleta geraram erros
   de compartilhamento de arquivo. Foram adicionados mutex, compartilhamento de
   leitura/escrita e retry limitado para IOException. A validação final executou
   cinco amostras reais com 261 leituras concorrentes, sem erro.
3. O monitor classificou o healthcheck do OS intencionalmente parado como falha.
   As paradas esperadas agora são declaradas explicitamente, limitadas ao OS e
   RabbitMQ deste projeto. OOM e restarts continuam bloqueando a execução. Os
   testes de indisponibilidade/recuperação foram repetidos com monitoramento e
   terminaram aprovados.
4. Logs Maven receberam destino absoluto e separado por execução. Os resultados
   inicialmente gravados em diretórios novos dos repositórios irmãos foram
   consolidados no Billing, sem alterar fontes. Cópias redundantes de relatórios
   encontraram o limite de caminho do Windows; os diretórios completos originais
   foram mantidos no arquivo privado e os relatórios atuais foram coletados de target.
5. Baseline de containers vazio passou a gerar arquivo explícito; o coletor aceita
   linhas vazias e preserva também containers preexistentes parados.

Essas falhas foram de sincronização/instrumentação do harness. Não foi comprovado
defeito produtivo nem insuficiência de memória para os testes realizados. Os alertas
originais continuam arquivados em artifacts, sem serem apresentados como OOM.

## Preservação, Docker e Git finais

- 354 arquivos históricos conferidos por SHA-256: zero alterações ou ausências.
  Os seis arquivos de harness intencionalmente alterados estão discriminados em
  `file-preservation.json`; novos arquivos não integram esse baseline histórico.
- Todos os 75 volumes originais continuam presentes. Total final: 87 volumes:
  os 75 originais, dez volumes nomeados dos dois projetos novos e dois volumes
  anônimos `/data/configdb` dos respectivos MongoDB. Nenhum volume foi excluído.
- Docker: dez containers saudáveis da execução aprovada e dez containers parados
  da primeira tentativa. Nenhum projeto de terceiros foi parado ou removido.
  O mock da primeira tentativa terminou com exit 137 durante a parada controlada;
  isso não deve ser confundido com OOM. Os indicadores do kernel permaneceram zero.
- Auth, OS e Execution: Git limpo, em homolog e nos commits esperados. Billing:
  `.gitignore` modificado e `integration/9.1.4/` não versionado, preservando o estado
  local anterior. Nenhum commit, push, checkout, reset ou pull foi executado.
- O monitor foi encerrado após a coleta. A stack aprovada permanece em execução
  para revisão, com seus volumes preservados.

## Arquivos alterados e evidências

Arquivos existentes alterados nesta sessão, sob `integration/9.1.4/`:
`README.md`, `prepare.ps1`, `start.ps1`, `verify.ps1`, `collect.ps1` e `runner.py`.
Novos auxiliares: `memory.ps1`, `watch-memory.ps1`, `summarize-memory.ps1`,
`recover.ps1`, `finalize.ps1` e `memory-analysis.md`.

Este diretório público contém relatório, resultado global, BDD, compensações,
estados finais, eventos/Inbox/Outbox, evidências financeiras, Maven/JaCoCo,
hashes, versões, preservação, configurações efetivas e resumo de memória.
`collection-status.json` registra `COMPLETE`. Certificados, chaves, credenciais,
fixtures de login e logs completos permanecem em `artifacts/`, ignorado pelo Git.

## Limites da conclusão

A stack funcionou com este volume de dados e estes cenários, consumindo cerca de
1,85 GiB na amostra final dos dez containers. Não é teste de carga, teste prolongado
nem garantia contra OOM com outras aplicações concorrentes ou bases maiores.
MongoDB e RabbitMQ continuam com limites internos calculados sobre a memória da
VM; seu consumo futuro pode aumentar. Não foi necessária otimização de heap,
limite de container ou configuração global. Os certificados locais duram dois dias.
Nenhuma ação AWS, Terraform, Kubernetes, CI/CD ou deploy externo foi executada.
