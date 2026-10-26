import base64
import hashlib
import hmac
import json
import os
import ssl
import sys
import time
import uuid
import urllib.error
import urllib.request
from pathlib import Path

import psycopg
from psycopg.rows import dict_row
from pymongo import MongoClient


ROOT = Path('/run')
RUNTIME = ROOT / 'runtime'
EVIDENCE = ROOT / 'evidence'
PASSWORD = os.environ['E2E_PASSWORD']
CONFIG = json.loads((RUNTIME / 'fixture.json').read_text(encoding='utf-8-sig'))
USERS = json.loads((RUNTIME / 'users.json').read_text(encoding='utf-8-sig'))
TOKENS = {}
CHECKS = []
TRACE = json.loads((EVIDENCE / 'http-trace.json').read_text()) if (EVIDENCE / 'http-trace.json').exists() else []
RESULT = {'status': 'RUNNING', 'runId': CONFIG['runId'], 'checks': CHECKS}
CONTEXT = ssl.create_default_context(cafile=str(ROOT / 'ca/ca.pem'))
SERVICE_CONTEXTS = {}
for service_name in ('os', 'execution', 'billing', 'runner'):
    service_context = ssl.create_default_context(cafile=str(ROOT / 'ca/ca.pem'))
    service_context.load_cert_chain(str(ROOT / service_name / 'client.pem'))
    SERVICE_CONTEXTS[service_name] = service_context


def save(name, value, private=False):
    directory = RUNTIME if private else EVIDENCE
    (directory / f'{name}.json').write_text(json.dumps(value, indent=2, default=str))


def check(condition, message):
    if not condition:
        raise AssertionError(message)
    CHECKS.append(message)
    print('PASS ' + message, flush=True)


def until(name, operation, timeout=150):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = operation()
        if value:
            return value
        time.sleep(0.5)
    raise TimeoutError(name)


def http(service, method, path, actor=None, body=None, certificate=None, headers=None):
    host = {'auth': 'auth-service', 'os': 'os-service', 'execution': 'execution-service', 'billing': 'billing-service'}[service]
    request_headers = {'Content-Type': 'application/json'}
    if actor:
        request_headers['Authorization'] = 'Bearer ' + TOKENS[actor]
    request_headers.update(headers or {})
    request = urllib.request.Request(f'https://{host}:8443{path}',
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers=request_headers, method=method)
    context = CONTEXT if certificate is None else SERVICE_CONTEXTS[certificate]
    try:
        with urllib.request.urlopen(request, context=context, timeout=60) as response:
            status, content = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, content = error.code, error.read()
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
        status, content = 0, b'{}'
    TRACE.append({'at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'phase': sys.argv[1], 'service': service, 'method': method, 'path': path, 'status': status, 'actor': actor, 'certificate': certificate})
    save('http-trace', TRACE)
    return status, json.loads(content) if content else None


def required(service, method, path, actor=None, body=None, **kwargs):
    status, response = http(service, method, path, actor, body, **kwargs)
    if status not in (200, 201, 202):
        raise AssertionError(f'{service} {method} {path}: expected success, received {status}')
    return response


def wait_api(service, path, actor, predicate=lambda value: True):
    def poll():
        status, value = http(service, 'GET', path, actor)
        return value if status == 200 and predicate(value) else None
    return until(f'{service} {path}', poll)


def login():
    for name, user in CONFIG['users'].items():
        response = required('auth', 'POST', '/api/v1/auth/login', body={'email': user['email'], 'senha': user['password']})
        TOKENS[name] = response['accessToken']
        check(response['tokenType'] == 'Bearer', 'login real ' + name)


def db(service):
    database = os.environ['AUTH_DB_NAME'] if service == 'auth' else 'e2e_' + service
    if not database.startswith('e2e_'):
        raise AssertionError('Fixture database must be isolated')
    return psycopg.connect(host=service + '-db', dbname=database, user='e2e', password=PASSWORD, row_factory=dict_row)


def rows(service, sql, params=()):
    with db(service) as connection:
        return connection.execute(sql, params).fetchall()


def mongo():
    return MongoClient('mongodb', username='e2e', password=PASSWORD, replicaSet='rs0', directConnection=True)


def broker(method, path, body=None):
    credentials = base64.b64encode(('e2e:' + PASSWORD).encode()).decode()
    request = urllib.request.Request('http://rabbitmq:15672/api' + path,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={'Authorization': 'Basic ' + credentials, 'Content-Type': 'application/json'}, method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        content = response.read()
        return json.loads(content) if content else None


def provider(method, path, body=None):
    request = urllib.request.Request('http://billing-service:8099' + path,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={'Authorization': 'Bearer ' + os.environ['MP_ACCESS_TOKEN'], 'Content-Type': 'application/json'}, method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def preflight():
    for service in ('os', 'execution', 'billing'):
        wait_api(service, '/actuator/health/readiness', None, lambda response: response.get('status') == 'UP')
        check(True, service + ' readiness UP por TLS validado')
    required('auth', 'GET', '/.well-known/jwks.json')
    check(True, 'JWKS acessivel por TLS validado')
    for service, expected in (('auth', '2'), ('os', '7'), ('billing', '4')):
        migrations = rows(service, 'SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank')
        check(migrations[-1]['version'] == expected and all(value['success'] for value in migrations), 'Flyway ' + service)
        save(service + '-migrations', migrations)
    with mongo() as client:
        check(client.admin.command('hello')['isWritablePrimary'], 'MongoDB PRIMARY')
    queues = broker('GET', '/queues/%2F')
    check(all(value.get('messages', 0) == 0 for value in queues), 'ausencia de mensagens antigas')
    save('preflight-queues', queues)
    bindings = broker('GET', '/bindings/%2F')
    save('bindings', bindings)
    sources = {value['source'] for value in bindings}
    check({'oficina.commands', 'oficina.events'}.issubset(sources), 'exchanges e bindings compartilhados')
    check(provider('GET', '/health')['status'] == 'UP', 'Mercado Pago simulado acessivel')


def provision():
    preflight()
    login()
    unknown = {'usuarioId': USERS['mecanico'], 'osId': str(uuid.uuid4()), 'capability': 'DIAGNOSTIC_READ', 'correlationId': str(uuid.uuid4())}
    status, _ = http('os', 'POST', '/internal/technical-authorization', 'mecanico', unknown, certificate='execution')
    check(status == 404, 'canal Execution para OS valida mTLS e JWT antes de resolver recurso inexistente')
    status, _ = http('os', 'POST', '/internal/technical-authorization', 'mecanico', unknown)
    check(status == 401, 'canal interno rejeita ausencia de certificado')
    unknown['usuarioId'] = USERS['clienteA']
    status, _ = http('os', 'POST', '/internal/technical-authorization', 'mecanico', unknown, certificate='execution')
    check(status == 403, 'certificado nao permite escolher usuario de terceiro')
    identity = {'clienteId': str(uuid.uuid4()), 'userAccessToken': TOKENS['clienteA'], 'requestId': str(uuid.uuid4()), 'issuedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
    status, response = http('auth', 'POST', '/internal/v1/identity/client-access/check', body=identity, certificate='os')
    check(status == 200 and response == {'autorizado': False}, 'canal OS para Auth valida usuario atual e nega vinculo inexistente')
    financial = {'usuarioId': USERS['clienteA'], 'osId': str(uuid.uuid4()), 'action': 'READ', 'correlationId': str(uuid.uuid4())}
    status, _ = http('os', 'POST', '/internal/financial-authorization', 'clienteA', financial, certificate='billing')
    check(status == 404, 'canal Billing para OS valida mTLS e JWT')
    branch_a, branch_b = str(uuid.uuid4()), str(uuid.uuid4())
    fixture_actor = CONFIG['runId']
    with db('os') as connection:
        for branch, code in ((branch_a, 'A'), (branch_b, 'B')):
            connection.execute('INSERT INTO filiais(id,nome,codigo,cidade,uf,ativo,created_at,updated_at) VALUES (%s,%s,%s,%s,%s,true,now(),now())',
                               (branch, 'Filial ficticia ' + code, code, 'Sao Paulo', 'SP'))
            for capability in ('OS_READ','OS_OPEN','CLIENT_READ','CLIENT_CREATE','VEHICLE_READ','VEHICLE_CREATE','BRANCH_READ'):
                connection.execute('INSERT INTO operational_grants(id,usuario_id,filial_id,capability,concedido_por,concedido_em) VALUES (%s,%s,%s,%s,%s,now())',
                                   (uuid.uuid4(), USERS['atendente'], branch, capability, fixture_actor))
        for capability in ('OS_READ','BRANCH_READ'):
            connection.execute('INSERT INTO operational_grants(id,usuario_id,filial_id,capability,concedido_por,concedido_em) VALUES (%s,%s,%s,%s,%s,now())',
                               (uuid.uuid4(), USERS['gerente'], branch_a, capability, fixture_actor))
        connection.execute("INSERT INTO administrative_grants(id,usuario_id,filial_id,capability,concedido_por,concedido_em) VALUES (%s,%s,%s,'SAGA_INSPECT',%s,now())",
                           (uuid.uuid4(), USERS['gerente'], branch_a, fixture_actor))
    state = {'filialA': branch_a, 'filialB': branch_b}
    for name, branch, cpf, plate in (('clienteB', branch_a, '12345678909', 'EAA1A23'), ('clienteA', branch_b, '98765432100', 'EAA2B34')):
        customer = required('os', 'POST', '/api/v1/clientes?filialId=' + branch, 'atendente',
                            {'nome': 'Cliente ficticio ' + name, 'cpfCnpj': cpf, 'email': name + '@example.invalid'})
        vehicle = required('os', 'POST', '/api/v1/veiculos?filialId=' + branch, 'atendente',
                           {'clienteId': customer['id'], 'placa': plate, 'marca': 'Ficticia', 'modelo': 'E2E', 'ano': 2025})
        required('os', 'GET', '/api/v1/filiais/' + branch, 'atendente')
        order = required('os', 'POST', '/api/v1/ordens-servico', 'atendente',
                         {'clienteId': customer['id'], 'veiculoId': vehicle['id'], 'filialId': branch, 'observacoes': 'E2E autenticado ' + CONFIG['runId']})
        state[name] = customer['id']
        state['veiculo' + name[-1]] = vehicle['id']
        state['osMain' if name == 'clienteB' else 'osOther'] = order['id']
    with db('os') as connection:
        for capability in ('OPERATIONAL_READ','VEHICLE_OPERATIONAL_READ','DIAGNOSTIC_READ','DIAGNOSTIC_WRITE','DIAGNOSTIC_COMPLETE','REPAIR_READ','REPAIR_WRITE','REPAIR_COMPLETE','HISTORY_READ'):
            connection.execute('INSERT INTO mechanic_assignments(id,usuario_id,os_id,capability,concedido_por,concedido_em) VALUES (%s,%s,%s,%s,%s,now())',
                               (uuid.uuid4(), USERS['mecanico'], state['osMain'], capability, USERS['gerente']))
    save('state', state, private=True)
    save('provisioning', {'users': {name: {'id': USERS[name], 'role': user['role']} for name, user in CONFIG['users'].items()},
                          'resources': state, 'grantProvenance': 'local fixture ' + fixture_actor,
                          'grants': rows('os', 'SELECT usuario_id,filial_id,capability,ativo FROM operational_grants'),
                          'assignments': rows('os', 'SELECT usuario_id,os_id,capability,ativo,concedido_por FROM mechanic_assignments')})
    check(True, 'fixtures persistidas com capacidades explicitas e sem acesso global')


def fingerprint():
    with mongo() as client:
        execution = {name: list(client.e2e_execution[name].find({}, {'_id': 0})) for name in ('diagnostics','work_orders','execution_history')}
        execution_event_ids = sorted(str(value['_id']) for value in client.e2e_execution.outbox_events.find({}, {'_id': 1}))
    return json.dumps({'execution': execution,
                       'orders': rows('os', 'SELECT id,status,version FROM ordens_servico ORDER BY id'),
                       'quotes': rows('billing', 'SELECT * FROM orcamentos ORDER BY id'),
                       'approvals': rows('billing', 'SELECT * FROM aprovacoes ORDER BY id'),
                       'payments': rows('billing', 'SELECT * FROM pagamentos ORDER BY id'),
                       'attempts': rows('billing', 'SELECT * FROM pagamentos_tentativas ORDER BY id'),
                       'notifications': rows('billing', 'SELECT count(*) AS count FROM provider_notifications'),
                       'osEventIds': rows('os', 'SELECT id FROM outbox_events ORDER BY id'),
                       'billingEventIds': rows('billing', 'SELECT event_id FROM outbox_events ORDER BY event_id'),
                       'executionEventIds': execution_event_ids}, default=str, sort_keys=True)


def denied(service, method, path, actor, body=None, expected=403, **kwargs):
    before = fingerprint()
    status, _ = http(service, method, path, actor, body, **kwargs)
    accepted = expected if isinstance(expected, tuple) else (expected,)
    check(status in accepted, f'negado {service} {method} {path} ator={actor} HTTP {status}')
    after = fingerprint()
    before_sections, after_sections = json.loads(before), json.loads(after)
    differences = [name for name in before_sections if before_sections[name] != after_sections[name]]
    check(after == before, 'sem mutacao apos negacao ' + str(len(CHECKS)) + ('; diferencas: ' + ','.join(differences) if differences else ''))


def internal_body(state, capability='DIAGNOSTIC_READ'):
    return {'usuarioId': USERS['mecanico'], 'osId': state['osMain'], 'capability': capability, 'correlationId': str(uuid.uuid4())}


def expired_token():
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    keys = json.loads((RUNTIME / 'auth-keys.json').read_text())
    key = next(value for value in keys['keys'] if value['kid'] == keys['activeKid'])
    private = serialization.load_der_private_key(base64.b64decode(key['privateKey']), password=None)
    original = TOKENS['mecanico'].split('.')
    claims = json.loads(base64.urlsafe_b64decode(original[1] + '=='))
    now = int(time.time())
    claims.update(iat=now - 120, nbf=now - 120, exp=now - 60)
    payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b'=').decode()
    message = (original[0] + '.' + payload).encode()
    signature = base64.urlsafe_b64encode(private.sign(message, padding.PKCS1v15(), hashes.SHA256())).rstrip(b'=').decode()
    return message.decode() + '.' + signature


def invalid_service_certificate():
    import datetime
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'execution-service')])
    now = datetime.datetime.now(datetime.timezone.utc)
    certificate = x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(private.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(minutes=1)).not_valid_after(now + datetime.timedelta(hours=1)).sign(private, hashes.SHA256())
    path = RUNTIME / 'invalid-client.pem'
    path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM) + private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    context = ssl.create_default_context(cafile=str(ROOT / 'ca/ca.pem'))
    context.load_cert_chain(str(path))
    SERVICE_CONTEXTS['invalid'] = context


def flow():
    login()
    state = json.loads((RUNTIME / 'state.json').read_text())
    os_id = state['osMain']
    saga = wait_api('os', '/api/v1/ordens-servico/' + os_id + '/saga', 'gerente', lambda value: value['status'] == 'WAITING_DIAGNOSTIC')
    save('saga-initial', saga)
    check(True, 'Saga WAITING_DIAGNOSTIC')
    diagnostic = wait_api('execution', '/api/v1/diagnosticos?osId=' + os_id, 'mecanico')
    diagnostic_id = diagnostic['id']
    for target in (os_id, state['osOther']):
        wait_api('os', '/api/v1/ordens-servico/' + target, 'atendente', lambda value: value['status'] == 'EM_DIAGNOSTICO')
    denied('os', 'GET', '/api/v1/ordens-servico/' + os_id, 'clienteA')
    denied('os', 'GET', '/api/v1/ordens-servico/' + state['osOther'], 'gerente')
    denied('execution', 'GET', '/api/v1/diagnosticos?osId=' + state['osOther'], 'mecanico')
    with mongo() as client:
        other = client.e2e_execution.diagnostics.find_one({'osId': state['osOther']})
        if not other:
            other = next(value for value in client.e2e_execution.diagnostics.find() if str(value.get('osId')) == state['osOther'])
        other_id = str(other.get('_id', other.get('id')))
    item = {'itemId': 'service-1', 'descricao': 'Servico ficticio', 'tipo': 'SERVICO', 'quantidade': '2', 'unidadePreco': 'HORA', 'precoPrevisto': '75.00'}
    denied('execution', 'POST', '/api/v1/diagnosticos/' + other_id + '/itens', 'mecanico', item)
    denied('execution', 'GET', '/api/v1/diagnosticos/' + diagnostic_id, 'semPapel')
    denied('execution', 'GET', '/api/v1/diagnosticos/' + diagnostic_id, None, expected=401, headers={'Authorization': 'Bearer invalid'})
    denied('execution', 'GET', '/api/v1/diagnosticos/' + diagnostic_id, None, expected=401, headers={'Authorization': 'Bearer ' + expired_token()})
    denied('os', 'POST', '/internal/technical-authorization', 'mecanico', internal_body(state), expected=401)
    denied('os', 'POST', '/internal/technical-authorization', 'mecanico', internal_body(state), certificate='billing')
    denied('os', 'POST', '/internal/technical-authorization', 'mecanico', internal_body(state), certificate='runner')
    denied('os', 'POST', '/internal/technical-authorization', 'mecanico', internal_body(state), certificate='execution', expected=401,
           headers={'Authorization': 'Bearer invalid'})
    invalid_service_certificate()
    denied('os', 'POST', '/internal/technical-authorization', 'mecanico', internal_body(state), certificate='invalid', expected=(0, 401, 403))
    forged = internal_body(state)
    forged['usuarioId'] = USERS['clienteA']
    denied('os', 'POST', '/internal/technical-authorization', 'mecanico', forged, certificate='execution')
    denied('os', 'POST', '/internal/technical-authorization', 'mecanico', internal_body(state, 'OPERATIONAL_READ'), certificate='execution')
    different = internal_body(state)
    different['osId'] = state['osOther']
    decision = required('os', 'POST', '/internal/technical-authorization', 'mecanico', different, certificate='execution')
    check(not decision['allowed'] and decision['osId'] == state['osOther'], 'decisao nega OS diferente sem reutilizar concessao')
    denied('auth', 'POST', '/internal/v1/identity/client-access/check', None,
           {'clienteId': state['clienteB'], 'userAccessToken': TOKENS['clienteB'], 'requestId': str(uuid.uuid4()), 'issuedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}, expected=401)
    with db('os') as connection:
        connection.execute("UPDATE mechanic_assignments SET ativo=false,revogado_por=%s,revogado_em=now(),version=version+1 WHERE usuario_id=%s AND os_id=%s AND capability IN ('DIAGNOSTIC_WRITE','DIAGNOSTIC_COMPLETE') AND ativo",
                           (USERS['gerente'], USERS['mecanico'], os_id))
    required('execution', 'GET', '/api/v1/diagnosticos/' + diagnostic_id, 'mecanico')
    denied('execution', 'POST', '/api/v1/diagnosticos/' + diagnostic_id + '/itens', 'mecanico', item)
    denied('execution', 'POST', '/api/v1/diagnosticos/' + diagnostic_id + '/conclusao', 'mecanico')
    check(True, 'leitura nao autoriza escrita; atribuicao revogada observada')
    with db('os') as connection:
        for capability in ('DIAGNOSTIC_WRITE','DIAGNOSTIC_COMPLETE'):
            connection.execute("INSERT INTO mechanic_assignments(id,usuario_id,os_id,capability,concedido_por,concedido_em) VALUES (%s,%s,%s,%s,%s,now())",
                               (uuid.uuid4(), USERS['mecanico'], os_id, capability, USERS['gerente']))
    save('assignment-audit', rows('os', 'SELECT * FROM mechanic_assignments ORDER BY concedido_em,id'))
    required('execution', 'POST', '/api/v1/diagnosticos/' + diagnostic_id + '/itens', 'mecanico', item)
    diagnostic = required('execution', 'POST', '/api/v1/diagnosticos/' + diagnostic_id + '/conclusao', 'mecanico')
    save('diagnostic', diagnostic)
    quote = wait_api('billing', '/api/v1/orcamentos?osId=' + os_id, 'clienteB')
    quote_id = quote['id']
    check(quote['status'] == 'PENDING_APPROVAL', 'orcamento criado por comando real')
    wait_api('os', '/api/v1/ordens-servico/' + os_id + '/saga', 'gerente', lambda value: value['status'] == 'WAITING_APPROVAL')
    denied('billing', 'GET', '/api/v1/orcamentos/' + quote_id, 'clienteA')
    denied('billing', 'POST', '/api/v1/orcamentos/' + quote_id + '/aprovar', 'clienteA', {})
    denied('billing', 'POST', '/api/v1/orcamentos/' + quote_id + '/aprovar', 'gerente', {})
    denied('billing', 'POST', '/api/v1/orcamentos/' + quote_id + '/aprovar', 'clienteB', {'responsavelId': USERS['clienteA']})
    denied('billing', 'GET', '/api/v1/orcamentos/' + quote_id, 'mecanico')
    summary = required('billing', 'GET', '/api/v1/orcamentos/' + quote_id, 'atendente')
    check(set(summary) == {'id','osId','status'}, 'projecao operacional minima atendente')
    manager_quote = required('billing', 'GET', '/api/v1/orcamentos/' + quote_id, 'gerente')
    check('itens' in manager_quote and 'valorTotal' in manager_quote, 'gerente consulta detalhes financeiros da filial autorizada')
    approval = required('billing', 'POST', '/api/v1/orcamentos/' + quote_id + '/aprovar', 'clienteB', {})
    finish_after_approval(state, approval, diagnostic_id)


def finish_after_approval(state, approval, diagnostic_id):
    os_id = state['osMain']
    quote_id = approval['orcamentoId']
    check(approval['responsavelId'] == USERS['clienteB'], 'aprovacao deriva identidade do JWT')
    save('approval', approval)
    repair = wait_api('execution', '/api/v1/reparos?osId=' + os_id, 'mecanico')
    wait_api('os', '/api/v1/ordens-servico/' + os_id, 'atendente', lambda value: value['status'] == 'EM_EXECUCAO')
    denied('execution', 'POST', '/api/v1/reparos/' + repair['id'] + '/conclusao', 'atendente')
    required('execution', 'PATCH', '/api/v1/reparos/' + repair['id'] + '/observacoes', 'mecanico', {'observacoes': 'Reparo ficticio concluido'})
    repair = required('execution', 'POST', '/api/v1/reparos/' + repair['id'] + '/conclusao', 'mecanico')
    save('repair', repair)
    payments = wait_api('billing', '/api/v1/pagamentos?osId=' + os_id, 'clienteB', lambda value: len(value) == 1)
    payment = payments[0]
    payment_id = payment['id']
    check(payment['status'] == 'PENDING', 'pagamento PENDING criado por comando real')
    denied('billing', 'POST', '/api/v1/pagamentos/' + payment_id + '/checkout', 'clienteA', {})
    denied('billing', 'POST', '/api/v1/pagamentos/' + payment_id + '/checkout', 'gerente', {})
    denied('billing', 'POST', '/api/v1/pagamentos/' + payment_id + '/checkout', 'atendente', {})
    denied('billing', 'GET', '/api/v1/pagamentos/' + payment_id, 'clienteA')
    checkout = required('billing', 'POST', '/api/v1/pagamentos/' + payment_id + '/checkout', 'clienteB', {})
    check(checkout['checkoutStatus'] == 'READY', 'checkout cria preferencia no provedor simulado')
    repeated = required('billing', 'POST', '/api/v1/pagamentos/' + payment_id + '/checkout', 'clienteB', {})
    check(repeated['preferenceId'] == checkout['preferenceId'], 'checkout idempotente reutiliza preferencia')
    invalid_body = {'type': 'payment', 'data': {'id': '123'}}
    denied('billing', 'POST', '/api/v1/webhooks/mercado-pago?data.id=123', None, invalid_body, expected=401,
           headers={'x-request-id': str(uuid.uuid4()), 'x-signature': 'ts=1,v1=' + '0' * 64})
    external = provider('POST', '/__admin/payments', {'preferenceId': checkout['preferenceId'], 'status': 'approved'})
    provider_id = str(external['id'])
    request_id, timestamp = str(uuid.uuid4()), str(int(time.time()))
    manifest = f'id:{provider_id};request-id:{request_id};ts:{timestamp};'
    signature = hmac.new(os.environ['MP_WEBHOOK_SECRET'].encode(), manifest.encode(), hashlib.sha256).hexdigest()
    webhook_headers = {'x-request-id': request_id, 'x-signature': f'ts={timestamp},v1={signature}'}
    webhook_body = {'type': 'payment', 'data': {'id': provider_id}}
    required('billing', 'POST', '/api/v1/webhooks/mercado-pago?data.id=' + provider_id, body=webhook_body, headers=webhook_headers)
    required('billing', 'POST', '/api/v1/webhooks/mercado-pago?data.id=' + provider_id, body=webhook_body, headers=webhook_headers)
    payment = wait_api('billing', '/api/v1/pagamentos/' + payment_id, 'clienteB', lambda value: value['status'] == 'CONFIRMED')
    order = wait_api('os', '/api/v1/ordens-servico/' + os_id, 'clienteB', lambda value: value['status'] == 'FINALIZADA')
    saga = wait_api('os', '/api/v1/ordens-servico/' + os_id + '/saga', 'gerente', lambda value: value['status'] == 'COMPLETED')
    check(True, 'pagamento CONFIRMED; OS FINALIZADA; Saga COMPLETED')
    check(len(provider('GET', '/__admin/state')['preferences']) == 1, 'uma unica preferencia apesar de repeticao do checkout')
    save('payment', payment)
    save('order-final', order)
    save('saga-final', saga)
    event = rows('os', "SELECT payload FROM outbox_events WHERE aggregate_id=%s AND event_type='diagnostic.start.v1'", (os_id,))[0]['payload']
    before = fingerprint()
    deliveries = broker('GET', '/queues/%2F/execution.diagnostic.start').get('message_stats', {}).get('deliver_get', 0)
    published = broker('POST', '/exchanges/%2F/oficina.commands/publish',
                       {'properties': {'content_type': 'application/json'}, 'routing_key': 'diagnostic.start.v1', 'payload': json.dumps(event), 'payload_encoding': 'string'})
    check(published['routed'], 'reentrega diagnostic.start roteada pelo RabbitMQ')
    until('reentrega consumida', lambda: broker('GET', '/queues/%2F/execution.diagnostic.start').get('message_stats', {}).get('deliver_get', 0) > deliveries)
    until('fila drenada apos reentrega', lambda: all(value.get('messages', 0) == 0 for value in broker('GET', '/queues/%2F')))
    check(fingerprint() == before, 'Inbox deduplica reentrega sem mutacoes')
    events = rows('billing', 'SELECT event_id,event_type,os_id,status,attempts FROM outbox_events ORDER BY sequence')
    os_events = rows('os', 'SELECT id,event_type,aggregate_id,status,attempts FROM outbox_events ORDER BY created_at')
    with mongo() as client:
        execution_events = list(client.e2e_execution.outbox_events.find({}, {'payload': 0}))
        save('execution-inbox', list(client.e2e_execution.inbox_messages.find({}, {'payload': 0})))
    event_types = {value['event_type'] for value in events + os_events}
    execution_types = {value.get('eventType') for value in execution_events}
    for expected in ('diagnostic.start.v1','quote.create.v1','quote.approved.v1','repair.start.v1','payment.request.v1','payment.confirmed.v1'):
        check(expected in event_types, 'evento persistido ' + expected)
    for expected in ('diagnostic.completed.v1', 'repair.completed.v1'):
        check(expected in execution_types, 'evento tecnico persistido ' + expected)
    save('billing-outbox', events)
    save('os-outbox', os_events)
    save('execution-outbox', execution_events)
    save('billing-inbox', rows('billing', 'SELECT event_id,event_type,os_id,status FROM inbox_messages ORDER BY received_order'))
    save('os-inbox', rows('os', 'SELECT event_id,event_type,saga_id,status FROM inbox_messages ORDER BY received_at'))
    save('financial-state', {'quotes': rows('billing', 'SELECT id,os_id,status,valor_total,version FROM orcamentos'),
                             'payments': rows('billing', 'SELECT id,os_id,status,valor,moeda,version FROM pagamentos'),
                             'attempts': rows('billing', 'SELECT id,pagamento_id,status,posicao FROM pagamentos_tentativas'),
                             'checkouts': rows('billing', 'SELECT payment_id,state FROM provider_checkouts'),
                             'sync': rows('billing', 'SELECT payment_id,attempts,last_sync_at,review_reason FROM provider_sync')})
    save('queues-final', broker('GET', '/queues/%2F'))
    before = fingerprint()
    check(broker('POST', '/exchanges/%2F/oficina.commands/publish', {'properties': {'content_type': 'application/json'},
          'routing_key': 'diagnostic.start.v1', 'payload': '{}', 'payload_encoding': 'string'})['routed'], 'comando invalido roteado para teste DLQ')
    until('DLQ recebeu comando invalido', lambda: any(value.get('messages', 0) > 0 and 'dlq' in value['name'] for value in broker('GET', '/queues/%2F')))
    check(fingerprint() == before, 'DLQ preserva dominios sem mutacoes')
    save('queues-dlq', broker('GET', '/queues/%2F'))
    state.update(paymentId=payment_id, diagnosticId=diagnostic_id, quoteId=quote_id, repairId=repair['id'], sagaId=saga['sagaId'])
    save('state', state, private=True)
    RESULT.update(totalChecks=len(CHECKS), finalStates={'payment': payment['status'], 'order': order['status'], 'saga': saga['status']}, ids=state)


def resume_after_approval():
    previous = json.loads((EVIDENCE / 'result.json').read_text())
    if previous['status'] != 'FAILED' or '/aprovar: expected success, received 0' not in previous.get('error', ''):
        raise AssertionError('Expected approval response-loss checkpoint')
    CHECKS.extend(previous['checks'])
    login()
    state = json.loads((RUNTIME / 'state.json').read_text())
    quote = required('billing', 'GET', '/api/v1/orcamentos?osId=' + state['osMain'], 'clienteB')
    check(quote['status'] == 'APPROVED', 'aprovacao confirmada pela API apos perda de resposta HTTP')
    approval = required('billing', 'GET', '/api/v1/orcamentos/' + quote['id'] + '/aprovacao', 'clienteB')
    diagnostic = required('execution', 'GET', '/api/v1/diagnosticos?osId=' + state['osMain'], 'mecanico')
    finish_after_approval(state, approval, diagnostic['id'])


def unavailable():
    login()
    state = json.loads((RUNTIME / 'state.json').read_text())
    denied('billing', 'POST', '/api/v1/pagamentos/' + state['paymentId'] + '/checkout', 'clienteB', {}, expected=503)
    denied('execution', 'PATCH', '/api/v1/diagnosticos/' + state['diagnosticId'] + '/observacoes', 'mecanico', {'observacoes': 'Nao persistir'}, expected=503)
    save('dependency-unavailable', {'status': 'PASSED', 'checks': CHECKS})


def outage():
    login()
    state = json.loads((RUNTIME / 'state.json').read_text())
    order = required('os', 'POST', '/api/v1/ordens-servico', 'atendente',
                     {'clienteId': state['clienteB'], 'veiculoId': state['veiculoB'], 'filialId': state['filialA'], 'observacoes': 'Recuperacao de broker E2E'})
    pending = until('Outbox retida sem broker', lambda: rows('os', "SELECT id,status,attempts,event_type FROM outbox_events WHERE aggregate_id=%s AND status IN ('PENDING','PROCESSING') AND attempts>0", (order['id'],)))
    check(bool(pending), 'OS aceita abertura autenticada e retém comando na Outbox durante indisponibilidade do broker')
    save('broker-outage', {'orderId': order['id'], 'outbox': pending, 'checks': CHECKS})
    save('recovery', {'osId': order['id']}, private=True)


def recover():
    state = json.loads((RUNTIME / 'recovery.json').read_text())
    def received():
        with mongo() as client:
            return client.e2e_execution.diagnostics.find_one({'osId': state['osId']})
    diagnostic = until('comando recuperado apos retorno do broker', received)
    outbox = until('Outbox publicada apos retorno', lambda: rows('os', "SELECT id,status,attempts,event_type FROM outbox_events WHERE aggregate_id=%s AND status='PUBLISHED'", (state['osId'],)))
    check(diagnostic is not None and bool(outbox), 'recuperacao apos falha de broker processa comando sem JWT humano')
    save('broker-recovery', {'osId': state['osId'], 'diagnosticId': str(diagnostic['_id']), 'outbox': outbox, 'checks': CHECKS})


try:
    if sys.argv[1] == 'provision':
        provision()
        save('preflight', {'status': 'PASSED', 'checks': CHECKS})
    elif sys.argv[1] == 'flow':
        flow()
        RESULT['status'] = 'PASSED'
    elif sys.argv[1] == 'resume-after-approval':
        resume_after_approval()
        RESULT['status'] = 'PASSED'
    elif sys.argv[1] == 'unavailable':
        unavailable()
    elif sys.argv[1] == 'outage':
        outage()
    elif sys.argv[1] == 'recover':
        recover()
    else:
        raise AssertionError('Unknown phase')
except Exception as error:
    RESULT['status'] = 'FAILED'
    RESULT['error'] = type(error).__name__ + ': ' + str(error)
    print(RESULT['error'], flush=True)
    save('result', RESULT)
    sys.exit(1)
finally:
    if sys.argv[1] in ('flow', 'resume-after-approval'):
        save('result', RESULT)
