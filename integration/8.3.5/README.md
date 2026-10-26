# E2E autenticado 8.3.5

Execução exclusivamente local. Requer Windows/PowerShell, Docker Desktop Linux, JDK 21,
Maven e OpenSSL (o Git para Windows já fornece OpenSSL). Os quatro repositórios devem
ser irmãos na branch homolog. Nenhum comando executa AWS, Terraform, workflows ou push.

1. Executar `verify.ps1` e conferir os quatro resultados em `artifacts/verify/result.json`.
2. Executar `prepare.ps1`. Guardar o caminho emitido como `RunRoot`.
3. Executar `start.ps1 -RunRoot <caminho>`. Exige os JARs da validação anterior.
4. Executar `collect.ps1 -RunRoot <caminho>` para reunir a evidência sanitizada e validar os gates.
5. Revisar `evidence/validation-global.json`, `result.json`, preflight, trace e snapshots.

Quando a política de scripts do Windows bloquear a execução, usar um processo isolado:

```powershell
& 'C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe' -NoProfile -ExecutionPolicy Bypass -File integration/8.3.5/verify.ps1
& 'C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe' -NoProfile -ExecutionPolicy Bypass -File integration/8.3.5/prepare.ps1
& 'C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe' -NoProfile -ExecutionPolicy Bypass -File integration/8.3.5/start.ps1 -RunRoot '<caminho emitido>'
```

Os parâmetros Maven/JavaHome/Git/Docker/OpenSsl permitem ajustar as instalações locais.
Cada preparação gera projeto, CA, certificados, usuários e volumes novos. Não reutilizar
o mesmo RunRoot para repetir provisionamento. Polling tem timeout e erros obrigatórios
produzem exit code diferente de zero. Senhas, JWTs e chaves permanecem em artifacts,
ignorado pelo Git. Esse diretório é privado; não publicar logs completos nem private.env.
Os serviços recebem somente seu certificado/chave e seus próprios dados de conexão.
O runner é uma fixture administrativa local com acesso aos bancos para preparar grants,
revogá-los e coletar evidência; não faz parte dos artefatos produtivos.

O vínculo Auth é idempotente para os dois pares exatos da fixture; falhas transitórias
de persistência/concorrência ou leitura de arquivo têm repetição limitada a 60 segundos.
Falhas de autorização/configuração encerram a execução. Se o provisionamento já tiver
preflight aprovado e houver interrupção antes do fluxo humano, `start.ps1 -RunRoot
<caminho> -ContinueAfterProvisioning` retoma desse checkpoint, revalida saúde e vínculos,
e continua exigindo autenticação/autorização reais. Não reutilizar essa opção após
conclusão parcial das operações humanas; preparar uma execução nova nesse caso.

Exceção controlada: `-ContinueAfterApproval` somente aceita o checkpoint específico de
perda de resposta do POST de aprovação. O runner preserva os checks anteriores, faz novo
login, consulta orçamento e decisão pela API do cliente e exige APPROVED e responsável
correto antes de continuar. Não repete aprovação nem altera banco/estado da Saga. O trace
registra a perda de resposta e a retomada. O timeout HTTP do harness é de 60 segundos;
os deadlines internos dos serviços não são ampliados por essa opção.

O Auth executa o handler Lambda original para login/JWKS com eventos API Gateway v2.
A rota interna está apenas no runtime HTTPS local em `auth/integration/8.3.5`, exige
certificado OS e verifica o JWT com o verificador real antes do caso de uso existente.
O handler de produção continua fechado para essa rota até existir integração AWS confiável.

Nenhuma porta da stack é publicada no host. Todos os HTTPs humanos e canais de
autorização usam TLS verificado pela CA da execução, sem trust-all. O certificado técnico
é separado do JWT humano original. O perfil `mtls` seleciona adapters reais; não remove
validação JWT nem concede acesso. Não ativar esse perfil atrás de terminação TLS sem
adaptar a prova de certificado: headers de proxy não são identidade técnica.

O mock Mercado Pago anterior é reutilizado somente na namespace de rede do Billing,
em loopback, com credenciais aleatórias locais. Não recebe token real de provedor.
Os bancos de desenvolvimento e containers preexistentes não são alterados.
Para parar somente a execução após revisar as evidências:

```powershell
$manifest = Get-Content '<RunRoot>/manifest.json' -Raw | ConvertFrom-Json
& 'C:/Program Files/Docker/Docker/resources/bin/docker.exe' compose --env-file '<RunRoot>/private.env' -p $manifest.project -f integration/8.3.5/compose.yaml stop
```

Volumes são preservados. Não usar prune ou down -v. Certificados duram dois dias;
chaves locais do Auth têm publicação antecipada de 90 segundos, conforme seu gerador.
Preparar uma nova execução após expiração. Usuários e concessões não são migrações
ou defaults produtivos. O segundo cliente permanece sem acesso à OS principal;
a OS secundária serve a testes negativos e não é declarada concluída.
