import json
import unittest
from datetime import datetime, timezone
from proxy import Gateway
from test_proxy import TOKEN, plan, groq

NOW = datetime(2026, 11, 11, tzinfo=timezone.utc).timestamp()

def catalog(model, price='0', **extra):
    return {'data': [{'id': model, 'status': 'active', 'effective_pricing': {'prompt': price, 'completion': price}, **extra}]}

class FreeRouteTests(unittest.TestCase):
    def gateway(self, send, models, **values):
        env = {'DEVICE_TOKENS_JSON': json.dumps({'test': TOKEN}), 'FREE_ONLY': '1',
               'OPENGATEWAY_API_KEY': 'test-twig', 'OPENROUTER_API_KEY': 'test-router',
               'GROQ_API_KEY': 'test-groq', **values}
        return Gateway(env, send, catalog_transport=models, wall_clock=lambda: NOW)

    def test_qwen_before_router_and_groq(self):
        calls = []
        def send(url, headers, body):
            calls.append(url)
            self.assertEqual(body['model'], 'qwen/qwen3.8-flash')
            return groq(plan())
        gw = self.gateway(send, lambda _: catalog('qwen/qwen3.8-flash'))
        self.assertEqual(gw.plan('synthetic')['provider'], 'opengateway')
        self.assertEqual(len(calls), 1)

    def test_paid_qwen_skipped_and_router_has_zero_price_ceiling(self):
        calls = []
        def prices(url):
            return catalog('qwen/qwen3.8-flash', '0.001') if 'twigpine' in url else catalog('nvidia/nemotron-3.5-super-vl:free')
        def send(url, headers, body):
            calls.append(url)
            self.assertIn('openrouter.ai', url)
            self.assertEqual(body['provider']['max_price'], {'prompt': 0, 'completion': 0})
            return groq(plan())
        self.assertEqual(self.gateway(send, prices).plan('test')['provider'], 'openrouter')
        self.assertEqual(len(calls), 1)

    def test_expired_promotion_never_sent(self):
        gw = self.gateway(lambda *_: groq(plan()), lambda _: catalog('qwen/qwen3.8-flash', promo={'ends_at': '2026-11-10T00:00:00Z'}),
                          OPENROUTER_API_KEY='')
        self.assertEqual(gw.plan('test')['provider'], 'groq')

    def test_catalog_unavailable_falls_back_to_existing_groq(self):
        def fail(_):
            raise TimeoutError()
        gw = self.gateway(lambda *_: groq(plan()), fail)
        self.assertEqual(gw.plan('test')['provider'], 'groq')

    def test_paid_deepseek_and_gemini_disabled_in_free_mode(self):
        def no_request(*_):
            raise AssertionError('Paid provider must not be called')
        gw = self.gateway(no_request, no_request, PROVIDER_ORDER='deepseek,gemini', DEEPSEEK_API_KEY='test', GEMINI_API_KEY='test')
        self.assertIsNone(gw.plan('test'))

    def test_free_suffix_that_aliases_paid_model_is_rejected(self):
        gw = self.gateway(None, lambda _: catalog('retired:free', '0.01'))
        self.assertFalse(gw.model_is_free('openrouter', 'retired:free'))

    def test_missing_or_nan_price_rejected(self):
        for item in ({'id': 'test:free'}, {'id': 'test:free', 'pricing': {'prompt': 'NaN', 'completion': '0'}}):
            gw = self.gateway(None, lambda _: {'data': [item]})
            self.assertFalse(gw.model_is_free('openrouter', 'test:free'))

    def test_zero_price_paid_router_id_still_rejected(self):
        gw = self.gateway(None, lambda _: catalog('qwen/paid'))
        self.assertFalse(gw.model_is_free('openrouter', 'qwen/paid'))

    def test_changed_gateway_price_is_not_cached(self):
        items = iter([catalog('qwen/qwen3.8-flash'), catalog('qwen/qwen3.8-flash', '0.001')])
        gw = self.gateway(None, lambda _: next(items))
        self.assertTrue(gw.model_is_free('opengateway', 'qwen/qwen3.8-flash'))
        self.assertFalse(gw.model_is_free('opengateway', 'qwen/qwen3.8-flash'))

    def test_invalid_qwen_plan_falls_back_to_router(self):
        def send(url, *_):
            return groq(plan('delete_all')) if 'twigpine' in url else groq(plan())
        gw = self.gateway(send, lambda url: catalog('qwen/qwen3.8-flash') if 'twigpine' in url else catalog('nvidia/nemotron-3.5-super-vl:free'))
        self.assertEqual(gw.plan('test')['provider'], 'openrouter')

if __name__ == '__main__':
    unittest.main()
