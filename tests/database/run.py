"""Testes de integridade CondPulse, exclusivamente em PostgreSQL descartável."""
import concurrent.futures
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
TA = "00000000-0000-4000-8000-000000000001"
TB = "00000000-0000-4000-8000-000000000002"
AA = "10000000-0000-4000-8000-000000000001"
AB = "10000000-0000-4000-8000-000000000002"
UA = "20000000-0000-4000-8000-000000000001"
UB = "20000000-0000-4000-8000-000000000002"
RA = "30000000-0000-4000-8000-000000000001"
RB = "30000000-0000-4000-8000-000000000002"
PA = "40000000-0000-4000-8000-000000000001"
ARR = "50000000-0000-4000-8000-000000000001"
ROLL = "60000000-0000-4000-8000-000000000001"
checks = 0

def guard():
    db = os.environ.get("PGDATABASE", "")
    host = os.environ.get("PGHOST", "")
    if os.environ.get("CP_DB_TEST_OK") != "DISPOSABLE":
        raise RuntimeError("Confirmação de banco descartável ausente.")
    if db != "condpulse_ci" and not db.startswith("cp_test_"):
        raise RuntimeError("Nome de banco não permitido para fixtures.")
    if host not in ("localhost", "127.0.0.1", "postgres"):
        raise RuntimeError("Host não permitido para teste descartável.")

def query(statement, *, tenant=None, actor=AA, role=None, check=True):
    prefix = suffix = ""
    if role:
        prefix = f"BEGIN; SET LOCAL ROLE {role}; "
        prefix += f"SET LOCAL app.actor_id='{actor}'; "
        if tenant:
            prefix += f"SET LOCAL app.tenant_id='{tenant}'; "
        suffix = "; COMMIT;"
    result = subprocess.run(
        ["psql", "-X", "-q", "-A", "-t", "-v", "ON_ERROR_STOP=1",
         "-v", "VERBOSITY=verbose", "-c", prefix + statement + suffix],
        text=True, capture_output=True, timeout=30,
    )
    if check and result.returncode:
        raise AssertionError(result.stderr)
    return result

def value(statement, **kwargs):
    return query(statement, **kwargs).stdout.strip().splitlines()[-1]

def expect(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1
    print(f"PASS {checks}: {label}", flush=True)

def error(statement, state, **kwargs):
    result = query(statement, check=False, **kwargs)
    return result.returncode != 0 and state in result.stderr

def reservation(tenant=TA, area=RA, unit=UA, actor=AA,
                start="12:00", end="13:00", occupied_end=None, identifier=None):
    columns = "tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot"
    vals = f"'{tenant}','{area}','{unit}','{actor}','BOOKING','CONFIRMED',"
    vals += f"'2030-01-01 {start}+00','2030-01-01 {end}+00',"
    vals += f"'2030-01-01 {start}+00','2030-01-01 {occupied_end or end}+00','{{}}'::jsonb"
    if identifier:
        columns += ",id"
        vals += f",'{identifier}'"
    return f"INSERT INTO cp.reservations({columns}) VALUES({vals}) RETURNING id"

def main():
    guard()
    if os.environ.get("CP_SCHEMA_ALREADY_MIGRATED") != "true":
        for migration in sorted((ROOT / "db/migrations").glob("V*.sql")):
            subprocess.run(["psql", "-X", "-v", "ON_ERROR_STOP=1", "-f", str(migration)], check=True)
    else:
        expect(value("select count(*) from platform.flyway_schema_history where success and version is not null") == "2",
               "Flyway executou as duas migrations de fundação")
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{AA}','ACTIVE'),('{AB}','ACTIVE');
        INSERT INTO platform.tenants(id,name,active) VALUES('{TA}','FICTICIO A',true),('{TB}','FICTICIO B',true);
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UA}','{TA}','FICTICIA 101'),('{UB}','{TB}','FICTICIA 202');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{AA}','{UA}','PESSOA FICTICIA A','RESIDENT','ACTIVE'),
                ('{TB}','{AB}','{UB}','PESSOA FICTICIA B','RESIDENT','ACTIVE');
        INSERT INTO cp.amenities(id,tenant_id,name,capacity,approval_required)
          VALUES('{RA}','{TA}','AREA FICTICIA A',10,false),('{RB}','{TB}','AREA FICTICIA B',10,false);
        INSERT INTO cp.parcels(id,tenant_id,unit_id,quantity,description,storage_location,received_by)
          VALUES('{PA}','{TA}','{UA}',1,'ENCOMENDA FICTICIA','ARMARIO DE TESTE','{AA}');
        INSERT INTO cp.arrival_requests(id,tenant_id,unit_id,expires_at)
          VALUES('{ARR}','{TA}','{UA}',now()+interval '5 minutes');
    """)
    expect(value("select count(*) from pg_roles where rolname in ('cp_api','cp_worker') and (rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication or rolcanlogin)") == "0",
           "grupos runtime sem login e sem privilegio elevado")
    expect(value("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='cp' and c.relkind='r' and (not c.relrowsecurity or not c.relforcerowsecurity)") == "0",
           "todas as tabelas de negocio usam ENABLE/FORCE RLS")
    expect(value("select count(*) from cp.units", role="cp_api") == "0", "sem contexto nao ha dados de unidade")
    expect(value("select count(*) from cp.units", tenant=TA, role="cp_api") == "1", "tenant A ve somente sua unidade")
    expect(value(f"select count(*) from cp.units where id='{UB}'", tenant=TA, role="cp_api") == "0",
           "UUID de outra unidade/tenant nao atravessa RLS")
    expect(value(f"select count(*) from platform.accounts where id='{AB}'", role="cp_api") == "0",
           "resolucao de conta mostra somente o proprio ator")
    expect(value("select count(*) from platform.tenants", role="cp_api") == "1", "catalogo usa vinculo vigente")
    leaked = query(f"SET ROLE cp_api; BEGIN; SELECT set_config('app.tenant_id','{TA}',true); SELECT count(*) FROM cp.units; COMMIT; SELECT count(*) FROM cp.units").stdout.strip().splitlines()
    expect(leaked[-2:] == ["1", "0"], "contexto SET LOCAL nao sobrevive ao commit na mesma conexao")
    expect(error(reservation(unit=UB), "23503", tenant=TA, role="cp_api"), "FK composta barra unidade de outro tenant")
    expect(error(reservation(area=RB), "23503", tenant=TA, role="cp_api"), "FK composta barra area de outro tenant")

    def book(_):
        return query(reservation(), tenant=TA, role="cp_api", check=False)
    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as pool:
        results = list(pool.map(book, range(50)))
    expect(sum(r.returncode == 0 for r in results) == 1, "50 reservas simultaneas produzem somente uma vencedora")
    expect(all(r.returncode == 0 or "23P01" in r.stderr for r in results), "perdedoras recebem conflito de exclusao")
    expect(bool(query(reservation(start="13:00",end="14:00"),tenant=TA,role="cp_api").stdout.strip()),
           "intervalos adjacentes sem buffer sao permitidos")
    query(reservation(start="14:00",end="15:00",occupied_end="15:30"),tenant=TA,role="cp_api")
    expect(error(reservation(start="15:00",end="16:00"),"23P01",tenant=TA,role="cp_api"),
           "buffer de limpeza participa da exclusao")
    expect(bool(query(reservation(tenant=TB,area=RB,unit=UB,actor=AB),tenant=TB,actor=AB,role="cp_api").stdout.strip()),
           "mesmo horario em outro tenant permanece independente")
    query(f"update cp.reservations set status='CANCELLED',version=version+1 where starts_at='2030-01-01 12:00+00'",tenant=TA,role="cp_api")
    expect(bool(query(reservation(),tenant=TA,role="cp_api").stdout.strip()),"cancelamento libera intervalo")

    pickup = f"""
        WITH claimed AS(
          UPDATE cp.parcels SET status='DELIVERED',version=version+1
          WHERE id='{PA}' AND status='RECEIVED' RETURNING id
        )
        INSERT INTO cp.parcel_handoffs(tenant_id,parcel_id,operator_id,recipient_name,evidence_kind)
        SELECT '{TA}',id,'{AA}','RECEBEDOR FICTICIO','ASSISTED' FROM claimed RETURNING id
    """
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(lambda _: query(pickup,tenant=TA,role="cp_api"), range(20)))
    expect(sum(bool(r.stdout.strip()) for r in results) == 1, "20 retiradas concorrentes geram somente um handoff")
    duplicate = f"insert into cp.parcel_handoffs(tenant_id,parcel_id,operator_id,recipient_name,evidence_kind) values('{TA}','{PA}','{AA}','FICTICIO','ASSISTED')"
    expect(error(duplicate,"23505",tenant=TA,role="cp_api"), "UNIQUE impede segunda evidencia de retirada")

    def decide(state):
        return query(f"update cp.arrival_requests set state='{state}',decided_by='{AA}',decided_at=now(),version=version+1 where id='{ARR}' and state='WAITING' and version=0 and expires_at>now() returning state",tenant=TA,role="cp_api")
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        decisions = list(pool.map(decide,["APPROVED","DENIED"]))
    expect(sum(bool(r.stdout.strip()) for r in decisions) == 1,"somente a primeira decisao de chegada e aceita")
    audit = f"insert into cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) values('{TA}','{AA}','TEST','fixture','{PA}','{ROLL}')"
    query(audit,tenant=TA,role="cp_api")
    expect(error("update cp.audit_log set action='ALTERED'","42501",tenant=TA,role="cp_api"), "aplicacao nao altera auditoria")
    expect(error("delete from cp.audit_log","42501",tenant=TA,role="cp_api"), "aplicacao nao apaga auditoria")
    query(f"insert into cp.idempotency_keys(tenant_id,actor_id,request_key,method,route,body_hash,expires_at) values('{TA}','{AA}','synthetic-test-key-0001','POST','/fixture',repeat('a',64),now()+interval '24 hours')",tenant=TA,role="cp_api")
    expect(value("select count(*) from cp.idempotency_keys",tenant=TA,actor=AB,role="cp_api")=="0",
           "resposta idempotente nao atravessa ator no mesmo tenant")
    rollback = reservation(start="18:00",end="19:00",identifier=ROLL).replace(" RETURNING id","")
    query(f"BEGIN; SET LOCAL ROLE cp_api; SET LOCAL app.tenant_id='{TA}'; SET LOCAL app.actor_id='{AA}'; {rollback}; INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES('{TA}','TEST','{ROLL}',0,'{{}}'); ROLLBACK")
    expect(value(f"select (select count(*) from cp.reservations where id='{ROLL}')+(select count(*) from cp.outbox where aggregate_id='{ROLL}')")=="0",
           "rollback desfaz reserva e outbox juntas")
    query(f"update cp.memberships set status='REVOKED' where account_id='{AA}'")
    expect(value("select count(*) from platform.tenants",role="cp_api")=="0","vinculo revogado remove tenant do catalogo")
    print(f"PASS: {checks} verificacoes PostgreSQL; nao constitui aceite de produto.", flush=True)

if __name__ == "__main__":
    main()
