# Configuração e medição de memória — 29/10/2026

Não existe requisito de 4 GiB livres no Windows no harness. A tentativa anterior
foi interrompida por decisão preventiva, registrada em seu próprio relatório.
A execução atual usa medições de pressão, swap, OOM e readiness para decidir
se pode adicionar carga. Nenhum parâmetro de memória foi alterado nesta análise.

| Componente | Imagem efetiva do E2E | Limite de container | Configuração de memória / CPU |
| --- | --- | --- | --- |
| Auth | eclipse-temurin:21-jdk | Não definido | Runtime `-Xmx256m`; sem `-Xms`, sem CPU explícita |
| OS | eclipse-temurin:21-jdk | Não definido | `-Xmx384m -XX:ActiveProcessorCount=2` |
| Execution | eclipse-temurin:21-jdk | Não definido | `-Xmx384m -XX:ActiveProcessorCount=2` |
| Billing | eclipse-temurin:21-jdk | Não definido | `-Xmx384m -XX:ActiveProcessorCount=2` |
| PostgreSQL Auth | postgres:17-alpine | Não definido | Sem ajuste de shared_buffers, work_mem ou max_connections no Compose |
| PostgreSQL OS | postgres:17-alpine | Não definido | Mesmos defaults da imagem |
| PostgreSQL Billing | postgres:17-alpine | Não definido | Mesmos defaults da imagem |
| MongoDB | mongo:8.0 | Não definido | Replica set rs0, autenticação/keyfile; cache WiredTiger sem tamanho explícito |
| RabbitMQ | rabbitmq:4-management-alpine | Não definido | `+S 2:2 +SDcpu 1 +SDio 1`; watermark de memória sem ajuste explícito |
| Mercado Pago Mock | python:3.13-alpine | Não definido | Python sem configuração de memória; namespace de rede do Billing |
| Runner temporário | python:3.13-alpine com dependências Python | Não definido | Sem configuração de memória; executado depois de readiness |

Nenhum Compose define `mem_limit`, reservations, limites em deploy,
`JAVA_TOOL_OPTIONS`, `-Xms` ou `-XX:MaxRAMPercentage`. Os quatro heaps máximos
somam 1408 MiB; isso não limita RSS, metaspace, buffers diretos, pilhas, cache de
arquivos ou consumo dos bancos. Compilação e migração do Auth usam processos Java
temporários sem `-Xmx` explícito. A fixture de falha adiciona temporariamente uma
JVM `-Xmx256m -XX:ActiveProcessorCount=2` ao Execution, além da aplicação real.

`UseContainerSupport` e heap efetivo são conferidos com `jcmd 1 VM.flags -all`
após readiness. Sem limites de cgroup, reconhecer containers não implica limitar
automaticamente o consumo de cada processo. Não se presumem os defaults efetivos
das imagens como medições; configurações dos bancos devem ser consultadas no runtime.

## Diferenças entre 8.3.5 e 9.1.4

Os parâmetros de memória, imagens, bancos, RabbitMQ e healthchecks são idênticos.
As diferenças do Compose 9.1.4 são:

- OS, Execution e Billing copiam o JAR imutável para `/tmp/oficina-app.jar` antes
  de executar, evitando leituras aleatórias no bind mount Windows.
- Execution recebe a fixture Java de falha de reparo por montagem somente leitura.
- Runner recebe `oficina.feature` e simula perda da resposta HTTP de aprovação.

Os Dockerfiles produtivos não são usados neste E2E. OS e Execution usam builds
Maven em Dockerfiles multistage com JRE 21 no runtime; Billing usa JRE 21 Alpine.
Seus entrypoints não configuram heap. Auth não possui Dockerfile neste checkout;
o harness compila seu runtime de integração no container JDK. Os Dockerfiles dos
runners 8.3.5 e 9.1.4 usam a mesma base Python e as mesmas dependências fixadas.

## Inicialização e preservação

O script anterior executava `compose up` de todos os serviços de uma vez,
permitindo concorrência entre bancos e JVMs. Agora a inicialização usa um serviço
por vez, `--no-deps`, healthcheck obrigatório e amostras antes, durante e depois.
A ordem é PostgreSQL Auth, OS e Billing, MongoDB, RabbitMQ, Auth, OS, Execution,
Billing e mock. O mock não pode preceder Billing na topologia atual porque usa
`network_mode: service:billing-service` e atende o provedor em loopback. Ele sobe
após Billing e antes dos testes. Essa dependência é preservada.

O runner é construído antes de subir a stack. Maven executa sequencialmente nos
quatro repositórios, antes da stack E2E. Testcontainers de regressão podem criar
containers temporários próprios durante cada build. Logs e cópias dos relatórios
Maven antigos e novos ficam em diretório exclusivo da execução. O coletor recebe
esse diretório explicitamente, sem sobrescrever `artifacts/verify/` histórico.

`memory.ps1` registra `docker stats --no-stream`, estado/health/OOM/restarts,
memória e paginação do Windows, memória/swap/PSI/oom_kill da VM e processos relevantes.
`watch-memory.ps1` repete amostras durante builds e testes. Não há bloqueio por
quantidade fixa de RAM livre no Windows. Novos eventos OOM, containers unhealthy,
mais de um restart, VM com menos de 128 MiB disponíveis ou swap abaixo de 128 MiB
com PSI full avg10 acima de 20% interrompem novas cargas. Esses últimos valores
são guardas de pressão crítica, não dimensionamento de heap ou limites de container.
Readiness persistente tem timeout de 360 segundos por componente. Alertas ficam
registrados e impedem a continuação automática. Não há reinício global ou limpeza.

O escritor de métricas usa mutex por runId para coordenar as amostras do monitor
e do inicializador. Cada chamada do inicializador recebe uma pasta de logs própria,
preservando também as evidências de uma retomada antes do provisionamento.

Uma corrida do runner foi identificada na primeira tentativa: Billing já expunha
o orçamento, mas `quote.created.v1` ainda atualizava a OS enquanto o teste comparava
fingerprints de uma requisição negada. O histórico confirmou a transição legítima
`EM_DIAGNOSTICO -> AGUARDANDO_APROVACAO`. O runner agora aguarda explicitamente
Saga `WAITING_APPROVAL` e OS `AGUARDANDO_APROVACAO` antes dessas comparações.
Os códigos HTTP esperados e a comparação integral dos fingerprints permanecem iguais.
