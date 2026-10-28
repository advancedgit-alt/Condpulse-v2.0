#!/usr/bin/env bash
# Somente o PostgreSQL descartável do job CI; nunca recebe segredo remoto.
set -euo pipefail
test "${CP_DB_TEST_OK:-}" = DISPOSABLE
postgres_container=${1:?Container PostgreSQL descartavel obrigatorio}
[[ "$postgres_container" =~ ^[0-9a-f]{12,64}$ ]]
image=condpulse-api:ci
api_container=condpulse-api-image-ci
network=$(docker inspect --format '{{range $name, $settings := .NetworkSettings.Networks}}{{$name}}{{end}}' "$postgres_container")
postgres_ip=$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$postgres_container")
test -n "$network"
test -n "$postgres_ip"
trap 'docker rm -f "$api_container" >/dev/null 2>&1 || true' EXIT

# O probe deve falhar quando não há API, inclusive sem emitir corpo/credenciais.
if docker run --rm --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --cap-drop ALL --security-opt no-new-privileges --network none \
  --entrypoint java "$image" -Xmx16m -XX:+UseSerialGC -cp /app/health Healthcheck; then
  echo 'Probe aceitou API ausente' >&2
  exit 1
fi

# A MESMA imagem executa a migração como job finito, com identidade DDL isolada.
docker run --rm --no-healthcheck --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --cap-drop ALL --security-opt no-new-privileges --memory 768m --cpus 1 \
  --pids-limit 256 --network "$network" \
  -e SPRING_PROFILES_ACTIVE=migrate \
  -e "DATABASE_URL=jdbc:postgresql://$postgres_ip:5432/condpulse_ci" \
  -e DATABASE_USER=postgres -e DATABASE_PASSWORD=condpulse_ci_only -e DB_POOL_SIZE=2 \
  "$image"

docker run -d --name "$api_container" --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --cap-drop ALL --security-opt no-new-privileges --memory 768m --cpus 1 \
  --pids-limit 256 --network "$network" -p 127.0.0.1:4181:8080 \
  -e SPRING_PROFILES_ACTIVE=api \
  -e "DATABASE_URL=jdbc:postgresql://$postgres_ip:5432/condpulse_ci" \
  -e DATABASE_USER=cp_ci_login -e DATABASE_PASSWORD=synthetic_ci_runtime_only \
  -e DB_POOL_SIZE=8 -e CP_JWT_ISSUER=https://auth.example.invalid \
  -e CP_JWT_JWK_SET_URI=https://auth.example.invalid/jwks.json \
  -e CP_BFF_ENABLED=false -e CP_RESERVATIONS_ENABLED=false "$image"

ready=0
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:4181/actuator/health/readiness >/dev/null; then ready=1; break; fi
  if [ "$(docker inspect --format '{{.State.Running}}' "$api_container")" != true ]; then
    docker logs "$api_container"
    exit 1
  fi
  sleep 2
done
if [ "$ready" -ne 1 ]; then docker logs "$api_container"; exit 1; fi
test "$(docker inspect --format '{{.Config.User}}' "$api_container")" = '10001:10001'
test "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$api_container")" = true
docker exec "$api_container" java -Xmx16m -XX:+UseSerialGC -cp /app/health Healthcheck
status=$(curl -sS -o /tmp/condpulse-image-auth.json -w '%{http_code}' http://127.0.0.1:4181/v1/me)
test "$status" = 401
node -e "const p=JSON.parse(require('node:fs').readFileSync('/tmp/condpulse-image-auth.json','utf8'));if(p.code!=='AUTHENTICATION_REQUIRED')process.exit(1)"
echo 'Imagem API: migrador finito, runtime readonly sem root, readiness e401 verificados.'
