"""Local launcher / synthetic smoke check. Does not print credentials or private prompts."""
import argparse
import json
import os
from pathlib import Path
from proxy import Gateway, handler_for
from http.server import ThreadingHTTPServer


def load_config():
    config = {}
    path = Path(__file__).with_name('.env')
    if path.exists():
        for line in path.read_text().splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                k, v = line.split('=', 1)
                config[k.strip()] = v.strip()
    config.update(os.environ)
    return config


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--doctor', action='store_true', help='Configuration status without exposing keys')
    parser.add_argument('--smoke', action='store_true', help='One synthetic alarm request; no personal data')
    parser.add_argument('--with-metered-fallbacks', action='store_true', help='Enable Gemini/DeepSeek after free routes; these APIs can incur charges')
    args = parser.parse_args()
    env = load_config()
    if args.with_metered_fallbacks:
        env['METERED_FALLBACKS'] = 'gemini,deepseek'
    if args.doctor:
        print(json.dumps({
            'free_only': env.get('FREE_ONLY', '1') != '0',
            'order': env.get('PROVIDER_ORDER', 'opengateway,openrouter,groq,gemini,deepseek'),
            'metered_fallbacks': sorted({p.strip() for p in env.get('METERED_FALLBACKS', '').split(',')} & {'gemini', 'deepseek'}),
            'configured': {p: bool(env.get(p.upper() + '_API_KEY')) for p in ('opengateway', 'openrouter', 'groq', 'gemini', 'deepseek')},
        }, ensure_ascii=False, indent=2))
        return 0
    try:
        gateway = Gateway(env)
    except (ValueError, json.JSONDecodeError):
        print('Server configuration missing or invalid. Run server/configure.py locally.')
        return 1
    if args.smoke:
        result = gateway.plan('Ngày mục tiêu: 2026-11-12. Yêu cầu thử nghiệm: đặt báo thức 7 giờ 30, nhãn uống nước. Không có dữ liệu cá nhân.')
        if result is None:
            print('Smoke check failed: all configured routes unavailable or rejected by free-price checks.')
            return 1
        p = result['plan']
        alarms = [s for s in p['steps'] if s['tool'] == 'set_medication_alarm']
        valid = len(p['steps']) == 1 and len(alarms) == 1 and alarms[0]['params']['hour'] in ('7', '07') and alarms[0]['params']['minute'] == '30'
        print(json.dumps({'smoke_passed': valid, 'provider': result['provider'], 'model': result['model']}, ensure_ascii=False))
        return 0 if valid else 1
    host = env.get('BIND_HOST', '127.0.0.1')
    port = int(env.get('PORT', '8787'))
    try:
        with ThreadingHTTPServer((host, port), handler_for(gateway)) as server:
            print(f'SeniorVoice gateway listening at {host}:{port}. Stop with Ctrl+C.', flush=True)
            server.serve_forever()
    except (OSError, PermissionError) as error:
        print('Cannot start local listener: ' + str(error))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
