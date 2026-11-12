import json
import unittest
from proxy import Gateway, PARAMS, SCHEMA, validate_plan

TOKEN = 'x' * 32

def plan(tool='set_medication_alarm'):
    return {'spoken_response': 'Dạ con đã hiểu', 'steps': [{'tool': tool, 'params': {p: '' for p in PARAMS}}]}

def gemini(p):
    return {'candidates': [{'finishReason': 'STOP', 'content': {'parts': [{'text': json.dumps(p)}]}}]}

def groq(p):
    return {'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(p)}}]}

class GatewayTests(unittest.TestCase):
    def gateway(self, transport, **extra):
        env = {'DEVICE_TOKENS_JSON': json.dumps({'test': TOKEN}), 'GEMINI_API_KEY': 'test-g', 'GROQ_API_KEY': 'test-q', 'PROVIDER_ORDER': 'gemini,groq', 'FREE_ONLY': '0', **extra}
        return Gateway(env, transport)

    def test_gemini_wins_without_groq_call(self):
        calls = []
        def send(url, headers, body):
            calls.append(url)
            self.assertEqual(body['generationConfig']['responseJsonSchema'], SCHEMA)
            return gemini(plan())
        result = self.gateway(send).plan('anonymized')
        self.assertEqual(result['provider'], 'gemini')
        self.assertEqual(len(calls), 1)

    def test_groq_on_timeout_quota_or_invalid_plan(self):
        for failure in (TimeoutError(), RuntimeError('429'), {'candidates': []}, gemini(plan('delete_everything'))):
            calls = []
            def send(url, headers, body):
                calls.append(url)
                if 'googleapis' in url:
                    if isinstance(failure, Exception):
                        raise failure
                    return failure
                self.assertTrue(body['response_format']['json_schema']['strict'])
                return groq(plan())
            self.assertEqual(self.gateway(send).plan('test')['provider'], 'groq')
            self.assertEqual(len(calls), 2)

    def test_both_fail_then_circuit_breaks(self):
        now = [0]
        calls = []
        def send(*args):
            calls.append(args[0])
            raise TimeoutError()
        gw = self.gateway(send)
        gw.clock = lambda: now[0]
        for _ in range(3):
            self.assertIsNone(gw.plan('test'))
        self.assertEqual(len(calls), 6)
        self.assertIsNone(gw.plan('test'))
        self.assertEqual(len(calls), 6)
        now[0] = 121
        gw.plan('test')
        self.assertEqual(len(calls), 8)

    def test_auth_and_per_device_rate_limit(self):
        gw = self.gateway(None, REQUESTS_PER_MINUTE='2')
        self.assertEqual(gw.authorize(''), 401)
        self.assertEqual(gw.authorize('Bearer wrong'), 401)
        self.assertEqual(gw.authorize('Bearer ' + TOKEN), 200)
        self.assertEqual(gw.authorize('Bearer ' + TOKEN), 200)
        self.assertEqual(gw.authorize('Bearer ' + TOKEN), 429)

    def test_skip_missing_keys(self):
        gw = self.gateway(lambda *_: groq(plan()), GEMINI_API_KEY='')
        self.assertEqual(gw.plan('test')['provider'], 'groq')
        gw = self.gateway(None, GEMINI_API_KEY='', GROQ_API_KEY='')
        self.assertIsNone(gw.plan('test'))

    def test_reject_incomplete_response(self):
        def send(url, *_):
            if 'googleapis' in url:
                out = gemini(plan())
                out['candidates'][0]['finishReason'] = 'MAX_TOKENS'
                return out
            return groq(plan())
        self.assertEqual(self.gateway(send).plan('test')['provider'], 'groq')

    def test_empty_plan_is_clarification(self):
        validate_plan({'spoken_response': 'Bác nói lại nhé', 'steps': []})

    def test_local_route_never_calls_public_api(self):
        calls = []
        def send(url, headers, body):
            calls.append(url)
            self.assertEqual(body['format'], SCHEMA)
            return {'done': True, 'message': {'content': json.dumps(plan())}}
        gw = self.gateway(send, LOCAL_ENABLED='1')
        self.assertEqual(gw.plan('test', local_only=True)['provider'], 'ollama')
        self.assertEqual(calls, ['http://127.0.0.1:11434/api/chat'])
        gw = self.gateway(send, LOCAL_ENABLED='')
        self.assertIsNone(gw.plan('test', local_only=True))
        self.assertEqual(len(calls), 1)

    def test_schema_matches_android(self):
        from pathlib import Path
        self.assertEqual(json.loads((Path(__file__).parent.parent / 'app/src/main/assets/plan_schema.json').read_text()), SCHEMA)

if __name__ == '__main__':
    unittest.main()
