import json
import unittest
from proxy import Gateway
from test_proxy import TOKEN, plan, gemini, groq

class OptionalApiTests(unittest.TestCase):
    def env(self, **values):
        return {'DEVICE_TOKENS_JSON': json.dumps({'test': TOKEN}),
                'FREE_ONLY': '1', 'PROVIDER_ORDER': 'gemini,deepseek',
                'GEMINI_API_KEY': 'test-google', 'DEEPSEEK_API_KEY': 'test-deepseek', **values}

    def test_optional_gemini_then_deepseek_on_quota(self):
        calls = []
        def send(url, headers, payload):
            calls.append(url)
            if 'googleapis.com' in url:
                self.assertIn('x-goog-api-key', headers)
                raise RuntimeError('429')
            self.assertEqual(url, 'https://api.deepseek.com/chat/completions')
            self.assertEqual(payload['model'], 'deepseek-flash')
            self.assertEqual(payload['response_format'], {'type': 'json_object'})
            self.assertEqual(payload['thinking'], {'type': 'disabled'})
            self.assertEqual(headers['Authorization'], 'Bearer test-deepseek')
            return groq(plan())
        gateway = Gateway(self.env(METERED_FALLBACKS='gemini,deepseek'), send)
        self.assertEqual(gateway.plan('synthetic')['provider'], 'deepseek')
        self.assertEqual(len(calls), 2)

    def test_gemini_opt_in_does_not_unlock_paid_router_or_gateway(self):
        def forbidden(*_):
            self.fail('A paid gateway request must not be sent')
        paid = {'data': [{'id': 'qwen/qwen3.8-flash', 'pricing': {'prompt': '1', 'completion': '1'}}]}
        gateway = Gateway(self.env(METERED_FALLBACKS='gemini,deepseek,opengateway,openrouter'),
                          forbidden, catalog_transport=lambda _: paid)
        self.assertEqual(gateway.metered_fallbacks, {'gemini', 'deepseek'})
        self.assertFalse(gateway.model_is_free('opengateway', 'qwen/qwen3.8-flash'))
        self.assertFalse(gateway.model_is_free('openrouter', 'qwen/paid'))

    def test_missing_deepseek_key_is_skipped_without_changing_gemini(self):
        calls = []
        def send(url, *_):
            calls.append(url)
            return gemini(plan())
        gateway = Gateway(self.env(METERED_FALLBACKS='gemini,deepseek', DEEPSEEK_API_KEY=''), send)
        self.assertEqual(gateway.plan('synthetic')['provider'], 'gemini')
        self.assertEqual(len(calls), 1)

if __name__ == '__main__':
    unittest.main()
