#!/usr/bin/env bash
# Sem banco nem rede: prova que o launcher real da imagem do worker na Fly falha fechado e não abre porta.
set -euo pipefail
test "${CP_DB_TEST_OK:-}" = DISPOSABLE
image=${1:-condpulse-fly-worker:ci}
[[ "$image" =~ ^condpulse-fly-worker:ci$ ]] || exit 1
jdbc='jdbc:postgresql://aws-1-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslrootcert=/tmp/condpulse-db-ca.crt'
common=(--rm --network none --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m --cap-drop ALL --security-opt no-new-privileges
  -e "DATABASE_URL=$jdbc" -e DATABASE_USER=cp_worker_11111111111111111111111111111111.rpnrqfrfwywglvyykgrg
  -e DATABASE_PASSWORD=SYNTHETIC_TEST_ONLY_password -e DB_POOL_SIZE=1 -e CP_WORKER_OUTBOX_ENABLED=false
  -e CP_WORKER_WAITLIST_ENABLED=false -e CP_WORKER_ACCESS_SYNC_ENABLED=false -e CP_WORKER_POLL_MILLIS=2000
  -e CP_WORKER_WAITLIST_MILLIS=60000 -e CP_WORKER_STAYS_ENABLED=false -e CP_WORKER_STAYS_MILLIS=60000 -e CP_DB_CA_BASE64=SYNTHETIC_PUBLIC_CA_ONLY)

test "$(docker image inspect --format '{{json .Config.ExposedPorts}}' "$image")" = null
test "$(docker image inspect --format '{{.Config.User}}' "$image")" = 10001:10001

reject() {
  local expected=$1
  shift
  local output
  if output=$(docker run "${common[@]}" "$@" "$image" 2>&1); then
    echo 'Fly worker launcher accepted an invalid configuration' >&2; exit 1
  fi
  test "$output" = "$expected"
}
reject FLY_WORKER_PROFILE_EXACT -e SPRING_PROFILES_ACTIVE=api
reject FLY_WORKER_DATABASE_TARGET_EXACT -e "DATABASE_URL=${jdbc/verify-full/disable}"
reject FLY_WORKER_IDENTITY_REQUIRED -e DATABASE_USER=cp_runtime_11111111111111111111111111111111.rpnrqfrfwywglvyykgrg
reject FLY_WORKER_POOL_LIMIT -e DB_POOL_SIZE=4
reject FLY_WORKER_ACCESS_SYNC_FORBIDDEN -e CP_WORKER_ACCESS_SYNC_ENABLED=true
reject FLY_WORKER_PRIVILEGED_ENV_FORBIDDEN -e CP_BFF_ENCRYPTION_KEY=SYNTHETIC_SENTINEL
reject FLY_WORKER_CONFIGURATION_OVERRIDE_FORBIDDEN -e SPRING_DATASOURCE_USERNAME=postgres
# Configuração válida até a CA sintética inválida: nenhum Spring, banco ou rede é tocado.
reject FLY_WORKER_CA_ENCODING_INVALID
echo 'Fly worker launcher: profile/JDBC/identity/pool/flags/CA guards verified without network.'
