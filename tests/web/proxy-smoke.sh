#!/usr/bin/env bash
# Somente runner descartavel: fixture de protocolo sem Auth, dados ou tokens reais.
set -euo pipefail
network=condpulse-web-ci
image=condpulse-web:ci
web=condpulse-web-ci
first=condpulse-proxy-api-first-ci
replacement=condpulse-proxy-api-next-ci
fixture="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/api-proxy-fixture.conf"
temporary=$(mktemp -d /tmp/condpulse-proxy.XXXXXX)
cleanup() {
  docker rm -f "$first" "$replacement" >/dev/null 2>&1 || true
  # Arquivos conhecidos de um diretorio criado por este script; sem delete recursivo.
  rm -f -- "$temporary/headers" "$temporary/body"
  rmdir -- "$temporary"
}
trap cleanup EXIT
web_started=$(docker inspect --format '{{.State.StartedAt}}' "$web")

request() {
  curl --silent --show-error --max-time 2 --path-as-is \
    -D "$temporary/headers" -o "$temporary/body" -w '%{http_code}' \
    -H 'Authorization: Bearer ci-synthetic-token' \
    -H 'Cookie: __Host-condpulse=ci-synthetic-session' \
    -H 'X-CSRF-Token: ci-synthetic-csrf' \
    -H 'Idempotency-Key: ci-synthetic-key' \
    "$@"
}

# Iniciar a web sem API nao pode transformar /v1 em SPA ou sucesso aparente.
status=$(request 'http://127.0.0.1:4180/v1/me?probe=absent')
test "$status" = 502

start_fixture() {
  docker run -d --name "$1" --network "$network" --network-alias api \
    --read-only --tmpfs /tmp:rw,nosuid,nodev,noexec,size=64m \
    --cap-drop ALL --security-opt no-new-privileges --no-healthcheck \
    -v "$fixture:/etc/nginx/conf.d/default.conf:ro" "$image" >/dev/null
}

await_upstream() {
  local expected_ip=$1
  local deadline=$((SECONDS + 20))
  while [ "$SECONDS" -lt "$deadline" ]; do
    status=$(request 'http://127.0.0.1:4180/v1/me?probe=a%2Fb&word=encoded%20space') || status=000
    if [ "$status" = 401 ] && tr -d '\r' < "$temporary/headers" | grep -Fqx "X-CI-Upstream-IP: $expected_ip"; then
      return 0
    fi
    sleep 1
  done
  echo 'Proxy nao reconectou ao upstream esperado dentro do prazo' >&2
  return 1
}

assert_protocol() {
  node --input-type=module - "$temporary" <<'NODE'
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const dir = process.argv[2];
const lines = readFileSync(dir + '/headers', 'utf8').trim().split(/\r?\n/);
const headers = new Map(lines.slice(1).map(line => {
  const split = line.indexOf(':');
  return [line.slice(0, split).toLowerCase(), line.slice(split + 1).trim()];
}));
assert.equal(headers.get('x-ci-request-uri'), '/v1/me?probe=a%2Fb&word=encoded%20space');
assert.equal(headers.get('x-ci-method'), 'GET');
assert.equal(headers.get('x-ci-authorization'), 'Bearer ci-synthetic-token');
assert.equal(headers.get('x-ci-csrf'), 'ci-synthetic-csrf');
assert.equal(headers.get('x-ci-idempotency'), 'ci-synthetic-key');
assert.equal(headers.get('x-ci-cookie'), '__Host-condpulse=ci-synthetic-session');
assert.equal(headers.get('set-cookie'), '__Host-condpulse=ci_fixture; Path=/; Secure; HttpOnly; SameSite=Lax');
assert.equal(headers.get('cache-control'), 'no-store');
assert.ok(headers.has('content-security-policy'));
assert.equal(JSON.parse(readFileSync(dir + '/body', 'utf8')).code, 'AUTHENTICATION_REQUIRED');
NODE
}

start_fixture "$first"
first_ip=$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$first")
await_upstream "$first_ip"
assert_protocol

# O alias auth tambem preserva metodo/URI e status de falha do servidor.
status=$(request -X POST -H 'Content-Type: application/json' --data '{}' 'http://127.0.0.1:4180/auth/login?probe=a%2Fb')
test "$status" = 400
tr -d '\r' < "$temporary/headers" | grep -Fqx 'X-CI-Request-URI: /auth/login?probe=a%2Fb'
tr -d '\r' < "$temporary/headers" | grep -Fqx 'X-CI-Method: POST'

# Alocar o substituto enquanto o IP antigo ainda esta ocupado torna a troca deterministica.
start_fixture "$replacement"
replacement_ip=$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$replacement")
test -n "$first_ip"
test -n "$replacement_ip"
test "$first_ip" != "$replacement_ip"
docker rm -f "$first" >/dev/null
await_upstream "$replacement_ip"
assert_protocol
test "$(docker inspect --format '{{.State.StartedAt}}' "$web")" = "$web_started"
echo 'Proxy: falha502, protocolo401/400 e novoIP sem reinicio web verificados.'
