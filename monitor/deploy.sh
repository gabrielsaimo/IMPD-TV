#!/usr/bin/env bash
# Sobe o monitoramento inteiro. Roda depois de `wrangler login`.
#
# Idempotente: pode rodar de novo sem duplicar nada. O banco só e criado se
# ainda nao existir, e o schema usa CREATE TABLE IF NOT EXISTS.
set -euo pipefail
cd "$(dirname "$0")"

say(){ printf '\n\033[1m== %s\033[0m\n' "$1"; }

command -v wrangler >/dev/null || { echo "wrangler nao encontrado: npm i -g wrangler"; exit 1; }
wrangler whoami 2>/dev/null | grep -q "Account Name\|associated with the email" || {
  echo "Nao autenticado. Rode: wrangler login"; exit 1; }

say "1/7  Banco D1"
if wrangler d1 list --json 2>/dev/null | grep -q '"name": *"impd-tv"'; then
  echo "impd-tv ja existe"
else
  wrangler d1 create impd-tv
fi
DB_ID=$(wrangler d1 list --json | node -e '
  let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
    const db=JSON.parse(s).find(x=>x.name==="impd-tv");
    if(!db){console.error("banco impd-tv nao encontrado");process.exit(1)}
    process.stdout.write(db.uuid||db.database_id);});')
echo "database_id: $DB_ID"

say "2/7  Gravando o database_id no wrangler.toml"
node -e '
  const fs=require("fs");
  const p="wrangler.toml";
  let s=fs.readFileSync(p,"utf8");
  s=s.replace(/database_id = ".*"/, `database_id = "${process.argv[1]}"`);
  fs.writeFileSync(p,s);' "$DB_ID"

say "3/7  Criando as tabelas"
wrangler d1 execute impd-tv --remote --file=schema.sql -y

say "4/7  Segredo do painel"
tr -d '\n' < .dashboard-token | wrangler secret put DASHBOARD_TOKEN

say "5/7  Subindo o Worker"
wrangler deploy 2>&1 | tee .deploy.log
WORKER_URL=$(grep -oE 'https://[a-z0-9.-]+\.workers\.dev' .deploy.log | head -1)
[ -n "$WORKER_URL" ] || { echo "nao consegui ler a URL do Worker"; exit 1; }
echo "Worker no ar: $WORKER_URL"

say "6/7  Apontando o painel e o app para $WORKER_URL"
node -e '
  const fs=require("fs");
  const url=process.argv[1];
  let p="public/index.html", s=fs.readFileSync(p,"utf8");
  s=s.replace(/"https:\/\/[^"]*\/v1"/, `"${url}/v1"`);
  fs.writeFileSync(p,s);
  const kt="../androidtv/app/src/main/java/br/com/impd/tv/Telemetry.kt";
  let k=fs.readFileSync(kt,"utf8");
  k=k.replace(/private const val BASE_URL = "[^"]*"/, `private const val BASE_URL = "${url}/v1"`);
  fs.writeFileSync(kt,k);' "$WORKER_URL"

say "7/7  Subindo o painel"
wrangler pages deploy public --project-name impd-tv-painel --commit-dirty=true 2>&1 | tee .pages.log
PAGES_URL=$(grep -oE 'https://[a-z0-9.-]+\.pages\.dev' .pages.log | tail -1)

printf '\n\033[1mNo ar\033[0m\n'
printf '  API .... %s\n' "$WORKER_URL"
printf '  Painel . %s\n' "${PAGES_URL:-veja o log acima}"
printf '  Chave .. %s\n' "$(cat .dashboard-token)"
printf '\nO app ja aponta para a API. Falta subir o versionCode e gerar o APK.\n'
