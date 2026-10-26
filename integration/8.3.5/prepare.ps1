param(
    [string]$JavaHome = 'C:/Users/arneto/.jdks/ms-21.0.12.1',
    [string]$Git = 'C:/Program Files/Git/cmd/git.exe',
    [string]$Docker = 'C:/Program Files/Docker/Docker/resources/bin/docker.exe',
    [string]$OpenSsl = 'C:/Program Files/Git/usr/bin/openssl.exe'
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$runId = [Guid]::NewGuid().ToString()
$suffix = $runId.Replace('-', '').Substring(0, 12)
$runRoot = Join-Path $PSScriptRoot "artifacts/$suffix"
New-Item -ItemType Directory -Force $runRoot | Out-Null
$runRoot = (Resolve-Path $runRoot).Path
foreach ($name in @('ca','auth','os','execution','billing','runner','jars','runtime','evidence')) {
    New-Item -ItemType Directory -Force (Join-Path $runRoot $name) | Out-Null
}
$env:E2E_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$env:AUTH_ENVIRONMENT = 'local'
$env:AUTH_LOCAL_KEY_FILE = Join-Path $runRoot 'runtime/auth-keys.json'
function Native($Executable, $Arguments) {
    $ErrorActionPreference = 'Continue'
    & $Executable @Arguments *> (Join-Path $runRoot 'prepare-native.log')
    if ($LASTEXITCODE -ne 0) { throw 'Preparation command failed; inspect private prepare-native.log' }
}
$keytool = Join-Path $JavaHome 'bin/keytool.exe'
$caStore = Join-Path $runRoot 'ca/ca.p12'
$caPem = Join-Path $runRoot 'ca/ca.pem'
Native $keytool @('-genkeypair','-alias','ca','-keyalg','RSA','-keysize','3072','-dname',"CN=oficina-e2e-$suffix",'-ext','bc:c','-ext','ku=keyCertSign,cRLSign','-validity','2','-storetype','PKCS12','-keystore',$caStore,'-storepass:env','E2E_PASSWORD')
Native $keytool @('-exportcert','-rfc','-alias','ca','-keystore',$caStore,'-storepass:env','E2E_PASSWORD','-file',$caPem)
foreach ($service in @('auth','os','execution','billing','runner')) {
    $directory = Join-Path $runRoot $service
    $store = Join-Path $directory 'identity.p12'
    $subject = if ($service -eq 'runner') { 'e2e-runner' } else { "$service-service" }
    Native $keytool @('-genkeypair','-alias','identity','-keyalg','RSA','-keysize','2048','-dname',"CN=$subject",'-validity','2','-storetype','PKCS12','-keystore',$store,'-storepass:env','E2E_PASSWORD')
    Native $keytool @('-certreq','-alias','identity','-keystore',$store,'-storepass:env','E2E_PASSWORD','-file',"$directory/request.csr")
    Native $keytool @('-gencert','-alias','ca','-keystore',$caStore,'-storepass:env','E2E_PASSWORD','-infile',"$directory/request.csr",'-outfile',"$directory/certificate.pem",'-rfc','-validity','2','-ext',"san=dns:$subject,dns:localhost",'-ext','eku=serverAuth,clientAuth','-ext','ku=digitalSignature,keyEncipherment','-ext','bc=ca:false')
    Native $keytool @('-importcert','-noprompt','-alias','ca','-keystore',$store,'-storepass:env','E2E_PASSWORD','-file',$caPem)
    Native $keytool @('-importcert','-alias','identity','-keystore',$store,'-storepass:env','E2E_PASSWORD','-file',"$directory/certificate.pem")
    Copy-Item -LiteralPath $caPem -Destination "$directory/ca.pem"
    Native $OpenSsl @('pkcs12','-in',$store,'-passin','env:E2E_PASSWORD','-nodes','-out',"$directory/client.pem")
}
Native (Join-Path $JavaHome 'bin/java.exe') @('-cp',"$workspace/tech-challenge-oficina-auth/target/tech-challenge-oficina-auth.jar",'br.com.fiap.oficina.auth.infrastructure.config.GenerateLocalKeys')
$roles = @{ atendente = 'ATENDENTE'; mecanico = 'MECANICO'; gerente = 'GERENTE'; clienteA = 'CLIENTE'; clienteB = 'CLIENTE'; semPapel = '' }
$users = [ordered]@{}
foreach ($name in $roles.Keys) {
    $users[$name] = @{ email = "$name.$suffix@example.invalid"; password = [Guid]::NewGuid().ToString('N'); role = $roles[$name] }
}
@{ runId = $runId; users = $users } | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $runRoot 'runtime/fixture.json')
$configuration = [ordered]@{
    WORKSPACE_ROOT = $workspace.Replace('\','/'); RUN_ROOT = $runRoot.Replace('\','/'); E2E_RUN_ID = $runId
    E2E_PASSWORD = $env:E2E_PASSWORD; AUTH_DB_NAME = "e2e_auth_$suffix"
    MP_ACCESS_TOKEN = 'TEST-' + [Guid]::NewGuid().ToString('N'); MP_WEBHOOK_SECRET = [Guid]::NewGuid().ToString('N')
}
$configuration.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Set-Content -Encoding ASCII (Join-Path $runRoot 'private.env')
$manifest = [ordered]@{ runId = $runId; project = "oficina-e2e835-$suffix"; runRoot = $runRoot; revisions = [ordered]@{} }
foreach ($service in @('auth','os-service','execution-service','billing-service')) {
    $repo = Join-Path $workspace "tech-challenge-oficina-$service"
    $branch = & $Git -C $repo branch --show-current
    if ($branch -ne 'homolog') { throw "Expected homolog: $service" }
    $manifest.revisions[$service] = @{ branch = $branch; commit = (& $Git -C $repo rev-parse HEAD); changes = @(& $Git -C $repo status --short) }
}
& $Docker ps --format '{{json .}}' | Set-Content -Encoding UTF8 (Join-Path $runRoot 'evidence/original-containers.jsonl')
if ($LASTEXITCODE -ne 0) { throw 'Docker unavailable' }
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $runRoot 'manifest.json')
Write-Output $runRoot
