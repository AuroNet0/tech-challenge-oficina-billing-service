# 9.1.4 — Diagnóstico e retomada segura

Diagnóstico em 28/10/2026, aproximadamente 15:44–15:48, America/Sao_Paulo.
Execução existente: `4e2a089b-d4ba-45ee-80f5-8b0841370f44`.

## Resultado

**Docker/WSL continua indisponível para executar o E2E. Retomada interrompida.**
Há evidência direta de esgotamento de memória na VM Linux: o kernel registrou
`global_oom`, matou processos Java e informou swap de 2 GiB com zero livre.
A falha atual também atinge o acesso ao Engine, não apenas as aplicações Java.
Não foi determinado qual carga originou o esgotamento, nem comprovado que todo
erro de conectividade decorra exclusivamente do OOM.

Nenhuma recuperação, reinicialização ou alteração de configuração foi executada.
O único arquivo criado nesta rodada é este relatório; as evidências anteriores
e o código produtivo permanecem preservados.

## Estado observado

| Item | Observação |
| --- | --- |
| Docker CLI | 29.7.2, API 1.55, contexto `desktop-linux` |
| Docker Engine | `docker version` retornou HTTP 500 e `Server: null` |
| Listagem de containers | Sonda `docker ps -a` não respondeu em 20 segundos |
| WSL | Listagem responde: Ubuntu `Stopped`, docker-desktop `Running`, ambas WSL 2 |
| Execução dentro de docker-desktop | Sonda `uptime; free -m` não respondeu em 20 segundos |
| RAM do host | 15,74 GiB totais; livre oscilou de aproximadamente 0,55 a 1,72 GiB |
| Disco C: | 475,66 GiB totais; 177,31 GiB livres |
| vmmemWSL | Working set Windows de aproximadamente 472,6 MiB na amostra |
| Backend Docker | Dois processos: aproximadamente 21,1 e 100,9 MiB de working set |
| Interface Docker Desktop | Aproximadamente 39,8 MiB de working set agregado |

Working set do Windows não representa o limite configurado nem toda a memória
comprometida da VM. O espaço livre do disco C: não permite atestar o espaço livre
interno do filesystem Linux/VHD, que não pôde ser consultado.

O texto genérico do erro HTTP 500 menciona a versão da API. Isso não comprova
incompatibilidade de versões; os logs do proxy mostram falha de conectividade
com o endpoint interno do Engine.

## Evidências relevantes

Fontes locais consultadas, sem copiar logs completos:

- `%LOCALAPPDATA%/Docker/log/host/com.docker.backend.exe.log` e `monitor.log`.
- `%LOCALAPPDATA%/Docker/log/vm/init.log` e rotações de 28/10/2026.
- Baseline privado de containers da execução 9.1.4, usado somente para correlacionar IDs.

Trechos sanitizados dos logs da VM:

```text
15:17:26 -03:00: Free swap = 0kB; Total swap = 2097152kB
15:17:27 -03:00: global_oom; Out of memory: Killed process 2068247 (java)
15:22:55 -03:00: global_oom; Out of memory: Killed process 2141769 (java)
15:45:47 -03:00: global_oom; Out of memory: Killed process 2756618 (java)
```

O proxy Docker registrou repetidamente `no route to host` ao acessar o endpoint
interno `192.168.65.7:2376`, além de `HEAD /_ping Internal Server Error`.
O log Linux também registrou `UtilAcceptVsock() failed`.

Correlação de IDs com o baseline anterior:

| Processo morto por OOM | Container identificado |
| --- | --- |
| Java 2068247; container `a3736182adfa…` | `oficina-e2e835-4a21f1818a36-billing-service-1` |
| Java 2141769; container `db8f2ffc4605…` | `oficina-e2e835-4a21f1818a36-os-service-1` |
| Java 2756618; container `8c49b409f7cf…` | Não identificado no baseline preexistente |

Portanto, não é possível afirmar que todos os containers antigos continuam
saudáveis. O kernel atingiu ao menos dois processos da stack 8.3.5. Isso ocorreu
por OOM; nenhum comando de parada foi enviado a esses containers nesta rodada.
O estado atual dos containers antigos e dos criados pela 9.1.4 continua
indeterminado porque a API Docker não respondeu.

## Ações seguras executadas

Somente consultas de versões, WSL, memória, disco, processos, logs e Git.
As duas sondas com limite de 20 segundos cancelaram exclusivamente os próprios
processos CLI de diagnóstico. Não encerraram Engine, serviços WSL ou containers.
Nenhuma tentativa de startup ou execução de cenários foi realizada.
Nenhum certificado, JAR, banco, volume, imagem ou evidência anterior foi alterado.

## Recuperação que exige autorização

A hipótese principal é esgotamento de memória da VM Docker, acompanhado de
degradação da comunicação Engine/WSL. Antes de tentar recuperar, recomenda-se
liberar memória no host fechando aplicações dispensáveis escolhidas pelo usuário.
Não se recomenda simplesmente aumentar a RAM da VM enquanto o host está pressionado.
Nenhuma configuração global foi modificada.

Se Docker continuar indisponível após liberar memória, uma opção de recuperação,
**somente mediante autorização**, é terminar exclusivamente a distribuição Docker:

```powershell
wsl --terminate docker-desktop
```

Depois, reabrir/recuperar o Docker Desktop pela interface. O comando acima não
foi executado. Se a recuperação exigir reinício global adicional do Docker Desktop
ou WSL, seu alcance deve ser autorizado antes da execução.

Recursos afetados: todos os containers hospedados nessa distribuição, incluindo
a stack 9.1.4, a 8.3.5 e os ambientes anteriores de desenvolvimento. A distribuição
Ubuntu não é alvo do comando. Volumes e imagens não são excluídos, mas processos
em execução seriam interrompidos sem garantia de shutdown gracioso. Há risco de
perda de estado em memória e de escritas em andamento; Postgres/Mongo podem
precisar de recuperação de crash. O estado em memória do provedor simulado antigo
também pode ser perdido. A recuperação não garante que todos os containers
voltem automaticamente; cada projeto precisará ter seu estado conferido.

Não foram executados `wsl --shutdown`, restart do Docker/host, prune, exclusão,
`down -v` ou encerramento de processos de aplicações do usuário.

## Condições para retomar

Após recuperação autorizada e confirmação de recursos disponíveis:

1. Confirmar que `docker version`, `docker ps -a` e uma consulta dentro de WSL respondem.
2. Inventariar os containers antigos e novos, incluindo os atingidos por OOM.
3. Reavaliar a memória com as stacks concorrentes. Qualquer parada de ambiente
   preexistente ou ajuste global exige autorização específica.
4. Conferir hashes dos JARs da execução contra os artefatos verificados e a
   validade dos certificados; essas validações não foram repetidas nesta rodada.
5. Confirmar ausência de checkpoint de negócio inesperado antes de iniciar.
   Na rodada anterior, o provisionamento não chegou a começar.
6. Executar o `start.ps1` existente da 9.1.4 para validar também a última alteração
   de startup, que copia o JAR para o filesystem interno do container.
7. Executar o coletor completo somente após os cenários e gates efetivamente passarem.

Comandos de retomada já existentes, **não executados neste diagnóstico**:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File integration/9.1.4/start.ps1 -RunRoot 'C:/Users/arneto/workspace/tech-challenge-oficina-billing-service/integration/9.1.4/artifacts/4e2a089bd4ba'
powershell -NoProfile -ExecutionPolicy Bypass -File integration/9.1.4/collect.ps1 -RunRoot 'C:/Users/arneto/workspace/tech-challenge-oficina-billing-service/integration/9.1.4/artifacts/4e2a089bd4ba'
```

Não executar esses comandos enquanto Engine/WSL estiver indisponível. Se os
certificados expirarem, revisar a preparação de outra execução dentro do escopo
autorizado, preservando esta tentativa e a 8.3.5.

## Resultado E2E e Git

E2E principal, BDD completo, rejeição, falha de reparo, evento tardio, recuperação
de broker e negativos de autorização: **não executados nesta rodada**.
Não há novos estados finais de OS/Saga/pagamento. Os 641 testes Maven aprovados
na rodada anterior permanecem evidência de regressão, não de integração completa.

Os quatro repositórios permanecem em `homolog`. Auth, OS e Execution estão sem
alterações locais. Billing mantém `.gitignore` modificado e `integration/9.1.4/`
não versionado; este diagnóstico acrescenta apenas este relatório nessa área.
Nenhum commit, push ou alteração produtiva foi realizado.
