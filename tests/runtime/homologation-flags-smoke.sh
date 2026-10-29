#!/usr/bin/env bash
# Mesmo conjunto de flags da Vercel Production desde 29/10/2026 (recuperação por e-mail e worker
# continuam desligados). Somente o PostgreSQL descartável do CI; chaves BFF sintéticas geradas
# nesta execução, nunca gravadas nem impressas. Prova que a API sobe com todos os módulos juntos,
# que o BFF responde ao CSRF e que as rotas dos módulos continuam exigindo sessão.
set -euo pipefail
test "${PORT:-}" = 8081
test "${DATABASE_URL:-}" = jdbc:postgresql://localhost:5432/condpulse_ci
test "${DATABASE_USER:-}" = cp_ci_login
for flag in CP_BFF_ENABLED CP_RESERVATIONS_ENABLED CP_MANAGEMENT_ENABLED CP_PLATFORM_ENABLED \
  CP_PLATFORM_BILLING_ENABLED CP_PARCELS_ENABLED CP_VISITS_ENABLED CP_NOTIFICATIONS_ENABLED; do
  test "${!flag:-}" = true
done
test -z "${CP_BFF_RECOVERY_ENABLED:-}${CP_WORKER_OUTBOX_ENABLED:-}"

body=$(mktemp)
headers=$(mktemp)
log=$(mktemp)
api_pid=
cleanup() {
  if [ -n "$api_pid" ]; then kill "$api_pid" 2>/dev/null || true; fi
  rm -f "$body" "$headers" "$log"
}
trap cleanup EXIT
CP_BFF_ENCRYPTION_KEY=$(openssl rand -base64 32)
CP_BFF_RATE_LIMIT_KEY=$(openssl rand -base64 32)
test "$CP_BFF_ENCRYPTION_KEY" != "$CP_BFF_RATE_LIMIT_KEY"
export CP_BFF_ENCRYPTION_KEY CP_BFF_RATE_LIMIT_KEY

java -jar apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar > "$log" 2>&1 &
api_pid=$!
base=http://127.0.0.1:8081
ready=0
for attempt in $(seq 1 45); do
  if curl -fsS "$base/actuator/health/readiness" > "$body"; then ready=1; break; fi
  if ! kill -0 "$api_pid" 2>/dev/null; then cat "$log"; exit 1; fi
  sleep 2
done
if [ "$ready" -ne 1 ]; then cat "$log"; exit 1; fi
node -e "const p=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')); if(p.status!=='UP') process.exit(1)" "$body"

# BFF ligado: o CSRF existe (com o BFF desligado a rota não tem controlador).
status=$(curl -sS -D "$headers" -o "$body" -w '%{http_code}' "$base/auth/csrf")
test "$status" = 200
grep -qi '^set-cookie: __Host-cp_csrf=' "$headers"
node -e "const p=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')); if(p.headerName!=='X-CSRF-Token'||typeof p.token!=='string'||p.token.length<16) process.exit(1)" "$body"

# Sem sessão, toda rota de módulo ligado responde 401 com o problema padrão.
tenant=22222222-2222-4222-8222-222222222222
unit=33333333-3333-4333-8333-333333333333
for route in /v1/me "/v1/tenants/$tenant/reservations" "/v1/tenants/$tenant/management/units" \
  "/v1/tenants/$tenant/parcels" "/v1/tenants/$tenant/arrivals" "/v1/tenants/$tenant/access-events" \
  "/v1/tenants/$tenant/units/$unit/notifications" /v1/platform/tenants /v1/platform/billing/plans; do
  status=$(curl -sS -o "$body" -w '%{http_code}' "$base$route")
  if [ "$status" != 401 ]; then echo "Rota sem sessão respondeu $status: $route" >&2; exit 1; fi
  node -e "const p=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')); if(p.code!=='AUTHENTICATION_REQUIRED') process.exit(1)" "$body"
done
if grep -q -E 'APPLICATION FAILED TO START| ERROR [0-9]+ --- ' "$log"; then cat "$log"; exit 1; fi
echo 'Flags da homologação: API pronta, CSRF do BFF ativo e rotas dos módulos protegidas.'
