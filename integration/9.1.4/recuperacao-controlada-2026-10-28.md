# Recuperação controlada e avaliação da retomada — 9.1.4

Observações de 28/10/2026, aproximadamente 16:00–16:06, America/Sao_Paulo.
RunRoot: `integration/9.1.4/artifacts/4e2a089bd4ba`.

## Resultado executivo

**O Engine já estava respondendo ao iniciar esta rodada. Nenhum reinício foi
executado pelo agente. O E2E permanece pendente por falta de margem de RAM no host.**

Os comandos `docker version`, `docker info`, `docker ps -a` e consultas dentro
de `docker-desktop` responderam sem HTTP 500 ou timeout. Não foi necessário usar
a autorização para `wsl --terminate docker-desktop`. Não foi possível atribuir
a recuperação a uma intervenção específica; a VM informou uptime de cerca de
dois minutos na primeira consulta.

## Engine e recursos

| Item | Observação |
| --- | --- |
| Estado anterior documentado | HTTP 500, timeouts WSL, global OOM e swap esgotada |
| Docker Engine / CLI | 29.7.2; Docker Desktop 4.90.0 |
| Engine e comandos de leitura | Respondendo normalmente |
| WSL | docker-desktop Running; Ubuntu Stopped; WSL 2 |
| VM | 8 CPUs; aproximadamente 7,63 GiB de RAM |
| Memória disponível na VM | 7068 MiB na amostra |
| Swap da VM | 2048 MiB totais, zero utilizado |
| Windows | 15,74 GiB totais; 1,07–1,27 GiB livres nas amostras |
| Disco C: | 190,73 GiB livres |

Maiores working sets observados no Windows: IntelliJ aproximadamente 2259 MiB,
vmmemWSL 1804 MiB, WebView2 1020 MiB e vários processos Edge entre 280 e 624 MiB
por processo. Esses valores não são limites configurados nem medem toda a memória
comprometida. Nenhuma aplicação do usuário foi encerrada.

A capacidade livre dentro da VM não garante capacidade física disponível no
Windows para novas alocações. Com apenas cerca de 1 GiB livre no host e OOM
recente, iniciar o harness representa risco de repetir o esgotamento.

## Inventário após recuperação

O inventário completo foi registrado em
`evidence/4e2a089b-d4ba-45ee-80f5-8b0841370f44/recovery-inventory-20261028T1904Z.json`.

- 114 containers existentes foram listados e inspecionados: 110 `exited` e
  quatro `created`; nenhum inspecionado estava em execução.
- O projeto `oficina-e2e914-4e2a089bd4ba` possui dez containers, todos parados.
- O projeto `oficina-e2e835-4a21f1818a36` possui dez containers, todos parados.
- Outras duas execuções 8.3.5, execuções Billing 7.6 e anteriores, projetos de
  desenvolvimento e demais projetos também constam do inventário, sem execução.
- `docker stats` não retornou amostras de containers em execução.

`docker info` informou três running e 111 stopped, divergindo de `ps` e da
inspeção individual. A causa dessa diferença de contadores não foi determinada;
nenhum restart adicional foi realizado para tentar corrigi-la.

Flags `OOMKilled=true` confirmadas:

| Projeto | Containers |
| --- | --- |
| 9.1.4 atual | Billing, OS e Execution |
| 8.3.5 principal | Billing, OS, Execution e Auth |
| oficina-billing | Billing |
| Execution de desenvolvimento | Execution e MongoDB |

As flags e códigos de saída são metadados dos encerramentos anteriores, não
ações executadas nesta rodada. Não foi tentado reiniciar bancos ou stacks antigas.

## Persistência e preservação

Foram conferidos os mounts e a existência dos cinco volumes nomeados de cada
execução principal: auth-data, os-data, billing-data, mongo-data e rabbit-data.
Os dez volumes das stacks 8.3.5 e 9.1.4 continuam presentes.

Não houve exclusão ou alteração de dados. A existência dos volumes não comprova
integridade transacional após crash: os bancos estão parados, e sua consistência
ainda deverá ser verificada ao subir somente o ambiente autorizado. Não é possível
certificar ausência de perda de escritas em andamento durante o OOM anterior.
Nenhuma evidência da 8.3.5 foi sobrescrita.

## Pré-condições da execução existente

Verificações feitas somente por leitura, sem iniciar containers:

| Artefato | Resultado |
| --- | --- |
| JAR OS | SHA-256 da cópia coincide com o JAR verificado |
| JAR Execution | SHA-256 da cópia coincide com o JAR verificado |
| JAR Billing | SHA-256 da cópia coincide com o JAR verificado |
| CA e certificados dos cinco participantes | Válidos; expiram em 30/10/2026, aproximadamente 14:37–14:38 -03:00 |
| runtime/state.json e runtime/users.json | Ausentes |
| evidence/preflight.json e evidence/http-trace.json | Ausentes |
| Última alteração do startup | Presente no Compose: copiar `/app.jar` para `/tmp/oficina-app.jar` |

Os checkpoints ausentes são compatíveis com a interrupção anterior ao
provisionamento. Não foi feita consulta a bancos parados para afirmar ausência
de registros internos. A última alteração do startup ainda não foi validada em
execução; a validação depende de liberar memória e iniciar o harness.

O RunRoot pode ser reutilizado enquanto os certificados permanecerem válidos,
após conferir os gates de recursos e os checkpoints novamente.

## Intervenção mínima e autorização pendente

Não há stack antiga ativa identificada que precise ser parada para liberar RAM.
Portanto, não se propõe executar stop/up/restart em projetos antigos.

A menor intervenção é o usuário liberar memória no Windows fechando aplicações
ou abas dispensáveis de sua escolha. Como estimativa de planejamento, reservar
alguns GiB para o harness e suas fixtures, com folga adicional para o host; não
tratar 1 GiB livre como suficiente. Reavaliar a RAM antes de iniciar os dez
containers. Não foi alterado o limite de RAM ou swap do Docker/WSL.

Foi solicitada ao usuário a liberação de recursos. Até essa confirmação e uma
nova medição favorável, nenhuma carga E2E será iniciada. Se uma intervenção
adicional em aplicação, stack antiga ou configuração global passar a ser
necessária, seu alcance deverá ser apresentado para autorização específica.

Ao retomar, o coletor precisará distinguir o baseline anterior à recuperação
autorizada do inventário pós-recuperação aqui preservado. Os containers antigos
estavam running no baseline original e agora estão parados. Não se deve
reiniciá-los para satisfazer artificialmente o gate de preservação, nem
sobrescrever o baseline original. Esse ajuste de coleta permanece pendente.

## Resultados e próximos passos

E2E principal, BDD efetivo, rejeição de orçamento, falha física de reparo, evento
tardio, recuperação RabbitMQ, negativos de autorização e retomada após perda HTTP:
**não executados nesta rodada; zero verificações E2E novas**.

Regressão anteriormente aprovada, sem nova execução nesta rodada:

| Serviço | Testes | Cobertura de linhas |
| --- | ---: | ---: |
| Auth | 84 | 92,83% |
| OS | 155 | 92,36% |
| Execution | 157 | 94,61% |
| Billing | 245 | 95,49% |
| Total | 641 | Todos acima de 80% |

Após liberar memória: medir recursos novamente, registrar o baseline posterior
à recuperação, retomar o `start.ps1` existente somente no projeto 9.1.4, executar
todos os cenários e então o coletor completo. O critério de aceite integrado
permanece pendente; não há novos estados finais de OS, Saga ou pagamento.

## Ações e Git

Nesta rodada foram executadas somente inspeções e gravação deste relatório e
do inventário sanitizado. Nenhum container, volume, imagem ou dado persistente
foi excluído; nenhuma stack foi iniciada, parada ou reiniciada pelo agente.
Nenhum código produtivo, configuração global, Terraform, AWS, Kubernetes ou
workflow foi alterado. Não houve commit ou push.

As alterações anteriores do Billing permanecem: `.gitignore` modificado e
`integration/9.1.4/` não versionado, em `homolog`. Esta rodada acrescenta somente
este relatório e `recovery-inventory-20261028T1904Z.json` dentro dessa área.
