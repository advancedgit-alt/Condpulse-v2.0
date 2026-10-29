#!/usr/bin/env bash
# CP_FILES_ENABLED ligado só no PostgreSQL descartável do CI, com emissor no formato do Supabase e chave de serviço
# sintética gerada aqui (nunca gravada nem impressa). Nenhuma chamada ao Storage acontece: prova que a API sobe com o
# módulo de arquivos, que as rotas de anexo exigem sessão e que a chave não aparece no log.
set -euo pipefail
test "${PORT:-}" = 8082
test "${DATABASE_URL:-}" = jdbc:postgresql://localhost:5432/condpulse_ci
test "${CP_FILES_ENABLED:-}" = true
body=$(mktemp); log=$(mktemp); api_pid=
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    # A chave sintética nunca vai para a anotação.
    sed "s/${CP_STORAGE_SERVICE_KEY:-sem-chave}/[REDACTED]/g" "$log" > "$log.safe" 2>/dev/null || true
    bash tests/ci/annotate-tail.sh "$log.safe" "Files smoke" 60
  fi
  if [ -n "$api_pid" ]; then kill "$api_pid" 2>/dev/null || true; fi
  rm -f "$body" "$log" "$log.safe"
}
trap cleanup EXIT
CP_STORAGE_SERVICE_KEY="synthetic-$(openssl rand -hex 24)"
export CP_STORAGE_SERVICE_KEY
java -jar apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar > "$log" 2>&1 &
api_pid=$!
base=http://127.0.0.1:8082
ready=0
for attempt in $(seq 1 45); do
  if curl -fsS "$base/actuator/health/readiness" > "$body"; then ready=1; break; fi
  if ! kill -0 "$api_pid" 2>/dev/null; then exit 1; fi
  sleep 2
done
if [ "$ready" -ne 1 ]; then echo "Readiness: $(head -c 300 "$body")" >> "$log"; exit 1; fi
tenant=22222222-2222-4222-8222-222222222222
item=44444444-4444-4444-8444-444444444444
for spec in "GET /v1/tenants/$tenant/requests/$item/attachments" "POST /v1/tenants/$tenant/uploads/intents" \
  "POST /v1/tenants/$tenant/attachments/$item/complete" "POST /v1/tenants/$tenant/attachments/$item/download"; do
  set -- $spec
  status=$(curl -sS -X "$1" -H 'Content-Type: application/json' -d '{}' -o "$body" -w '%{http_code}' "$base$2")
  # GET sem sessão: 401. POST sem sessão nem CSRF: barrado pelo CSRF padrão do Spring Security antes da autenticação
  # (403 ACTION_FORBIDDEN), comportamento observado no CI 40955946990.
  if [ "$1" = GET ]; then expected=401; code=AUTHENTICATION_REQUIRED; else expected=403; code=ACTION_FORBIDDEN; fi
  if [ "$status" != "$expected" ]; then echo "Rota de anexo sem sessão respondeu $status: $2 $(head -c 300 "$body")" >> "$log"; exit 1; fi
  node -e "const p=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')); if(p.code!==process.argv[2]) process.exit(1)" "$body" "$code"
done
if grep -qF "$CP_STORAGE_SERVICE_KEY" "$log"; then echo 'Chave de serviço apareceu no log.' >&2; exit 1; fi
if grep -q -E 'APPLICATION FAILED TO START| ERROR [0-9]+ --- ' "$log"; then exit 1; fi
echo 'Arquivos ligados: API pronta e rotas de anexo protegidas.'
