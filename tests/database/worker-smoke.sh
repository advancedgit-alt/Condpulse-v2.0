#!/usr/bin/env bash
# Perfil worker real (jar) contra o PostgreSQL descartável do job, com o login sintético cp_ci_worker: sobe sem porta
# HTTP, passa pelo DatabaseRoleGuard, arrenda e conclui uma tarefa sem consumidor (SKIPPED, nunca "entregue") e encerra.
# Condomínio e tarefa fictícios criados e removidos aqui; nada remoto e nenhum segredo real.
set -euo pipefail
test "${CP_DB_TEST_OK:-}" = DISPOSABLE
test "${PGHOST:-}" = localhost
test "${PGDATABASE:-}" = condpulse_ci
jar=apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar
log="${RUNNER_TEMP:-/tmp}/condpulse-worker.log"
tenant=$(python3 -I -c 'import uuid; print(uuid.uuid4())')
event=$(python3 -I -c 'import uuid; print(uuid.uuid4())')
psql -X -q -v ON_ERROR_STOP=1 -c "insert into platform.tenants(id,name,active) values('$tenant','FICTICIO WORKER SMOKE',true)"
psql -X -q -v ON_ERROR_STOP=1 -c "insert into cp.outbox(id,tenant_id,event_type,aggregate_id,aggregate_version,payload) values('$event','$tenant','SMOKE_WITHOUT_CONSUMER',gen_random_uuid(),0,'{}')"

SPRING_PROFILES_ACTIVE=worker DATABASE_URL=jdbc:postgresql://localhost:5432/condpulse_ci \
  DATABASE_USER=cp_ci_worker DATABASE_PASSWORD=synthetic_ci_worker_only DB_POOL_SIZE=2 \
  CP_WORKER_OUTBOX_ENABLED=true CP_WORKER_POLL_MILLIS=500 java -jar "$jar" > "$log" 2>&1 &
pid=$!
cleanup() {
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  psql -X -q -c "delete from cp.outbox where tenant_id='$tenant'" -c "delete from platform.tenants where id='$tenant'" >/dev/null || true
}
trap cleanup EXIT

state=""
for _ in $(seq 1 90); do
  state=$(psql -X -At -c "select state||':'||attempts||':'||(processed_at is not null) from cp.outbox where id='$event'")
  [ "$state" = "SKIPPED:1:true" ] && break
  if ! kill -0 "$pid" 2>/dev/null; then tail -n 80 "$log"; echo "Worker encerrou antes de processar ($state)" >&2; exit 1; fi
  sleep 1
done
if [ "$state" != "SKIPPED:1:true" ]; then tail -n 80 "$log"; echo "Worker nao concluiu a tarefa sintetica ($state)" >&2; exit 1; fi
# Perfil worker sem servidor HTTP: nenhuma porta pública.
if curl -fsS --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then echo "Perfil worker expôs HTTP" >&2; exit 1; fi
# Log saneado: sem senha sintética nem payload.
if grep -q "synthetic_ci_worker_only" "$log"; then echo "Log do worker contém credencial" >&2; exit 1; fi
echo "Worker processou a tarefa sintetica: $state"
