import copy
import datetime
import decimal
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


def encode(value):
    if isinstance(value, decimal.Decimal):
        return format(value, 'f')
    if isinstance(value, dict):
        return '{' + ','.join(json.dumps(key) + ':' + encode(item) for key, item in value.items()) + '}'
    if isinstance(value, list):
        return '[' + ','.join(encode(item) for item in value) + ']'
    return json.dumps(value)


def evidence(value):
    if isinstance(value, decimal.Decimal):
        return format(value, 'f')
    if isinstance(value, dict):
        return {key: evidence(item) for key, item in value.items()}
    if isinstance(value, list):
        return [evidence(item) for item in value]
    return value


lock = threading.RLock()
preferences = {}
payments = {}
journal = []
controls = {'delay_next_create': False, 'hide_preferences': False}
token = os.environ['MOCK_ACCESS_TOKEN']


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, value):
        data = encode(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def handle_request(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path == '/health':
            return self.reply(200, {'status': 'UP'})
        if self.headers.get('Authorization') != 'Bearer ' + token:
            return self.reply(401, {'error': 'unauthorized'})
        try:
            body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or '{}',
                              parse_float=decimal.Decimal)
        except (ValueError, decimal.InvalidOperation):
            return self.reply(400, {'error': 'invalid_json'})
        delay = False
        with lock:
            if not path.startswith('/__admin'):
                journal.append({'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                'method': self.command, 'path': self.path, 'body': body})
            if path == '/__admin/state':
                return self.reply(200, evidence({'preferences': preferences, 'payments': payments,
                                        'journal': journal, 'controls': controls}))
            if path == '/__admin/config' and self.command == 'POST':
                controls.update({key: bool(value) for key, value in body.items() if key in controls})
                return self.reply(200, {'ok': True})
            if path == '/__admin/payments' and self.command == 'POST':
                preference = preferences.get(body.get('preferenceId'))
                if preference is None or body.get('status') not in ['pending', 'in_process', 'approved', 'rejected']:
                    return self.reply(400, {'error': 'invalid_state'})
                payment_id = str(200000 + int(preference['id'].split('-')[-1]))
                total = sum((item['unit_price'] * item['quantity'] for item in preference['items']), decimal.Decimal(0))
                payments[payment_id] = {'id': int(payment_id), 'collector_id': 42,
                    'external_reference': preference['external_reference'], 'metadata': preference['metadata'],
                    'transaction_amount': total, 'currency_id': 'BRL', 'live_mode': False,
                    'status': body['status'], 'date_last_updated': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    'order': {'id': 'order-' + payment_id, 'type': 'mercadopago'}}
                return self.reply(200, evidence(payments[payment_id]))
            if path == '/users/me':
                return self.reply(200, {'id': 42, 'tags': ['test_user']})
            if path == '/checkout/preferences/search':
                reference = parse_qs(parsed.query).get('external_reference', [''])[0]
                matches = [] if controls['hide_preferences'] else [value for value in preferences.values()
                           if value['external_reference'] == reference]
                offset = int(parse_qs(parsed.query).get('offset', ['0'])[0])
                return self.reply(200, {'elements': matches[offset:offset + 20], 'total': len(matches)})
            if path == '/checkout/preferences' and self.command == 'POST':
                preference_id = 'e2e-preference-' + str(len(preferences) + 1)
                response = copy.deepcopy(body)
                response.update({'id': preference_id, 'collector_id': 42,
                    'sandbox_init_point': 'https://sandbox.mercadopago.com.br/checkout/v1/redirect?pref_id=' + preference_id})
                preferences[preference_id] = response
                delay = controls['delay_next_create']
                controls['delay_next_create'] = False
            elif path.startswith('/checkout/preferences/'):
                preference_id = path.rsplit('/', 1)[-1]
                if preference_id not in preferences:
                    return self.reply(404, {'error': 'not_found'})
                if self.command == 'PUT':
                    preferences[preference_id].update(body)
                response = preferences[preference_id]
            elif path == '/v1/payments/search':
                reference = parse_qs(parsed.query).get('external_reference', [''])[0]
                matches = [value for value in payments.values() if value['external_reference'] == reference]
                offset = int(parse_qs(parsed.query).get('offset', ['0'])[0])
                return self.reply(200, {'results': matches[offset:offset + 20], 'paging': {'total': len(matches)}})
            elif path.startswith('/v1/payments/'):
                response = payments.get(path.rsplit('/', 1)[-1])
                if response is None:
                    return self.reply(404, {'error': 'not_found'})
            elif path.startswith('/merchant_orders/order-'):
                payment_id = path.rsplit('order-', 1)[-1]
                response = {'preference_id': 'e2e-preference-' + str(int(payment_id) - 200000)}
            else:
                return self.reply(404, {'error': 'not_found'})
            response = copy.deepcopy(response)
        if delay:
            time.sleep(8)
        return self.reply(200, response)

    do_GET = handle_request
    do_POST = handle_request
    do_PUT = handle_request


ThreadingHTTPServer(('0.0.0.0', 8099), Handler).serve_forever()
