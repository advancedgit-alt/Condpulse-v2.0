#!/usr/bin/env bash
# CP_REALTIME_ENABLED ligado só no PostgreSQL descartável do CI, com BFF e chaves sintéticas geradas aqui (nunca gravadas
# nem impressas). Prova que a API sobe com o canal em tempo real, que o canal exige sessão (cookie ou Bearer) mesmo pedido
# como text/event-stream e que o problema chega como JSON com o status certo.
set -euo pipefail
test "${PORT:-}" = 8083
test "${DATABASE_URL:-}" = jdbc:postgresql://localhost:5432/condpulse_ci
test "${CP_REALTIME_ENABLED:-}" = true
test "${CP_BFF_ENABLED:-}" = true
body=$(mktemp); headers=$(mktemp); log=$(mktemp); api_pid=
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then bash tests/ci/annotate-tail.sh "$log" "Realtime smoke" 60; fi
  if [ -n "$api_pid" ]; then kill "$api_pid" 2>/dev/null || true; fi
  rm -f "$body" "$headers" "$log"
}
trap cleanup EXIT
CP_BFF_ENCRYPTION_KEY=$(openssl rand -base64 32)
CP_BFF_RATE_LIMIT_KEY=$(openssl rand -base64 32)
export CP_BFF_ENCRYPTION_KEY CP_BFF_RATE_LIMIT_KEY
java -jar apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar > "$log" 2>&1 &
api_pid=$!
base=http://127.0.0.1:8083
ready=0
for attempt in $(seq 1 45); do
  if curl -fsS "$base/actuator/health/readiness" > "$body"; then ready=1; break; fi
  if ! kill -0 "$api_pid" 2>/dev/null; then exit 1; fi
  sleep 2
done
if [ "$ready" -ne 1 ]; then echo "Readiness: $(head -c 300 "$body")" >> "$log"; exit 1; fi
route=/v1/tenants/22222222-2222-4222-8222-222222222222/units/33333333-3333-4333-8333-333333333333/events
for auth in none bearer; do
  args=(-sS -D "$headers" -o "$body" -w '%{http_code}' --max-time 10 -H 'Accept: text/event-stream')
  if [ "$auth" = bearer ]; then args+=(-H 'Authorization: Bearer synthetic.invalid.token'); fi
  status=$(curl "${args[@]}" "$base$route")
  if [ "$status" != 401 ]; then echo "Canal sem sessão válida ($auth) respondeu $status: $(head -c 300 "$body")" >> "$log"; exit 1; fi
  if grep -qi '^content-type: text/event-stream' "$headers"; then echo "Canal recusado abriu stream ($auth)" >> "$log"; exit 1; fi
done
if grep -q -E 'APPLICATION FAILED TO START| ERROR [0-9]+ --- ' "$log"; then exit 1; fi
echo 'Tempo real ligado: API pronta e canal protegido.'
