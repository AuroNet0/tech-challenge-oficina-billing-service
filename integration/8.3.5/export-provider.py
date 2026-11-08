import json
import os
import urllib.request


request = urllib.request.Request('http://127.0.0.1:8099/__admin/state',
                                 headers={'Authorization': 'Bearer ' + os.environ['MOCK_ACCESS_TOKEN']})
with urllib.request.urlopen(request, timeout=10) as response:
    state = json.load(response)
payments = [{name: payment[name] for name in ('id', 'status', 'transaction_amount', 'currency_id', 'live_mode', 'external_reference')}
            for payment in state['payments'].values()]
requests = [{'method': entry['method'], 'path': entry['path']} for entry in state['journal']]
if not any(payment['status'] == 'approved' and not payment['live_mode'] for payment in payments):
    raise SystemExit('Missing simulated approved payment')
if not any(entry['method'] == 'GET' and entry['path'].startswith('/v1/payments/') for entry in requests):
    raise SystemExit('Missing provider payment verification')
print(json.dumps({'preferences': list(state['preferences']), 'payments': payments, 'requests': requests}, indent=2))
