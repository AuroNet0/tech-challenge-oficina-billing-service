# Etapa 9.1.4 — preflight de 29/10/2026

RunId: `54f48b26-570c-414d-a1a3-11b180ff9dfa`.
Resultado: **BLOQUEADO POR MEMÓRIA INSUFICIENTE NO WINDOWS**.
Critério de aceite integrado não atendido nesta tentativa.

## Preflight e recursos

O Engine respondeu normalmente a `docker version`, `docker info`, `docker ps -a`
e `docker system df`. Compose disponível. A primeira tentativa no sandbox recebeu
negação de acesso; as consultas foram repetidas com acesso ampliado, somente leitura.
Nenhum problema de integridade foi observado nessas consultas; nenhuma carga foi
iniciada para realizar testes adicionais de estabilidade.

| Recurso | Medição |
| --- | --- |
| Docker Engine / Docker Desktop | 29.7.2 / 4.90.0 |
| Docker Compose | 5.5.1 |
| Windows RAM total | 15,75 GiB |
| Windows RAM livre inicial | 898000 KiB, aproximadamente 0,86 GiB |
| Windows RAM livre antes / depois da coleta | 887656 / 888672 KiB, aproximadamente 0,85 GiB |
| VM RAM total / disponível | 7,63 / 6,98 GiB |
| VM swap total / livre | 2 / 2 GiB |
| Windows arquivo de paginação total / utilizado | 27648 / 1392 MiB |
| Disco C: livre | 188,22 GiB |
| Disco de dados Docker disponível | aproximadamente 931,6 GiB |
| Containers totais / em execução | 0 / 0 |
| Imagens / volumes existentes | 0 / 75 |
| Cache de build existente | 317 registros, 19,67 GB reportados pelo Docker |

As amostras confirmam margem física criticamente baixa no host, muito inferior à
referência de planejamento de aproximadamente 4 GiB livres. A capacidade disponível
na VM e a paginação não garantem margem física no Windows. Conforme a condição de
interrupção solicitada, a execução terminou no preflight. Não foram iniciados Maven,
builds, geração de certificados, imagens ou containers. Nenhuma configuração global
de memória ou swap foi alterada.

## Versões e Git

Todos os repositórios estão em `homolog`, nos commits esperados:

| Serviço | Commit | Estado |
| --- | --- | --- |
| Auth | `88fe949` | Limpo |
| OS | `9391d3b` | Limpo |
| Execution | `929c6de` | Limpo |
| Billing | `bc70ca2` | `.gitignore` modificado; `integration/9.1.4/` não versionado |

As alterações locais preexistentes foram preservadas. Nenhum checkout, reset, pull,
commit ou push foi executado. Nenhum código produtivo foi alterado.

## Baselines e preservação

1. Baseline histórico: `integration/8.3.5/evidence/containers.json` registra dez
   containers da execução 8.3.5; `preservation.json` registra os recursos então
   preexistentes. Esses arquivos históricos continuam intactos.
2. Estado observado após a limpeza manual informada: zero containers, zero imagens,
   75 volumes. Não se atribui a remoção histórica a esta tentativa, nem se tenta
   recriar containers antigos.
3. Estado anterior a qualquer inicialização desta tentativa: os mesmos zero
   containers e 75 volumes, registrados em `before.json` privado.
4. Estado final: zero containers e os mesmos 75 volumes, registrados em `after.json`
   privado. Não houve inicialização de stack.

Foram conferidos SHA-256 de 360 arquivos preexistentes das árvores
`integration/8.3.5/` e `integration/9.1.4/`, incluindo evidências, diagnósticos,
documentação, resultados Maven e artefatos anteriores: zero arquivos alterados ou
ausentes. O inventário de volumes antes/depois é idêntico; isso comprova preservação
do inventário, sem alegar uma validação do conteúdo interno de cada banco.

## Resultados efetivamente executados

| Validação | Resultado nesta tentativa |
| --- | --- |
| Fluxo principal E2E | Não executado |
| BDD Given/When/Then | Não executado |
| A — orçamento rejeitado | Não executado |
| B — falha física de reparo | Não executado |
| C — diagnóstico tardio | Não executado |
| Autorização, recuperação e idempotência | Não executados |
| Verificações integradas aprovadas | 0 |
| Auth `mvn clean verify` / cobertura | Não executado / não medida |
| OS `mvn clean verify` / cobertura | Não executado / não medida |
| Execution `mvn clean verify` / cobertura | Não executado / não medida |
| Billing `mvn clean verify` / cobertura | Não executado / não medida |
| Coletor integrado completo | Não executado: depende dos cenários e builds |

Os 641 testes anteriores não são resultados desta tentativa. Os resultados antigos
não foram sobrescritos. Sem aplicações ativas, não há novos estados de OS, Saga,
pagamento ou eventos de negócio a reportar.

## Arquivos novos e evidências

- `evidence/54f48b26-570c-414d-a1a3-11b180ff9dfa/preflight.json`: resumo sanitizado.
- Este relatório, em diretório novo, sem substituir relatórios anteriores.
- `artifacts/54f48b26570c/`: script de captura, versões, revisões, snapshots antes e
  depois, inventário SHA-256, preservação e resultado detalhado; diretório privado
  já coberto pelo `.gitignore` existente. Nenhum certificado ou JWT foi gerado.

A captura não executa `prepare.ps1`, `start.ps1` ou `collect.ps1`, evitando reuso
de certificados e sobrescrita dos resultados Maven históricos. Nenhum recurso
Docker foi removido, parado ou reiniciado. Nenhuma ação AWS, Terraform, Kubernetes,
CI/CD ou deploy externo foi realizada.

Para retomar, liberar RAM no Windows e repetir o preflight com margem confortável
no host e na VM. Cerca de 4 GiB livres no Windows é uma referência inicial, não
garantia de capacidade. A futura execução integrada deverá receber outro runId,
certificados novos e nova pasta. A inicialização por grupos e a verificação de
readiness continuam pendentes junto com os cenários integrados.
