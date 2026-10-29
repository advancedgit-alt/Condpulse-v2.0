#!/usr/bin/env bash
# Perfil worker real (jar) contra o PostgreSQL descartável do job, com o login sintético cp_ci_worker: sobe sem porta
# HTTP, passa pelo DatabaseRoleGuard, arrenda e conclui uma tarefa sem consumidor (SKIPPED, nunca "entregue") e uma
# encomenda fictícia recebida (DELIVERED, com um aviso AVAILABLE na caixa do morador, sem marcar leitura) e encerra.
# Condomínio, unidade, conta, encomenda e tarefas fictícios criados e removidos aqui; nada remoto e nenhum segredo real.
set -euo pipefail
test "${CP_DB_TEST_OK:-}" = DISPOSABLE
test "${PGHOST:-}" = localhost
test "${PGDATABASE:-}" = condpulse_ci
jar=apps/backend/target/condpulse-backend-0.1.0-SNAPSHOT.jar
log="${RUNNER_TEMP:-/tmp}/condpulse-worker.log"
uuid() { python3 -I -c 'import uuid; print(uuid.uuid4())'; }
tenant=$(uuid); event=$(uuid); parcel_event=$(uuid); unit=$(uuid); resident=$(uuid); parcel=$(uuid)
psql -X -q -v ON_ERROR_STOP=1 <<SQL
begin;
insert into platform.tenants(id,name,active) values('$tenant','FICTICIO WORKER SMOKE',true);
insert into platform.accounts(id,status) values('$resident','ACTIVE');
insert into cp.units(id,tenant_id,label) values('$unit','$tenant','FICTICIA SMOKE');
insert into cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
 values('$tenant','$resident','$unit','MORADOR FICTICIO SMOKE','RESIDENT','ACTIVE');
insert into cp.parcels(id,tenant_id,unit_id,quantity,description,storage_location,received_by)
 values('$parcel','$tenant','$unit',1,'CAIXA FICTICIA SMOKE','PORTARIA FICTICIA','$resident');
insert into cp.outbox(id,tenant_id,event_type,aggregate_id,aggregate_version,payload) values
 ('$event','$tenant','SMOKE_WITHOUT_CONSUMER',gen_random_uuid(),0,'{}'),
 ('$parcel_event','$tenant','PARCEL_RECEIVED','$parcel',0,jsonb_build_object('parcelId','$parcel','unitId','$unit'));
commit;
SQL

SPRING_PROFILES_ACTIVE=worker DATABASE_URL=jdbc:postgresql://localhost:5432/condpulse_ci \
  DATABASE_USER=cp_ci_worker DATABASE_PASSWORD=synthetic_ci_worker_only DB_POOL_SIZE=2 \
  CP_WORKER_OUTBOX_ENABLED=true CP_WORKER_POLL_MILLIS=500 java -jar "$jar" > "$log" 2>&1 &
pid=$!
cleanup() {
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  psql -X -q -c "delete from cp.notifications where tenant_id='$tenant'" -c "delete from cp.outbox where tenant_id='$tenant'" \
    -c "delete from cp.parcels where tenant_id='$tenant'" -c "delete from cp.memberships where tenant_id='$tenant'" \
    -c "delete from cp.units where tenant_id='$tenant'" -c "delete from platform.tenants where id='$tenant'" \
    -c "delete from platform.accounts where id='$resident'" >/dev/null || true
}
trap cleanup EXIT

expected="SKIPPED:1:true DELIVERED:1:true"
state=""
for _ in $(seq 1 90); do
  state=$(psql -X -At -c "select string_agg(state||':'||attempts||':'||(processed_at is not null),' ' order by id='$parcel_event')
    from cp.outbox where id in ('$event','$parcel_event')")
  [ "$state" = "$expected" ] && break
  if ! kill -0 "$pid" 2>/dev/null; then tail -n 80 "$log"; echo "Worker encerrou antes de processar ($state)" >&2; exit 1; fi
  sleep 1
done
if [ "$state" != "$expected" ]; then tail -n 80 "$log"; echo "Worker nao concluiu as tarefas sinteticas ($state)" >&2; exit 1; fi
notice=$(psql -X -At -c "select count(*)||':'||string_agg(kind||':'||state||':'||(read_at is null)||':'||(account_id='$resident'),',')
  from cp.notifications where tenant_id='$tenant' and outbox_id='$parcel_event'")
if [ "$notice" != "1:PARCEL_RECEIVED:AVAILABLE:true:true" ]; then echo "Aviso da encomenda inesperado ($notice)" >&2; exit 1; fi
# Perfil worker sem servidor HTTP: nenhuma porta pública.
if curl -fsS --max-time 2 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then echo "Perfil worker expôs HTTP" >&2; exit 1; fi
# Log saneado: sem senha sintética nem payload.
if grep -q "synthetic_ci_worker_only" "$log"; then echo "Log do worker contém credencial" >&2; exit 1; fi
echo "Worker processou as tarefas sinteticas: $state; aviso $notice"
