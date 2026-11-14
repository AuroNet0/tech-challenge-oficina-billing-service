# CI/CD do Billing

O [workflow](../.github/workflows/ci.yml) atende pull requests e pushes em `main` e `homolog`, além de `workflow_dispatch`. Define os jobs `verify` e `deploy`; a configuração remota de habilitação do GitHub Actions não é identificada pelos arquivos locais.

## Verificação

O job `verify` usa Java 21 e executa os testes dos scripts de CI, seguidos de [verify.py](../scripts/ci/verify.py):

1. Varredura heurística de segredos em arquivos rastreados e novos não ignorados: chaves privadas, tokens conhecidos e URLs com credenciais; também procura valores sensíveis literais em fontes/configurações selecionadas. Achados informam caminhos, sem imprimir valores.
2. Maven Wrapper `-B -ntp clean verify`. O POM aplica JaCoCo `LINE/COVEREDRATIO >= 0.80` no agregado `BUNDLE`.
3. Leitura de Surefire/Failsafe e JaCoCo: exige testes presentes, zero falhas, erros e ignorados e cobertura de linhas de pelo menos 80%.
4. Build Docker `linux/amd64` e inspeção de plataforma, usuário, ENTRYPOINT, SIGTERM, HEALTHCHECK e variáveis da imagem.
5. Runtime em [compose isolado](../scripts/ci/compose.yaml), com PostgreSQL, RabbitMQ e Billing: verifica usuário sem privilégios de root, Java 21, health/liveness/readiness `UP`, migrações Flyway e pelo menos dois consumidores.
6. Encerramento por SIGTERM, sem OOM, aceitando saída 0 ou 143. Os recursos desse projeto Compose são removidos no `finally`.

Mercado Pago e reconciliação ficam desabilitados no runtime de CI. Esse teste não comprova homologação no provedor oficial nem substitui o fluxo distribuído.

A análise SonarCloud ocorre após o verificador, usando o XML `target/site/jacoco/jacoco.xml`. A ausência de `SONAR_TOKEN` reprova a etapa. `sonar.qualitygate.wait` é verdadeiro **somente em push na main**: nos demais eventos há análise sem espera bloqueante pelo Quality Gate. O projeto configurado é [AuroNet0_tech-challenge-oficina-billing-service](https://sonarcloud.io/summary/new_code?id=AuroNet0_tech-challenge-oficina-billing-service).

A coleta usa `if: always()`, gera `job.json` e publica somente JSON sanitizado do diretório de verificação como artefato, com retenção de sete dias. Não converte falhas anteriores em sucesso.

## Deploy

O job `deploy` depende de `verify` e roda **somente em push na main**. Possui `contents: read` e `id-token: write`, assume a role configurada por OIDC e serializa deploys no grupo `deploy-billing-service`, sem cancelar o anterior.

O preflight consulta EKS e ECR. Ausência de cluster (`ResourceNotFoundException`) ou repositório (`RepositoryNotFoundException`) resulta em `deploy=skip`, contemplando ambiente desligado/removido. Outros erros reprovam o job. Esse teste não substitui a verificação posterior de acesso ao cluster e rollout.

Quando o ambiente existe, o workflow:

1. Faz checkout de `github.sha`.
2. Resolve a conta com STS e procura a imagem no ECR pela tag `GITHUB_SHA`.
3. Reutiliza a tag existente; se não existir, autentica no ECR, executa build e push. O fluxo não sobrescreve deliberadamente a tag encontrada; não verifica a política de imutabilidade do ECR.
4. Valida o digest `sha256`, atualiza kubeconfig e executa `kubectl set image` com referência por digest.
5. Aguarda `kubectl rollout status` por até 300 segundos. Em falha, coleta descrição e logs; não há rollback automático.
6. Registra serviço, SHA, digest e resultado no resumo quando o deploy tem sucesso ou é ignorado por ambiente ausente.

| Parâmetro | Valor no workflow |
| --- | --- |
| Região | `us-east-1` |
| Cluster / namespace | `tc-oficina-p4-demo-a` |
| ECR | `tc-oficina-p4-demo-a-billing` |
| Deployment / container | `billing-service` |

O [Dockerfile](../Dockerfile) expõe `8080` e verifica `/actuator/health/readiness`. A [configuração](../src/main/resources/application.yml) também expõe `/actuator/health` e `/actuator/health/liveness`; readiness inclui aplicação e banco, sem RabbitMQ.

## Secrets e variáveis

| Identificador | Configuração |
| --- | --- |
| `SONAR_TOKEN` | Secret do GitHub |
| `GITHUB_TOKEN` | Token disponibilizado pelo GitHub Actions |
| `AWS_ROLE_ARN` | Variável do GitHub acessada por `vars.AWS_ROLE_ARN` |

A role deve permitir as operações EKS, ECR e STS usadas no workflow e o acesso necessário ao Deployment. A política IAM e os recursos provisionados não são definidos nesse arquivo.

## Execução local

Pré-requisitos: Java 21, Python 3, Git e Docker com Compose e containers Linux, disponíveis no PATH.

```sh
python3 -B -m unittest discover -s scripts/ci -p 'test_*.py'
python3 -B scripts/ci/verify.py --output ci-reports
```

No Windows, use `python` quando necessário. Com PyYAML disponível, `python3 -B scripts/ci/validate_yaml.py` valida os YAML previstos pelo script. O verificador escreve `verification.json` no diretório solicitado e retorna 1 em falha; gates não executados ficam como `not_run`. A análise SonarCloud e o deploy são etapas do workflow, não desse comando local.

Esta revisão documental não executa CI, build ou deploy. O texto anterior descrevia uma versão sem deploy e afirmava Actions desabilitado; essas afirmações não representam o workflow atual.
