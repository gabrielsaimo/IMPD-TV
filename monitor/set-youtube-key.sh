#!/usr/bin/env bash
# Grava a chave da YouTube Data API no Worker e confere se ela chegou inteira.
#
# Existe porque o prompt do `wrangler secret put` gravou vazio duas vezes: ele
# responde "Success! Uploaded secret" mesmo sem receber nada, e o
# `wrangler secret list` mostra a chave como existente. So medindo o tamanho da
# para ver que o valor nunca chegou.
#
# Uso:
#   ./set-youtube-key.sh AIzaSy...          (a chave como argumento)
#   ./set-youtube-key.sh                    (le de monitor/youtube-key.txt)
set -euo pipefail
cd "$(dirname "$0")"

KEY="${1:-}"
if [ -z "$KEY" ] && [ -f youtube-key.txt ]; then
  KEY=$(tr -d ' \t\r\n' < youtube-key.txt)
fi

if [ -z "$KEY" ]; then
  echo "Nenhuma chave recebida."
  echo "Cole a chave em monitor/youtube-key.txt e rode de novo, ou passe como argumento."
  exit 1
fi

case "$KEY" in
  AIza*) ;;
  *) echo "Isso nao parece uma chave do Google (elas comecam com AIza). Recebido: ${#KEY} caracteres."; exit 1 ;;
esac

echo "Chave recebida: ${#KEY} caracteres, comeca com ${KEY:0:6}…"

printf '%s' "$KEY" | wrangler secret put YOUTUBE_API_KEY

echo
echo "Republicando para o Worker enxergar a chave…"
wrangler deploy > /dev/null 2>&1

echo "Conferindo do lado de fora…"
BASE="https://impd-tv-monitor.gabrielsaimo68.workers.dev"
TOKEN=$(cat .dashboard-token)
LEN=$(curl -s "$BASE/v1/admin/state" -H "authorization: Bearer $TOKEN" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["secrets"]["youtubeApiKey"])')

if [ "$LEN" = "0" ]; then
  echo "FALHOU: o Worker continua vendo a chave vazia."
  exit 1
fi
echo "OK: o Worker esta com ${LEN} caracteres."

echo
echo "Buscando a fileira de videos…"
curl -s "$BASE/v1/videos" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("videos:", d["count"], "| motivo:", d.get("reason") or "nenhum")
for v in d["videos"][:8]:
    print(" -", v["dateLabel"], "|", v["channel"][:24], "|", v["title"][:56])'

rm -f youtube-key.txt
echo
echo "Pronto. O arquivo com a chave foi apagado; ela vive so no Worker."
echo "As televisoes pegam a fileira na proxima abertura, sem aplicativo novo."
