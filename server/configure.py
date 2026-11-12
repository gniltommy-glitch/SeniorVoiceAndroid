"""Enter provider keys locally without chat, shell history or APKs; preserve existing keys."""
import argparse
import getpass
import json
import os
import secrets
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--provider', choices=['opengateway', 'openrouter', 'groq', 'deepseek', 'gemini'])
args = parser.parse_args()
path = Path(__file__).with_name('.env')
data = path.read_text() if path.exists() else Path(__file__).with_name('.env.example').read_text()
providers = [args.provider] if args.provider else ['opengateway', 'openrouter', 'groq']
for provider in providers:
    key = getpass.getpass(provider + ' API key (hidden; Enter keeps current value): ').strip()
    if not key:
        continue
    if '\n' in key or '\r' in key:
        raise SystemExit('Invalid key format')
    field = provider.upper() + '_API_KEY'
    lines = data.splitlines()
    if any(line.startswith(field + '=') for line in lines):
        lines = [field + '=' + key if line.startswith(field + '=') else line for line in lines]
    else:
        lines.append(field + '=' + key)
    data = '\n'.join(lines) + '\n'
if not path.exists():
    data = data.replace('replace-with-a-random-token-at-least-24-characters', secrets.token_urlsafe(32))
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, 'w') as out:
    out.write(data)
os.chmod(path, 0o600)
print('Saved server/.env without changing provider order or exposing credentials.')
