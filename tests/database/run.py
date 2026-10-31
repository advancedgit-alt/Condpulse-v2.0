"""Testes de integridade CondPulse, exclusivamente em PostgreSQL descartável."""
import concurrent.futures
import hashlib
import hmac
import time
import os
from pathlib import Path
import re
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
VA = "70000000-0000-4000-8000-000000000001"
ROLL = "60000000-0000-4000-8000-000000000001"
RQ = "30000000-0000-4000-8000-000000000003"
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
        if actor:
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

def reservation_error_state(result):
    # SQL errors from psql -c / ON_ERROR_STOP have exit 1 or 3. Killed processes,
    # connection failures (2) and missing/ambiguous SQLSTATEs remain unclassified.
    if result.returncode not in (1, 3):
        return None
    states = re.findall(r"(?m)^(ERROR|FATAL|PANIC):[ \t]+([0-9A-Z]{5}):", result.stderr)
    return states[0][1] if len(states) == 1 and states[0][0] == "ERROR" else None


def reconcile_reservation_race(results, retry_aborted):
    """Recheck only proven GiST deadlock aborts after the whole wave finished.

    This is a direct-SQL test harness, not application retry policy. PostgreSQL
    may abort competing exclusion-index inserts with 40P01 before a winner
    commits. Once all 50 psql processes ended and one committed, each such
    aborted insert must observe 23P01, without retrying uncertain outcomes.
    """
    completed = list(results)
    if len(completed) != 50 or sum(r.returncode == 0 for r in completed) != 1:
        raise AssertionError("A onda deve encerrar 50 transacoes e exatamente um commit.")
    deadlocks = []
    # Validate every first-wave result before making any sequential recheck.
    for index, result in enumerate(completed):
        if result.returncode == 0:
            continue
        state = reservation_error_state(result)
        if state not in ("23P01", "40P01"):
            raise AssertionError(f"Perdedora {index} sem conflito/abort conhecido: {state}.")
        if state == "40P01":
            deadlocks.append(index)
    for index in deadlocks:
        result = retry_aborted(index)
        if reservation_error_state(result) != "23P01":
            raise AssertionError(f"Deadlock abortado {index} nao confirmou conflito apos a onda.")
        completed[index] = result
    return completed


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

def capable(capabilities, statement):
    return f"SET LOCAL app.capabilities='{capabilities}'; {statement}"

def manage_checks():
    """V006: escrita de gestao exige capacidade publicada no contexto local."""
    unit = f"INSERT INTO cp.units(tenant_id,label) VALUES('{TA}','FICTICIA GESTAO')"
    expect(error(unit, "42501", tenant=TA, role="cp_api"), "unidade nao e criada sem capacidade de gestao")
    expect(error(capable("amenities.manage", unit), "42501", tenant=TA, role="cp_api"),
           "capacidade de areas nao cria unidade")
    expect(error(capable("units.manage,units.manager", unit.replace(f"'{TA}'", f"'{TB}'")), "42501", tenant=TA, role="cp_api"),
           "capacidade nao atravessa tenant no WITH CHECK")
    query(capable("units.manage", unit), tenant=TA, role="cp_api")
    expect(value(f"select count(*) from cp.units where tenant_id='{TA}' and label='FICTICIA GESTAO'") == "1",
           "gestor com capacidade cria unidade no proprio tenant")
    rename = f"WITH u AS (UPDATE cp.units SET label='FICTICIA RENOMEADA',version=version+1 WHERE id='{UA}' RETURNING id) SELECT count(*) FROM u"
    expect(error(rename, "42501", tenant=TA, role="cp_api"), "sem capacidade a atualizacao da unidade e recusada (42501)")
    expect(error(capable("amenities.manage", rename), "42501", tenant=TA, role="cp_api"),
           "capacidade de areas nao atualiza unidade")
    expect(value(capable("units.manage", rename), tenant=TA, role="cp_api") == "1", "com capacidade a unidade e atualizada")
    block = f"INSERT INTO cp.blocks(tenant_id,name) VALUES('{TA}','BLOCO FICTICIO')"
    expect(error(block, "42501", tenant=TA, role="cp_api"), "bloco nao e criado sem capacidade")
    expect(error(capable("amenities.manage", block), "42501", tenant=TA, role="cp_api"), "capacidade de areas nao cria bloco")
    query(capable("units.manage", block), tenant=TA, role="cp_api")
    expect(error(capable("units.manage", "UPDATE cp.blocks SET name='BLOCO RENOMEADO'"), "42501", tenant=TA, role="cp_api"),
           "bloco nao possui grant de alteracao")
    new_area = f"INSERT INTO cp.amenities(tenant_id,name,capacity) VALUES('{TA}','AREA FICTICIA GESTAO',5)"
    expect(error(new_area, "42501", tenant=TA, role="cp_api"), "area nao e criada sem capacidade")
    expect(error(capable("units.manage", new_area), "42501", tenant=TA, role="cp_api"), "capacidade de unidades nao cria area")
    resize = f"UPDATE cp.amenities SET capacity=capacity+1,version=version+1 WHERE id='{RA}'"
    expect(error(resize, "42501", tenant=TA, role="cp_api"), "area nao e alterada sem capacidade")
    expect(error(capable("units.manage", resize), "42501", tenant=TA, role="cp_api"), "capacidade de unidades nao altera area")
    query(capable("amenities.manage", resize + "; UPDATE cp.amenities SET capacity=capacity-1,version=version+1 WHERE id='" + RA + "'"),
          tenant=TA, role="cp_api")
    query(f"UPDATE cp.units SET label='FICTICIA 101' WHERE id='{UA}'")
    expect(error(f"DELETE FROM cp.units WHERE label='FICTICIA GESTAO'", "42501", tenant=TA, role="cp_api"),
           "aplicacao nao apaga unidades")
    expect(value("select platform.has_capability('units.manage')", tenant=TA, role="cp_api") == "f",
           "contexto sem capacidade nega a funcao")
    expect(value(capable("units.manage.extra,units", "select platform.has_capability('units.manage')"), tenant=TA, role="cp_api") == "f",
           "capacidade exige correspondencia exata")
    area = f"INSERT INTO cp.amenities(tenant_id,name,capacity) VALUES('{TA}','  area ficticia a  ',5)"
    expect(error(capable("amenities.manage", area), "23505", tenant=TA, role="cp_api"),
           "nome de area e unico por tenant sem diferenciar caixa e espacos")
    expect(value("select count(*) from cp.audit_log", tenant=TA, role="cp_api") == "0",
           "auditoria nao e lida sem capacidade")
    expect(int(value(capable("audit.read", "select count(*) from cp.audit_log"), tenant=TA, role="cp_api")) >= 1,
           "auditoria e lida somente com capacidade e dentro do tenant")
    expect(value(capable("audit.read", "select count(*) from cp.audit_log"), tenant=TB, actor=AB, role="cp_api") == "0",
           "auditoria de outro tenant permanece invisivel")

INV = "70000000-0000-4000-8000-000000000001"
INV_EXPIRED = "70000000-0000-4000-8000-000000000002"
NEWCOMER = "10000000-0000-4000-8000-000000000003"
TOKEN = "a" * 64
EXPIRED_TOKEN = "b" * 64
INV_SHARED = "70000000-0000-4000-8000-000000000004"
SHARED_TOKEN = "f" * 64
NEWCOMER_2 = "10000000-0000-4000-8000-000000000004"
NEWCOMER_3 = "10000000-0000-4000-8000-000000000005"

def with_invitation(token, statement):
    return f"SET LOCAL app.invitation_hash='{token}'; {statement}"

def invitation_checks():
    """V007: convite individual e vinculo pendente criado somente pelo convidado."""
    invite = (f"INSERT INTO cp.membership_invitations(id,tenant_id,unit_id,role,display_name,email_hash,token_hash,expires_at,created_by) "
              f"VALUES('{INV}','{TA}','{UA}','RESIDENT','CONVIDADO FICTICIO','{'c'*64}','{TOKEN}',now()+interval '7 days','{AA}')")
    expect(error(invite, "42501", tenant=TA, role="cp_api"), "convite nao e criado sem capacidade de vinculos")
    query(capable("memberships.manage", invite), tenant=TA, role="cp_api")
    expired = (f"INSERT INTO cp.membership_invitations(id,tenant_id,unit_id,role,display_name,email_hash,token_hash,expires_at,created_by,created_at) "
               f"VALUES('{INV_EXPIRED}','{TA}','{UA}','RESIDENT','CONVIDADO VENCIDO','{'d'*64}','{EXPIRED_TOKEN}',now()-interval '1 day','{AA}',now()-interval '2 days')")
    query(expired)
    expect(error(capable("memberships.manage", invite.replace(INV, "70000000-0000-4000-8000-000000000003").replace("'MANAGER'", "x").replace("'RESIDENT'", "'MANAGER'").replace(TOKEN, "e"*64)),
                 "23514", tenant=TA, role="cp_api"), "convite nao concede papel de gestor")
    expect(value("select count(*) from cp.membership_invitations", tenant=TA, role="cp_api") == "0",
           "convites nao sao listados sem capacidade")
    expect(value("select count(*) from cp.membership_invitations", tenant=None, actor=NEWCOMER, role="cp_api") == "0",
           "convidado sem token nao enumera convites")
    expect(value(with_invitation(TOKEN, "select count(*) from cp.membership_invitations"), actor=NEWCOMER, role="cp_api") == "1",
           "token apresentado revela somente o proprio convite")
    expect(error(f"INSERT INTO platform.accounts(id,status) VALUES('{AB}','ACTIVE')", "42501", actor=NEWCOMER, role="cp_api"),
           "conta local so pode ser criada para o proprio ator")
    expect(error(f"INSERT INTO platform.accounts(id,status) VALUES('{NEWCOMER}','SUSPENDED')", "42501", actor=NEWCOMER, role="cp_api"),
           "conta propria nasce somente ativa")
    query(f"INSERT INTO platform.accounts(id,status) VALUES('{NEWCOMER}','ACTIVE')", actor=NEWCOMER, role="cp_api")
    member = (f"INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status,invitation_id) "
              f"VALUES('{TA}','{NEWCOMER}','{UA}','CONVIDADO FICTICIO','RESIDENT','PENDING','{INV}')")
    expect(error(member, "42501", tenant=TA, actor=NEWCOMER, role="cp_api"), "vinculo nao nasce sem convite no contexto")
    expect(error(with_invitation(EXPIRED_TOKEN, member.replace("CONVIDADO FICTICIO", "CONVIDADO VENCIDO").replace(INV, INV_EXPIRED)), "42501", tenant=TA, actor=NEWCOMER, role="cp_api"),
           "convite vencido nao cria vinculo")
    expect(error(with_invitation(TOKEN, member.replace(f",'{INV}')", ",NULL)")), "42501", tenant=TA, actor=NEWCOMER, role="cp_api"),
           "vinculo precisa apontar o proprio convite")
    expect(error(with_invitation(TOKEN, member.replace("status,invitation_id)", "status,invitation_id,valid_from)").replace(f",'{INV}')", f",'{INV}',now()-interval '1 year')")),
                 "42501", tenant=TA, actor=NEWCOMER, role="cp_api"), "convidado nao escolhe inicio da vigencia")
    manager_membership = value(f"select id from cp.memberships where tenant_id='{TA}' and account_id='{AA}' order by id limit 1")
    hijack = query(with_invitation(TOKEN, member + "; " +
        f"UPDATE cp.membership_invitations SET status='ACCEPTED',accepted_by='{NEWCOMER}',accepted_at=now(),"
        f"membership_id='{manager_membership}',version=version+1 WHERE id='{INV}'"), tenant=TA, actor=NEWCOMER, role="cp_api", check=False)
    expect(hijack.returncode != 0 and "42501" in hijack.stderr, "convite so e concluido com o vinculo do proprio convidado")
    expect(error(with_invitation(TOKEN, member.replace("'PENDING'", "'ACTIVE'")), "42501", tenant=TA, actor=NEWCOMER, role="cp_api"),
           "convidado nao se aprova sozinho")
    expect(error(with_invitation(TOKEN, member.replace("'RESIDENT'", "'OWNER'")), "42501", tenant=TA, actor=NEWCOMER, role="cp_api"),
           "papel diferente do convite e recusado")
    expect(error(with_invitation(TOKEN, member.replace(f"'{TA}','{NEWCOMER}','{UA}'", f"'{TB}','{NEWCOMER}','{UB}'")), "42501", tenant=TB, actor=NEWCOMER, role="cp_api"),
           "convite nao atravessa tenant")
    accepted = query(with_invitation(TOKEN, member + " RETURNING id; " +
        f"UPDATE cp.membership_invitations SET status='ACCEPTED',accepted_by='{NEWCOMER}',accepted_at=now(),"
        f"membership_id=(SELECT id FROM cp.memberships WHERE account_id='{NEWCOMER}'),version=version+1 WHERE id='{INV}' RETURNING id"),
        tenant=TA, actor=NEWCOMER, role="cp_api").stdout.strip().splitlines()
    expect(len(accepted) == 2, "convidado cria vinculo pendente e conclui o convite na mesma transacao")
    expect(value(with_invitation(TOKEN, f"WITH u AS (UPDATE cp.membership_invitations SET status='ACCEPTED',accepted_by='{NEWCOMER}',accepted_at=now(),membership_id=membership_id WHERE id='{INV}' RETURNING id) SELECT count(*) FROM u"),
                 tenant=TA, actor=NEWCOMER, role="cp_api") == "0", "convite aceito nao e reutilizado")
    expect(value("select count(*) from platform.tenants", actor=NEWCOMER, role="cp_api") == "0",
           "vinculo pendente nao libera o condominio")
    staff_read = "select count(*) from cp.memberships"
    expect(value(staff_read, tenant=TA, role="cp_api") == "1", "sem capacidade a gestao ve apenas o proprio vinculo")
    expect(int(value(capable("memberships.manage", staff_read), tenant=TA, role="cp_api")) >= 2,
           "gestor com capacidade ve vinculos do proprio tenant")
    approve = f"WITH u AS (UPDATE cp.memberships SET status='ACTIVE',version=version+1 WHERE account_id='{NEWCOMER}' RETURNING id) SELECT count(*) FROM u"
    expect(value(approve, tenant=TA, role="cp_api") == "0", "aprovacao exige capacidade")
    expect(value(capable("memberships.manage", approve), tenant=TA, role="cp_api") == "1", "gestor aprova vinculo pendente")
    expect(value("select count(*) from platform.tenants", actor=NEWCOMER, role="cp_api") == "1",
           "vinculo aprovado libera o condominio")
    expect(error(capable("memberships.manage", f"UPDATE cp.memberships SET role='MANAGER' WHERE account_id='{NEWCOMER}'"), "42501", tenant=TA, role="cp_api"),
           "gestao nao altera papel do vinculo")
    query(f"UPDATE cp.memberships SET status='REVOKED' WHERE account_id='{NEWCOMER}'")
    shared_invite = invite.replace(INV, INV_SHARED).replace(TOKEN, SHARED_TOKEN)
    query(capable("memberships.manage", shared_invite), tenant=TA, role="cp_api")
    for account in (NEWCOMER_2, NEWCOMER_3):
        query(f"INSERT INTO platform.accounts(id,status) VALUES('{account}','ACTIVE')", actor=account, role="cp_api")
    shared = member.replace(NEWCOMER, NEWCOMER_2).replace(INV, INV_SHARED)
    query(with_invitation(SHARED_TOKEN, shared), tenant=TA, actor=NEWCOMER_2, role="cp_api")
    expect(error(with_invitation(SHARED_TOKEN, shared.replace(NEWCOMER_2, NEWCOMER_3)), "23505", tenant=TA, actor=NEWCOMER_3, role="cp_api"),
           "o mesmo convite nao gera segundo vinculo mesmo antes de concluido")
    query(f"UPDATE cp.memberships SET status='REVOKED' WHERE account_id='{NEWCOMER_2}'")
    again = f"INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) VALUES('{TA}','{NEWCOMER}','{UA}','CONVIDADO FICTICIO','RESIDENT','PENDING')"
    query(again)
    expect(error(again, "23505"), "somente um vinculo nao revogado por conta, unidade e papel")
    expect(value(f"select count(*) from cp.memberships where account_id='{NEWCOMER}' and status='REVOKED'") == "1",
           "vinculo revogado nao impede novo convite para a mesma unidade e papel")
    query(f"UPDATE cp.memberships SET status='REVOKED' WHERE account_id='{NEWCOMER}'")

def decision_checks():
    """V008: confirmacao/rejeicao por decisor e bloqueio de manutencao por gestor de areas."""
    pending = reservation(start="20:00", end="21:00").replace("'CONFIRMED',", "'PENDING_APPROVAL',").replace(
        "'{}'::jsonb", "'{}'::jsonb").replace("rule_snapshot", "rule_snapshot,expires_at").replace(
        "'{}'::jsonb)", "'{}'::jsonb,'2030-01-01 19:00+00')")
    created = query(pending, tenant=TA, role="cp_api").stdout.strip().splitlines()[-1]
    approve = f"WITH u AS (UPDATE cp.reservations SET status='CONFIRMED',expires_at=NULL,version=version+1 WHERE id='{created}' RETURNING id) SELECT count(*) FROM u"
    expect(error(approve, "42501", tenant=TA, role="cp_api"), "morador nao confirma pedido pendente")
    expect(error(approve.replace("'CONFIRMED',expires_at=NULL", "'REJECTED',expires_at=NULL"), "42501", tenant=TA, role="cp_api"),
           "morador nao rejeita pedido pendente")
    expect(value(capable("reservations.decide", approve), tenant=TA, role="cp_api") == "1", "decisor confirma pedido pendente")
    cancel = f"WITH u AS (UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{created}' RETURNING id) SELECT count(*) FROM u"
    expect(value(cancel, tenant=TA, role="cp_api") == "1", "cancelamento continua disponivel ao fluxo residencial")
    block = (f"INSERT INTO cp.reservations(tenant_id,amenity_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot) "
             f"VALUES('{TA}','{RA}','{AA}','BLOCK','CONFIRMED','2030-01-02 08:00+00','2030-01-02 10:00+00','2030-01-02 08:00+00','2030-01-02 10:00+00','{{}}'::jsonb)")
    expect(error(block, "42501", tenant=TA, role="cp_api"), "bloqueio de manutencao exige capacidade de areas")
    query(capable("amenities.manage", block), tenant=TA, role="cp_api")
    expect(error(reservation(start="09:00", end="09:30").replace("2030-01-01", "2030-01-02"), "23P01", tenant=TA, role="cp_api"),
           "bloqueio participa da mesma exclusao das reservas")
    lift = "WITH u AS (UPDATE cp.reservations SET status='CANCELLED',decision_reason='Manutencao concluida',version=version+1 WHERE kind='BLOCK' RETURNING id) SELECT count(*) FROM u"
    expect(value(lift, tenant=TA, role="cp_api") == "0", "fluxo residencial nao altera bloqueio de manutencao")
    expect(value(capable("reservations.decide", lift), tenant=TA, role="cp_api") == "0",
           "decisor sem capacidade de areas nao altera bloqueio")
    expect(value(capable("amenities.manage", lift), tenant=TA, role="cp_api") == "1", "gestor de areas encerra bloqueio")
    query(f"INSERT INTO cp.amenities(id,tenant_id,name,capacity,approval_required) VALUES('{RQ}','{TA}','AREA FICTICIA APROVACAO',10,true)")
    direct = reservation(area=RQ, start="20:00", end="21:00")
    expect(error(direct, "42501", tenant=TA, role="cp_api"), "area com aprovacao nao aceita reserva ja confirmada")
    expect(error(capable("amenities.manage,units.manage", direct), "42501", tenant=TA, role="cp_api"),
           "nem capacidade de gestao confirma reserva direta em area com aprovacao")
    requested = direct.replace("'CONFIRMED',", "'PENDING_APPROVAL',").replace("rule_snapshot", "rule_snapshot,expires_at").replace(
        "'{}'::jsonb)", "'{}'::jsonb,'2030-01-01 19:00+00')")
    expect(bool(query(requested, tenant=TA, role="cp_api").stdout.strip()), "area com aprovacao aceita pedido pendente")
    long_reason = f"UPDATE cp.reservations SET decision_reason=repeat('x',501) WHERE amenity_id='{RQ}'"
    expect(error(long_reason, "23514", tenant=TA, role="cp_api"), "motivo da decisao tem limite de tamanho")
    # Maquina de estados: a aplicacao so cria pedido pendente ou reserva confirmada; nada volta de estado encerrado.
    held = reservation(start="22:00", end="23:00").replace("'CONFIRMED',", "'HELD',")
    expect(error(held, "42501", tenant=TA, role="cp_api"), "aplicacao nao cria retencao HELD sem fila de espera")
    expect(error(capable("reservations.decide,amenities.manage", held), "42501", tenant=TA, role="cp_api"),
           "nem decisor e gestor de areas criam retencao HELD")
    for closed in ("CANCELLED", "REJECTED", "EXPIRED"):
        expect(error(capable("reservations.decide", reservation(start="22:00", end="23:00").replace("'CONFIRMED',", f"'{closed}',")),
                     "42501", tenant=TA, role="cp_api"), f"reserva nao nasce {closed}")
    pending_block = block.replace("'CONFIRMED',", "'PENDING_APPROVAL',").replace("2030-01-02", "2030-01-03")
    expect(error(capable("amenities.manage", pending_block), "42501", tenant=TA, role="cp_api"), "bloqueio nasce somente confirmado")
    reopen = f"UPDATE cp.reservations SET status='PENDING_APPROVAL',version=version+1 WHERE id='{created}'"
    expect(error(reopen, "23514", tenant=TA, role="cp_api"), "reserva cancelada nao volta a pedido pendente")
    expect(error(capable("reservations.decide", reopen.replace("'PENDING_APPROVAL'", "'CONFIRMED'")), "23514", tenant=TA, role="cp_api"),
           "decisor nao reconfirma reserva cancelada")
    confirmed = "UPDATE cp.reservations SET status='HELD',version=version+1 WHERE starts_at='2030-01-01 13:00+00'"
    expect(error(confirmed, "23514", tenant=TA, role="cp_api"), "reserva confirmada nao volta a retencao")
    expect(error(capable("reservations.decide", confirmed.replace("'HELD'", "'REJECTED'")), "23514", tenant=TA, role="cp_api"),
           "reserva confirmada nao e rejeitada depois da decisao")
    expire = f"WITH u AS (UPDATE cp.reservations SET status='EXPIRED',version=version+1 WHERE amenity_id='{RQ}' AND status='PENDING_APPROVAL' RETURNING id) SELECT count(*) FROM u"
    expect(value(expire, tenant=TA, role="cp_api") == "1", "pedido pendente expira pelo fluxo residencial")
    revive = f"UPDATE cp.reservations SET status='CONFIRMED',expires_at=NULL,version=version+1 WHERE amenity_id='{RQ}'"
    expect(error(capable("reservations.decide", revive), "23514", tenant=TA, role="cp_api"), "pedido expirado nao e confirmado nem pelo decisor")
    expect(error(revive, "23514"), "transicao invalida e recusada tambem fora do papel da aplicacao")

def recovery_checks():
    """V009: tentativa de recuperacao visivel so pelo proprio hash; sessoes por conta so na transacao de recuperacao."""
    h1, h2 = "a" * 64, "b" * 64
    def with_hash(h, statement):
        return f"SET LOCAL app.recovery_hash='{h}'; {statement}"
    insert = ("INSERT INTO platform.recovery_attempts(id,token_hash,account_id,encrypted_access_token,mfa_required,expires_at) "
              f"VALUES(gen_random_uuid(),'{h1}','{AA}','v1.ficticio',false,clock_timestamp()+interval '10 minutes')")
    expect(error(insert, "42501", role="cp_api"), "tentativa de recuperacao exige o hash no contexto")
    expect(error(with_hash(h1, insert.replace(f"'{AA}'", f"'{AB}'")), "42501", role="cp_api"),
           "tentativa pertence somente ao ator publicado")
    query(with_hash(h1, insert), role="cp_api")
    expect(value("select count(*) from platform.recovery_attempts", role="cp_api") == "0", "sem hash nenhuma tentativa e visivel")
    expect(value(with_hash(h2, "select count(*) from platform.recovery_attempts"), role="cp_api") == "0",
           "outro hash nao le a tentativa")
    expect(value(with_hash(h1, "select count(*) from platform.recovery_attempts"), role="cp_api") == "1",
           "o proprio hash le a tentativa")
    expect(error(with_hash(h1, f"UPDATE platform.recovery_attempts SET account_id='{AB}'"), "42501", role="cp_api"),
           "conta da tentativa e imutavel para a aplicacao")
    expect(error(with_hash(h1, "UPDATE platform.recovery_attempts SET expires_at=expires_at+interval '1 hour'"), "42501", role="cp_api"),
           "prazo da tentativa e imutavel para a aplicacao")
    expect(error(with_hash(h1, "UPDATE platform.recovery_attempts SET failures=6"), "23514", role="cp_api"),
           "tentativas tem limite no banco")
    expect(error(with_hash(h2, insert.replace("'10 minutes'", "'16 minutes'").replace(h1, h2)), "23514", role="cp_api"),
           "tentativa dura no maximo 15 minutos")
    expect(error(with_hash(h2, insert.replace("false", "true").replace(h1, h2)), "23514", role="cp_api"),
           "segundo fator exigido sempre aponta o fator")
    forged = ("INSERT INTO platform.recovery_attempts(id,token_hash,account_id,encrypted_access_token,mfa_required,created_at,expires_at) "
              f"VALUES(gen_random_uuid(),'{h2}','{AA}','v1.ficticio',false,clock_timestamp()+interval '30 days',"
              "clock_timestamp()+interval '30 days 10 minutes')")
    expect(error(with_hash(h2, forged), "42501", role="cp_api"), "aplicacao nao escolhe created_at para estender o prazo")
    expect(error(with_hash(h2, insert.replace("mfa_required,", "mfa_required,failures,").replace("false,", "false,0,").replace(h1, h2)),
                 "42501", role="cp_api"), "aplicacao nao define falhas na criacao")
    other = with_hash(h2, f"WITH u AS (UPDATE platform.recovery_attempts SET failures=failures+1 WHERE token_hash='{h1}' RETURNING 1) "
                          "SELECT count(*) FROM u")
    expect(value(other, role="cp_api") == "0", "outro hash nao altera a tentativa")
    removed = with_hash(h2, f"WITH d AS (DELETE FROM platform.recovery_attempts WHERE token_hash='{h1}' RETURNING 1) SELECT count(*) FROM d")
    expect(value(removed, role="cp_api") == "0", "outro hash nao remove a tentativa")
    query(with_hash(h1, "UPDATE platform.recovery_attempts SET failures=2"), role="cp_api")
    expect(error(with_hash(h1, "UPDATE platform.recovery_attempts SET failures=1"), "42501", role="cp_api"),
           "falhas da tentativa nao diminuem")
    mfa = ("INSERT INTO platform.recovery_attempts(id,token_hash,account_id,encrypted_access_token,factor_id,mfa_required,expires_at) "
           f"VALUES(gen_random_uuid(),'{h2}','{AA}','v1.aal1',gen_random_uuid(),true,clock_timestamp()+interval '10 minutes')")
    query(with_hash(h2, mfa), role="cp_api")
    expect(error(with_hash(h2, "UPDATE platform.recovery_attempts SET mfa_required=false"), "42501", role="cp_api"),
           "segundo fator nao e dispensado sem novo acesso elevado")
    query(with_hash(h2, "UPDATE platform.recovery_attempts SET mfa_required=false,encrypted_access_token='v1.aal2'"), role="cp_api")
    expect(error(with_hash(h2, "UPDATE platform.recovery_attempts SET mfa_required=true,encrypted_access_token='v1.outro'"), "42501",
                 role="cp_api"), "exigencia do segundo fator nao volta depois de dispensada")
    sessions = ("INSERT INTO platform.web_sessions(token_hash,account_id,encrypted_access_token,encrypted_refresh_token,"
                "access_expires_at,expires_at) VALUES"
                + ",".join(f"('{h}','{account}','v1.ficticio','v1.ficticio',now()+interval '5 minutes',now()+interval '1 hour')"
                           for h, account in (("c" * 64, AA), ("d" * 64, AA), ("e" * 64, AB))))
    query(sessions)
    purge = f"WITH d AS (DELETE FROM platform.web_sessions WHERE account_id='{AA}' RETURNING 1) SELECT count(*) FROM d"
    expect(value(purge, role="cp_api") == "0", "sessoes da conta nao sao removidas fora da recuperacao")
    marker = "SET LOCAL app.recovery_purge='on'; "
    expect(value(marker + purge, role="cp_api", actor=AB) == "0", "marcador de recuperacao nao alcanca outra conta")
    expect(value(marker + purge, role="cp_api") == "2", "recuperacao remove todas as sessoes da propria conta")
    expect(value(f"select count(*) from platform.web_sessions where account_id='{AB}'") == "1",
           "sessao de outra conta permanece")
    query(f"DELETE FROM platform.web_sessions WHERE account_id='{AB}'; DELETE FROM platform.recovery_attempts")

def platform_checks():
    """V010: papel de plataforma so por operacao revisada; catalogo global e auditoria da plataforma por capacidade."""
    def caps(value, statement):
        return f"SET LOCAL app.platform_capabilities='{value}'; {statement}"
    grant_role = (f"INSERT INTO platform.platform_roles(account_id,role,status,reason) "
                  f"VALUES('{AA}','PLATFORM_ADMIN','ACTIVE','ensaio ficticio')")
    expect(error(grant_role, "42501", role="cp_api"), "aplicacao nao concede papel de plataforma")
    query(grant_role)
    history = "select string_agg(operation||':'||status||':'||coalesce(revoke_reason,'-'),',' order by id) from platform.platform_role_events"
    expect(value(history) == "INSERT:ACTIVE:-", "concessao do papel fica na trilha operacional")
    expect(value("select executed_by=session_user from platform.platform_role_events") == "t", "trilha registra a identidade que executou")
    expect(error("UPDATE platform.platform_roles SET status='REVOKED',revoked_at=now()", "23514"),
           "revogacao exige motivo proprio")
    query("UPDATE platform.platform_roles SET status='REVOKED',revoked_at=now(),revoke_reason='revogacao ficticia'")
    expect(value("select reason from platform.platform_roles") == "ensaio ficticio", "revogacao preserva o motivo da concessao")
    query("UPDATE platform.platform_roles SET status='ACTIVE',revoked_at=NULL,revoke_reason=NULL,granted_at=clock_timestamp()")
    expect(value(history) == "INSERT:ACTIVE:-,UPDATE:REVOKED:revogacao ficticia,UPDATE:ACTIVE:-", "revogacao e reativacao ficam na trilha")
    for reader in ("cp_api", "cp_worker", "cp_onboarding"):
        expect(error("select count(*) from platform.platform_role_events", "42501", role=reader), f"{reader} nao le a trilha de papeis")
    expect(error(f"INSERT INTO platform.platform_role_events(operation,account_id,role,status,reason) VALUES('INSERT','{AB}','PLATFORM_ADMIN','ACTIVE','x')",
                 "42501", role="cp_api"), "aplicacao nao forja a trilha de papeis")
    expect(value("select count(*) from platform.platform_roles", role="cp_api") == "1", "conta le o proprio papel de plataforma")
    expect(value("select count(*) from platform.platform_roles", role="cp_api", actor=AB) == "0",
           "papel de plataforma de outra conta nao e visivel")
    expect(error("UPDATE platform.platform_roles SET status='REVOKED',revoked_at=now()", "42501", role="cp_api"),
           "aplicacao nao altera papel de plataforma")
    expect(error("select count(*) from platform.platform_roles", "42501", role="cp_worker"), "worker nao le papeis de plataforma")
    expect(error("DELETE FROM platform.platform_roles", "42501", role="cp_api"), "aplicacao nao apaga papel de plataforma")
    for reader in ("cp_worker", "cp_onboarding"):
        expect(error("select count(*) from platform.platform_audit_log", "42501", role=reader), f"{reader} nao le a trilha da plataforma")
    expect(value("select count(*) from platform.tenants", role="cp_api") == "1", "sem capacidade o catalogo segue o vinculo")
    expect(value(caps("tenants.read", "select count(*) from platform.tenants"), role="cp_api") == "2",
           "capacidade publicada le o catalogo global")
    expect(value(caps("tenants.manage", "select count(*) from platform.tenants"), role="cp_api") == "1",
           "capacidade de gestao nao implica leitura global")
    expect(value(caps("tenants.read,x", "select platform.has_platform_capability('tenants.manage')::text"), role="cp_api") == "false",
           "capacidade de plataforma exige correspondencia exata")
    rename = f"UPDATE platform.tenants SET name='RENOMEADO',version=version+1 WHERE id='{TA}'"
    expect(error(caps("tenants.read", rename), "42501", role="cp_api"), "leitura global nao altera condominio")
    local = (f"SET LOCAL app.capabilities='units.manage,memberships.manage,amenities.manage,audit.read'; "
             f"WITH u AS (UPDATE platform.tenants SET name='GESTOR LOCAL' WHERE id='{TB}' RETURNING 1) SELECT count(*) FROM u")
    expect(value(local, role="cp_api", actor=AB, tenant=TB) == "0", "gestor local nao ganha poder de plataforma")
    expect(error(f"SELECT id FROM platform.tenants WHERE id='{TB}' FOR UPDATE", "42501", role="cp_api", actor=AB, tenant=TB)
           or value(f"SELECT count(*) FROM (SELECT id FROM platform.tenants WHERE id='{TB}' FOR UPDATE) l", role="cp_api", actor=AB, tenant=TB) == "0",
           "membro do condominio nao bloqueia a linha do catalogo")
    mass = "WITH u AS (UPDATE platform.tenants SET active=false RETURNING 1) SELECT count(*) FROM u"
    expect(value(caps("tenants.manage", mass), role="cp_api") == "0", "alteracao sem leitura global nao alcanca o catalogo")
    expect(value(caps("tenants.read,tenants.manage", f"WITH u AS ({rename} RETURNING version) SELECT version FROM u"),
                 role="cp_api") == "1", "plataforma renomeia condominio pela capacidade")
    expect(error(caps("tenants.read,tenants.manage", f"UPDATE platform.tenants SET time_zone='UTC' WHERE id='{TA}'"),
                 "42501", role="cp_api"), "fuso do condominio fica fora do painel")
    expect(error(caps("tenants.read,tenants.manage", f"UPDATE platform.tenants SET name='' WHERE id='{TA}'"),
                 "23514", role="cp_api"), "nome vazio e recusado no banco")
    audit = (f"INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) "
             f"VALUES('{AA}','TENANT_RENAMED','{TA}','ensaio','{{\"name\":\"RENOMEADO\"}}')")
    expect(error(audit, "42501", role="cp_api"), "auditoria da plataforma exige capacidade publicada")
    expect(error(caps("tenants.read,platform.audit.read", audit), "42501", role="cp_api"), "leitura nao grava trilha da plataforma")
    expect(error(caps("x", audit), "42501", role="cp_api"), "capacidade desconhecida nao grava trilha")
    expect(error(caps("tenants.manage", audit.replace("(actor_id,", "(id,actor_id,").replace(f"VALUES('{AA}',", f"VALUES(gen_random_uuid(),'{AA}',")),
                 "42501", role="cp_api"), "aplicacao nao escolhe o id da trilha")
    expect(error(caps("tenants.manage", audit), "42501", role="cp_api", actor=AB), "auditoria pertence ao ator publicado")
    query(caps("tenants.manage", audit), role="cp_api")
    expect(error(caps("tenants.manage", audit.replace("'ensaio'", "'ensaio'").replace("details)", "details,created_at)")
                       .replace("}')", "}',now()-interval '1 day')")), "42501", role="cp_api"), "aplicacao nao escolhe data da auditoria")
    expect(value("select count(*) from platform.platform_audit_log", role="cp_api") == "0", "auditoria da plataforma exige leitura")
    expect(value(caps("platform.audit.read", "select count(*) from platform.platform_audit_log"), role="cp_api") == "1",
           "leitura da auditoria da plataforma por capacidade")
    expect(error(caps("platform.audit.read,tenants.manage", "UPDATE platform.platform_audit_log SET reason='x'"), "42501", role="cp_api"),
           "aplicacao nao altera auditoria da plataforma")
    expect(error(caps("platform.audit.read,tenants.manage", "DELETE FROM platform.platform_audit_log"), "42501", role="cp_api"),
           "aplicacao nao apaga auditoria da plataforma")
    query(f"INSERT INTO platform.onboarding_runs(operation_id,tenant_id,manifest_hash,database_actor,counts) "
          f"VALUES(gen_random_uuid(),'{TA}',repeat('a',64),'cp_onboarding_ficticio','{{\"units\":1}}')")
    expect(value("select count(*) from platform.onboarding_runs", role="cp_api") == "0", "recibo de onboarding exige capacidade")
    expect(value(caps("tenants.read", "select (counts->>'units') from platform.onboarding_runs"), role="cp_api") == "1",
           "plataforma le contagens do onboarding")
    expect(error(caps("tenants.read", "select manifest_hash from platform.onboarding_runs"), "42501", role="cp_api"),
           "hash do manifesto fica fora do painel")
    expect(error(caps("tenants.read", "select database_actor from platform.onboarding_runs"), "42501", role="cp_api"),
           "principal do banco fica fora do painel")
    suspended_token = "9" * 64
    query(f"INSERT INTO cp.membership_invitations(id,tenant_id,unit_id,role,display_name,email_hash,token_hash,expires_at,created_by) "
          f"VALUES('70000000-0000-4000-8000-000000000009','{TB}','{UB}','RESIDENT','CONVIDADO SUSPENSO','{'8'*64}','{suspended_token}',"
          f"now()+interval '7 days','{AB}'); UPDATE platform.tenants SET active=false WHERE id='{TB}'")
    expect(value(with_invitation(suspended_token, f"select count(*) from platform.tenants where id='{TB}'"), actor=NEWCOMER_3, role="cp_api") == "0",
           "convite nao revela condominio suspenso")
    suspended_member = (f"INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status,invitation_id) "
                        f"VALUES('{TB}','{NEWCOMER_3}','{UB}','CONVIDADO SUSPENSO','RESIDENT','PENDING','70000000-0000-4000-8000-000000000009')")
    expect(error(with_invitation(suspended_token, suspended_member), "42501", tenant=TB, actor=NEWCOMER_3, role="cp_api"),
           "convite de condominio suspenso nao cria vinculo")
    query(f"UPDATE platform.tenants SET active=true WHERE id='{TB}'")
    expect(value(with_invitation(suspended_token, f"select count(*) from platform.tenants where id='{TB}'"), actor=NEWCOMER_3, role="cp_api") == "1",
           "convite pendente revela somente o proprio condominio ativo")
    query("DELETE FROM cp.membership_invitations WHERE id='70000000-0000-4000-8000-000000000009'")
    query(f"DELETE FROM platform.onboarding_runs WHERE tenant_id='{TA}'; DELETE FROM platform.platform_audit_log; "
          f"DELETE FROM platform.platform_roles; UPDATE platform.tenants SET name='FICTICIO A' WHERE id='{TA}'")
    expect(value(history).endswith(",DELETE:ACTIVE:-"), "remocao do papel tambem fica na trilha")
    query("DELETE FROM platform.platform_role_events")

def parcel_checks():
    """V012: portaria recebe, entrega e anula por capacidade; unidade ve so o que e seu; retirada unica e identificada."""
    CONC, UA2, P2, P3, P4, P5 = ("10000000-0000-4000-8000-0000000000c1", "20000000-0000-4000-8000-0000000000a2",
                                 "40000000-0000-4000-8000-000000000002", "40000000-0000-4000-8000-000000000003",
                                 "40000000-0000-4000-8000-000000000004", "40000000-0000-4000-8000-000000000005")
    DEP, OWN, SUS, RES2 = ("10000000-0000-4000-8000-0000000000d1", "10000000-0000-4000-8000-0000000000d2",
                           "10000000-0000-4000-8000-0000000000d3", "10000000-0000-4000-8000-0000000000d4")
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{CONC}','ACTIVE'),('{DEP}','ACTIVE'),('{OWN}','ACTIVE'),('{SUS}','SUSPENDED'),
          ('{RES2}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UA2}','{TA}','FICTICIA 102');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{CONC}',NULL,'PORTARIA FICTICIA','CONCIERGE','ACTIVE'),
                ('{TA}','{DEP}','{UA}','DEPENDENTE FICTICIO','DEPENDENT','ACTIVE'),
                ('{TA}','{OWN}','{UA}','PROPRIETARIO FICTICIO','OWNER','ACTIVE'),
                ('{TA}','{SUS}','{UA}','CONTA SUSPENSA FICTICIA','RESIDENT','ACTIVE'),
                ('{TA}','{RES2}','{UA2}','MORADOR FICTICIO 102','RESIDENT','ACTIVE');
        INSERT INTO cp.parcels(id,tenant_id,unit_id,quantity,description,storage_location,received_by)
          VALUES('{P2}','{TA}','{UA2}',2,'CAIXAS FICTICIAS','PRATELEIRA 2','{CONC}');
    """)
    def member(account):
        return value(f"select id from cp.memberships where tenant_id='{TA}' and account_id='{account}'")
    M_AA, M_DEP, M_OWN, M_SUS, M_RES2 = member(AA), member(DEP), member(OWN), member(SUS), member(RES2)
    concierge = "SET LOCAL app.capabilities='concierge.operate'; "
    def as_concierge(statement):
        return concierge + statement
    expect(value(f"select count(*) from cp.parcels where id in ('{PA}','{P2}')", tenant=TA, role="cp_api") == "1",
           "morador ve somente encomendas da propria unidade")
    expect(value(f"select count(*) from cp.parcels where id='{P2}'", tenant=TA, role="cp_api") == "0",
           "UUID de encomenda de outra unidade nao atravessa RLS")
    for label, account in (("dependente sem permissao", DEP), ("proprietario nao residente", OWN), ("conta suspensa", SUS)):
        expect(value(f"select count(*) from cp.parcels where id='{PA}'", tenant=TA, actor=account, role="cp_api") == "0",
               f"{label} nao ve as encomendas da unidade")
    query(f"UPDATE cp.memberships SET permissions='{{\"parcels\":{{\"view\":true}}}}' WHERE id='{M_DEP}'")
    expect(value(f"select count(*) from cp.parcels where id='{PA}'", tenant=TA, actor=DEP, role="cp_api") == "1",
           "dependente com permissao explicita ve as encomendas da unidade")
    expect(value(f"select count(*) from cp.parcels where id in ('{PA}','{P2}')", tenant=TA, actor=CONC, role="cp_api") == "0",
           "portaria sem capacidade publicada (sem AAL2) nao ve encomendas")
    expect(value(as_concierge(f"select count(*) from cp.parcels where id in ('{PA}','{P2}')"), tenant=TA, actor=CONC, role="cp_api") == "2",
           "portaria com capacidade ve as encomendas do condominio")
    expect(value(as_concierge("select count(*) from cp.parcels"), tenant=TB, actor=AB, role="cp_api") == "0",
           "capacidade publicada nao atravessa condominio")
    expect(value(as_concierge(f"select string_agg(display_name,',' order by display_name) from cp.memberships where unit_id='{UA}'"),
                 tenant=TA, actor=CONC, role="cp_api") == "CONTA SUSPENSA FICTICIA,DEPENDENTE FICTICIO,PESSOA FICTICIA A",
           "portaria ve os vinculos residenciais da unidade, sem proprietario")
    expect(value(f"select count(*) from cp.memberships where unit_id='{UA}'", tenant=TA, actor=CONC, role="cp_api") == "0",
           "portaria sem capacidade nao ve vinculos")
    def receive(identifier, actor=CONC, unit=UA, extra_cols="", extra_vals=""):
        return (f"INSERT INTO cp.parcels(id,tenant_id,unit_id,quantity,description,storage_location,received_by{extra_cols}) "
                f"VALUES('{identifier}','{TA}','{unit}',1,'ENVELOPE FICTICIO','ARMARIO 1','{actor}'{extra_vals})")
    expect(error(receive(P3, actor=AA), "42501", tenant=TA, actor=AA, role="cp_api"), "morador nao registra recebimento")
    expect(error(as_concierge(receive(P3, actor=AA)), "42501", tenant=TA, actor=CONC, role="cp_api"),
           "recebimento registra a propria conta da portaria")
    expect(error(as_concierge(receive(P3, extra_cols=",status", extra_vals=",'DELIVERED'")), "42501", tenant=TA, actor=CONC, role="cp_api"),
           "encomenda nasce recebida")
    expect(error(as_concierge(receive(P3, extra_cols=",received_at", extra_vals=",now()-interval '1 day'")), "42501", tenant=TA, actor=CONC,
                 role="cp_api"), "aplicacao nao escolhe a data do recebimento")
    query(f"UPDATE cp.units SET status='INACTIVE' WHERE id='{UA2}'")
    expect(error(as_concierge(receive(P3, unit=UA2)), "42501", tenant=TA, actor=CONC, role="cp_api"), "unidade inativa nao recebe encomenda")
    expect(value(f"select count(*) from cp.parcels where id='{P2}'", tenant=TA, actor=RES2, role="cp_api") == "0",
           "morador de unidade inativa nao ve as encomendas dela")
    query(f"UPDATE cp.units SET status='ACTIVE' WHERE id='{UA2}'")
    query(as_concierge(receive(P3)), tenant=TA, actor=CONC, role="cp_api")
    expect(value(f"select count(*) from cp.parcels where id='{P3}'", tenant=TA, role="cp_api") == "1", "morador ve encomenda recebida da unidade")
    resident_update = f"WITH u AS (UPDATE cp.parcels SET status='DELIVERED',version=version+1 WHERE id='{P3}' RETURNING 1) SELECT count(*) FROM u"
    expect(value(resident_update, tenant=TA, role="cp_api") == "0", "morador nao marca entrega")
    expect(error(as_concierge(f"UPDATE cp.parcels SET quantity=9 WHERE id='{P3}'"), "42501", tenant=TA, actor=CONC, role="cp_api"),
           "quantidade e local ficam fora da alteracao")
    expect(error(as_concierge(f"UPDATE cp.parcels SET status='DELIVERED',version=version+1 WHERE id='{P3}'"), "23514", tenant=TA, actor=CONC,
                 role="cp_api"), "entrega sem registro de retirada e recusada no commit")
    expect(error(as_concierge(f"UPDATE cp.parcels SET status='DELIVERED',version=version+2 WHERE id='{P3}'"), "23514", tenant=TA, actor=CONC,
                 role="cp_api"), "versao da encomenda avanca uma unidade")
    def handoff(parcel=P3, operator=CONC, evidence="ASSISTED", membership=M_AA, name="PESSOA FICTICIA A"):
        return (f"INSERT INTO cp.parcel_handoffs(tenant_id,parcel_id,operator_id,recipient_name,recipient_membership_id,evidence_kind) "
                f"VALUES('{TA}','{parcel}','{operator}','{name}','{membership}','{evidence}')")
    def deliver(parcel=P3):
        return f"UPDATE cp.parcels SET status='DELIVERED',version=version+1 WHERE id='{parcel}'; "
    expect(error(as_concierge(deliver() + handoff(operator=AA)), "42501", tenant=TA, actor=CONC, role="cp_api"),
           "retirada registra a propria conta da portaria")
    expect(error(as_concierge(deliver() + handoff(evidence="CODE")), "42501", tenant=TA, actor=CONC, role="cp_api"),
           "codigo e delegacao ainda nao sao aceitos")
    for label, membership, name in (
            ("vinculo de outra unidade", M_RES2, "MORADOR FICTICIO 102"),
            ("nome diferente do vinculo", M_AA, "OUTRA PESSOA FICTICIA"),
            ("proprietario nao residente", M_OWN, "PROPRIETARIO FICTICIO"),
            ("conta suspensa", M_SUS, "CONTA SUSPENSA FICTICIA"),
            ("dependente sem permissao de retirada", M_DEP, "DEPENDENTE FICTICIO")):
        expect(error(as_concierge(deliver() + handoff(membership=membership, name=name)), "42501", tenant=TA, actor=CONC, role="cp_api"),
               f"retirada por {label} e recusada")
    expect(error(handoff(operator=AA), "42501", tenant=TA, role="cp_api"), "morador nao registra retirada")
    expect(error(as_concierge(handoff()), "23514", tenant=TA, actor=CONC, role="cp_api"), "retirada sem entrega e recusada no commit")
    query(as_concierge(deliver() + handoff()), tenant=TA, actor=CONC, role="cp_api")
    expect(value(f"select status||':'||version from cp.parcels where id='{P3}'") == "DELIVERED:1", "entrega com registro de retirada")
    expect(value(f"select recipient_name from cp.parcel_handoffs where parcel_id='{P3}'", tenant=TA, role="cp_api") == "PESSOA FICTICIA A",
           "morador ve quem retirou a encomenda da unidade")
    expect(value(f"select count(*) from cp.parcel_handoffs where parcel_id='{P3}'", tenant=TA, actor=AB, role="cp_api") == "0",
           "retirada de outra unidade nao e visivel")
    expect(error(as_concierge(f"UPDATE cp.parcels SET status='RECEIVED',version=version+1 WHERE id='{P3}'"), "23514", tenant=TA, actor=CONC,
                 role="cp_api"), "entrega nao volta")
    expect(error(as_concierge(f"DELETE FROM cp.parcel_handoffs WHERE parcel_id='{P3}'"), "42501", tenant=TA, actor=CONC, role="cp_api"),
           "registro de retirada nao e apagado")
    # Dependente com permissao de retirada, escolhido pela portaria.
    query(as_concierge(receive(P4)), tenant=TA, actor=CONC, role="cp_api")
    query(f"UPDATE cp.memberships SET permissions='{{\"parcels\":{{\"view\":true,\"pickup\":true}}}}' WHERE id='{M_DEP}'")
    query(as_concierge(deliver(P4) + handoff(parcel=P4, membership=M_DEP, name="DEPENDENTE FICTICIO")), tenant=TA, actor=CONC, role="cp_api")
    expect(value(f"select recipient_membership_id from cp.parcel_handoffs where parcel_id='{P4}'") == M_DEP,
           "retirada grava o vinculo de quem retirou")
    # Registro indevido: anulado pela portaria com motivo; some da unidade e nao e retirado.
    query(as_concierge(receive(P5)), tenant=TA, actor=CONC, role="cp_api")
    def void(actor=CONC, reason="'Unidade errada'"):
        return f"UPDATE cp.parcels SET status='VOIDED',voided_by='{actor}',void_reason={reason},version=version+1 WHERE id='{P5}'"
    expect(error(as_concierge(void(actor=AA)), "42501", tenant=TA, actor=CONC, role="cp_api"), "anulacao registra a propria conta")
    expect(error(as_concierge(void(reason="NULL")), "23514", tenant=TA, actor=CONC, role="cp_api"), "anulacao exige motivo")
    expect(error(as_concierge(f"UPDATE cp.parcels SET voided_at=now() WHERE id='{P5}'"), "42501", tenant=TA, actor=CONC, role="cp_api"),
           "aplicacao nao escolhe o horario da anulacao")
    expect(value(f"WITH u AS ({void(actor=AA)} RETURNING 1) SELECT count(*) FROM u", tenant=TA, role="cp_api") == "0", "morador nao anula")
    query(as_concierge(void()), tenant=TA, actor=CONC, role="cp_api")
    expect(value(f"select status||':'||version||':'||(voided_at is not null) from cp.parcels where id='{P5}'") == "VOIDED:1:true",
           "anulacao com versao e horario do banco")
    expect(value(f"select count(*) from cp.parcels where id='{P5}'", tenant=TA, role="cp_api") == "0", "unidade nao ve registro anulado")
    expect(error(as_concierge(deliver(P5) + handoff(parcel=P5)), "23514", tenant=TA, actor=CONC, role="cp_api"),
           "registro anulado nao e retirado")
    expect(error(as_concierge(f"UPDATE cp.parcels SET status='RECEIVED',voided_by=NULL,void_reason=NULL,version=version+1 WHERE id='{P5}'"),
                 "23514", tenant=TA, actor=CONC, role="cp_api"), "anulacao nao volta")
    # Eventos e trilha de encomendas somente pela portaria, em nome da propria conta.
    event = f"INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES('{TA}','PARCEL_DELIVERED','{P2}',9,'{{}}')"
    expect(error(event, "42501", tenant=TA, role="cp_api"), "morador nao grava evento de encomenda")
    trail = "INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) VALUES('{tenant}','{actor}','PARCEL_RECEIVED','PARCEL','{p}','{t}')"
    expect(error(trail.format(tenant=TA, actor=AA, p=P2, t=ROLL), "42501", tenant=TA, role="cp_api"), "morador nao grava trilha de encomenda")
    expect(error(as_concierge(trail.format(tenant=TA, actor=AA, p=P2, t=ROLL)), "42501", tenant=TA, actor=CONC, role="cp_api"),
           "portaria grava trilha de encomenda so em nome da propria conta")
    # V015 libera ao worker somente unidade e situacao (aviso de encomenda ainda a retirar); dados da encomenda nao.
    expect(error("select description,storage_location from cp.parcels", "42501", tenant=TA, role="cp_worker"), "worker nao le dados da encomenda")
    query(f"DELETE FROM cp.parcel_handoffs WHERE parcel_id in ('{P3}','{P4}'); DELETE FROM cp.parcels WHERE id in ('{P2}','{P3}','{P4}','{P5}'); "
          f"DELETE FROM cp.memberships WHERE account_id in ('{CONC}','{DEP}','{OWN}','{SUS}','{RES2}'); DELETE FROM cp.units WHERE id='{UA2}'; "
          f"DELETE FROM platform.accounts WHERE id in ('{CONC}','{DEP}','{OWN}','{SUS}','{RES2}')")

def billing_checks():
    """V011: financeiro da plataforma por capacidade; PAID somente com pagamento registrado do mesmo valor."""
    PLAN, SUB, CH1, CH2, PAY, PAY2, SUB2, CH3, PAY3, CH4 = (
        "90000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(1, 11))
    full = "billing.read,billing.manage"
    def caps(value, statement):
        return f"SET LOCAL app.platform_capabilities='{value}'; {statement}"
    def plan(actor=AA, extra_cols="", extra_vals="", price=1000, name="PLANO FICTICIO"):
        return (f"INSERT INTO platform.billing_plans(name,price_cents,period,created_by{extra_cols}) "
                f"VALUES('{name}',{price},'MONTHLY','{actor}'{extra_vals})")
    expect(value("select count(*) from platform.billing_plans", role="cp_api") == "0", "sem capacidade nao ha planos visiveis")
    expect(error(plan(), "42501", role="cp_api"), "plano exige capacidade de financeiro")
    expect(error(caps(full, plan(actor=AB)), "42501", role="cp_api"), "plano registra o proprio ator")
    expect(error(caps(full, plan(extra_cols=",version", extra_vals=",5")), "42501", role="cp_api"),
           "aplicacao nao define versao do plano na criacao")
    expect(error(caps(full, plan(price=0, name="PLANO GRATUITO")), "23514", role="cp_api"), "plano sem valor e recusado")
    query(f"INSERT INTO platform.billing_plans(id,name,price_cents,period,created_by) VALUES('{PLAN}','PLANO FICTICIO',1000,'MONTHLY','{AA}')")
    expect(value(caps("billing.manage", "select count(*) from platform.billing_plans"), role="cp_api") == "0",
           "gestao financeira sem leitura nao enxerga planos")
    expect(value(caps(full, "select count(*) from platform.billing_plans"), role="cp_api") == "1", "leitura financeira por capacidade")
    def subscription(status="ACTIVE", price=1000, start="2030-01-01", identifier=None):
        cols = "id," if identifier else ""
        vals = f"'{identifier}'," if identifier else ""
        return (f"INSERT INTO platform.billing_subscriptions({cols}tenant_id,plan_id,price_cents,period,starts_on,due_day,status,created_by) "
                f"VALUES({vals}'{TA}','{PLAN}',{price},'MONTHLY','{start}',10,'{status}','{AA}')")
    expect(error(caps(full, subscription("ENDED")), "42501", role="cp_api"), "assinatura nasce ativa")
    expect(error(caps(full, subscription(price=999)), "23514", role="cp_api"), "assinatura copia o valor do plano")
    expect(error(caps("billing.manage", subscription()), "42501", role="cp_api"), "assinatura sem leitura do plano falha fechada")
    query(subscription(identifier=SUB))
    expect(error(caps(full, subscription()), "23505", role="cp_api"), "uma assinatura ativa por condominio")
    def charge(identifier, start, end, due, status="OPEN", amount=1000, sub=SUB, tenant=TA):
        return (f"INSERT INTO platform.billing_charges(id,subscription_id,tenant_id,period_start,period_end,amount_cents,due_on,status,created_by) "
                f"VALUES('{identifier}','{sub}','{tenant}','{start}','{end}',{amount},'{due}','{status}','{AA}')")
    expect(error(caps(full, charge(CH2, "2030-02-01", "2030-03-01", "2030-02-10", "PAID")), "42501", role="cp_api"),
           "cobranca nasce em aberto")
    for label, statement in (
            ("cobranca com valor diferente do contratado", charge(CH2, "2030-02-01", "2030-03-01", "2030-02-10", amount=900)),
            ("cobranca fora do dia de inicio da assinatura", charge(CH2, "2030-02-15", "2030-03-15", "2030-03-10")),
            ("cobranca com fim de periodo divergente", charge(CH2, "2030-02-01", "2030-04-01", "2030-02-10")),
            ("cobranca com vencimento fora da regra", charge(CH2, "2030-02-01", "2030-03-01", "2030-02-20")),
            ("cobranca antes do inicio da assinatura", charge(CH2, "2029-12-01", "2030-01-01", "2029-12-10")),
            ("cobranca em outro condominio", charge(CH2, "2030-02-01", "2030-03-01", "2030-02-10", tenant=TB))):
        expect(error(caps(full, statement), "23514", role="cp_api"), label + " e recusada")
    expect(error(caps("billing.manage", charge(CH2, "2030-02-01", "2030-03-01", "2030-02-10")), "42501", role="cp_api"),
           "cobranca sem leitura da assinatura falha fechada")
    query(charge(CH1, "2030-01-01", "2030-02-01", "2030-01-10"))
    paid = f"UPDATE platform.billing_charges SET status='PAID',version=version+1 WHERE id='{CH1}'"
    expect(error(caps(full, paid), "23514", role="cp_api"), "cobranca paga exige pagamento registrado")
    expect(error(caps(full, f"UPDATE platform.billing_charges SET status='PAID' WHERE id='{CH1}'"), "23514", role="cp_api"),
           "transicao da cobranca avanca a versao")
    def payment(identifier, amount, actor=AA, charge_id=CH1, reference="E2E-FICTICIO-001", paid_on="2025-01-08"):
        return (f"INSERT INTO platform.billing_payments(id,charge_id,amount_cents,paid_on,method,reference,status,recorded_by) "
                f"VALUES('{identifier}','{charge_id}',{amount},'{paid_on}','PIX','{reference}','RECORDED','{actor}')")
    expect(error(caps(full, payment(PAY, 1000)), "23514", role="cp_api"), "pagamento sem baixa da cobranca e recusado")
    expect(error(caps(full, payment(PAY, 900) + "; " + paid), "23514", role="cp_api"), "pagamento de valor diferente nao baixa a cobranca")
    expect(error(caps(full, payment(PAY, 1000, actor=AB) + "; " + paid), "42501", role="cp_api"), "pagamento registra o proprio ator")
    future = "((clock_timestamp() at time zone 'America/Sao_Paulo')::date + 2)"
    expect(error(caps(full, payment(PAY, 1000, paid_on="2025-01-08").replace("'2025-01-08'", future) + "; " + paid), "23514",
                 role="cp_api"), "pagamento com data futura e recusado")
    query(caps(full, payment(PAY, 1000) + "; " + paid), role="cp_api")
    expect(value(f"select status from platform.billing_charges where id='{CH1}'") == "PAID", "baixa com pagamento do mesmo valor")
    expect(error(caps(full, payment(PAY2, 1000, reference="E2E-FICTICIO-OUTRO")), "23505", role="cp_api"),
           "um pagamento registrado por cobranca")
    expect(error(caps(full, f"UPDATE platform.billing_charges SET status='CANCELLED',cancel_reason='x',version=version+1 WHERE id='{CH1}'"),
                 "23514", role="cp_api"), "cobranca paga nao e cancelada sem estorno")
    def void(actor):
        return (f"UPDATE platform.billing_payments SET status='VOIDED',voided_by='{actor}',voided_at=now(),void_reason='Registro errado' "
                f"WHERE id='{PAY}'")
    reopen = f"UPDATE platform.billing_charges SET status='OPEN',version=version+1 WHERE id='{CH1}'"
    expect(error(caps(full, void(AB) + "; " + reopen), "42501", role="cp_api"), "estorno registra o proprio ator")
    expect(error(caps(full, void(AA)), "23514", role="cp_api"), "estorno sem reabrir a cobranca e recusado")
    query(caps(full, void(AA) + "; " + reopen), role="cp_api")
    expect(error(caps(full, f"UPDATE platform.billing_payments SET status='RECORDED' WHERE id='{PAY}'"), "23514", role="cp_api"),
           "pagamento estornado nao volta")
    expect(error(caps(full, f"DELETE FROM platform.billing_payments WHERE id='{PAY}'"), "42501", role="cp_api"), "pagamento nao e apagado")
    query(caps(full, f"UPDATE platform.billing_charges SET status='CANCELLED',cancel_reason='Periodo indevido',version=version+1 WHERE id='{CH1}'"),
          role="cp_api")
    expect(error(caps(full, f"UPDATE platform.billing_charges SET status='OPEN',cancel_reason=null,version=version+1 WHERE id='{CH1}'"),
                 "23514", role="cp_api"), "cobranca cancelada nao volta")
    expect(error(caps(full, f"UPDATE platform.billing_charges SET amount_cents=1 WHERE id='{CH1}'"), "42501", role="cp_api"),
           "valor da cobranca e imutavel para a aplicacao")
    # Periodo cancelado pode ser cobrado de novo; o comprovante estornado quita a cobranca correta.
    query(caps(full, charge(CH2, "2030-01-01", "2030-02-01", "2030-01-10")), role="cp_api")
    expect(error(caps(full, charge(CH3, "2030-01-01", "2030-02-01", "2030-01-10")), "23P01", role="cp_api"),
           "periodo nao cancelado nao e cobrado duas vezes")
    query(caps(full, payment(PAY2, 1000, charge_id=CH2) + f"; UPDATE platform.billing_charges SET status='PAID',version=version+1 WHERE id='{CH2}'"),
          role="cp_api")
    query(caps(full, charge(CH3, "2030-02-01", "2030-03-01", "2030-02-10")), role="cp_api")
    expect(error(caps(full, payment(PAY3, 1000, charge_id=CH3, reference=" e2e-ficticio-001 ")
                      + f"; UPDATE platform.billing_charges SET status='PAID',version=version+1 WHERE id='{CH3}'"), "23505", role="cp_api"),
           "um comprovante registrado nao quita duas cobrancas")
    # Encerramento: nao passa de cobranca existente, estado final e imutavel e a assinatura seguinte nao sobrepoe.
    def end(day):
        return f"UPDATE platform.billing_subscriptions SET status='ENDED',ends_on='{day}',ended_at=now(),version=version+1 WHERE id='{SUB}'"
    expect(error(caps(full, end("2030-02-15")), "23514", role="cp_api"), "encerramento nao corta cobranca existente")
    query(caps(full, end("2030-03-01")), role="cp_api")
    expect(error(caps(full, f"UPDATE platform.billing_subscriptions SET status='ACTIVE',ends_on=null,ended_at=null,version=version+1 WHERE id='{SUB}'"),
                 "23514", role="cp_api"), "assinatura encerrada nao volta")
    expect(error(caps(full, charge(CH4, "2030-03-01", "2030-04-01", "2030-03-10")), "23514", role="cp_api"),
           "assinatura encerrada nao cobra depois do encerramento")
    expect(error(caps(full, subscription(start="2030-02-01")), "23P01", role="cp_api"), "nova assinatura nao comeca antes do encerramento")
    query(caps(full, subscription(start="2030-03-01", identifier=SUB2)), role="cp_api")
    # Mesmo com gatilhos desligados pelo dono, o banco nao aceita periodos sobrepostos do mesmo condominio.
    query("SET session_replication_role=replica; " + charge(CH4, "2030-03-01", "2030-04-01", "2030-03-10"))
    expect(error(caps(full, charge("90000000-0000-4000-8000-000000000011", "2030-03-01", "2030-04-01", "2030-03-10", sub=SUB2)), "23P01",
                 role="cp_api"), "periodos de cobranca do condominio nao se sobrepoem")
    expect(error(caps(full, f"UPDATE platform.billing_plans SET name='PLANO RENOMEADO' WHERE id='{PLAN}'"), "23514", role="cp_api"),
           "alteracao de plano avanca a versao")
    expect(value("SET LOCAL app.capabilities='units.manage,memberships.manage,amenities.manage,audit.read'; "
                 "select count(*) from platform.billing_charges", role="cp_api", tenant=TA) == "0",
           "gestao local nao ve o financeiro da plataforma")
    expect(error("select count(*) from platform.billing_charges", "42501", role="cp_worker"), "worker nao le o financeiro")
    mass = "WITH u AS (UPDATE platform.billing_plans SET active=false,version=version+1 RETURNING 1) SELECT count(*) FROM u"
    expect(value(caps("billing.manage", mass), role="cp_api") == "0", "gestao financeira sem leitura nao varre planos")
    expect(value(caps(full, mass), role="cp_api") == "1", "gestao financeira com leitura altera os planos visiveis")
    def trail(action):
        return (f"INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason) "
                f"VALUES('{AA}','{action}','{TA}','ensaio')")
    query(caps("billing.manage", trail("BILLING_PLAN_UPDATED")), role="cp_api")
    expect(error(caps("billing.manage", trail("TENANT_SUSPENDED")), "42501", role="cp_api"), "financeiro nao grava evento de condominio")
    expect(error(caps("billing.manage", trail("BILLINGX_FORJADO")), "42501", role="cp_api"), "prefixo do financeiro e literal")
    expect(error(caps("tenants.manage", trail("BILLING_PAYMENT_RECORDED")), "42501", role="cp_api"), "catalogo nao grava evento financeiro")
    expect(error(caps("billing.read", trail("BILLING_PLAN_UPDATED")), "42501", role="cp_api"), "leitura financeira nao grava trilha")
    query("DELETE FROM platform.platform_audit_log; DELETE FROM platform.billing_payments; DELETE FROM platform.billing_charges; "
          "DELETE FROM platform.billing_subscriptions; DELETE FROM platform.billing_plans")

def visit_checks():
    """V013: convite da unidade, chegada sem convite com primeira decisao valida no prazo e portaria por capacidade."""
    CONC, DEP, RES2, UA2 = ("10000000-0000-4000-8000-0000000000e1", "10000000-0000-4000-8000-0000000000e2",
                            "10000000-0000-4000-8000-0000000000e3", "20000000-0000-4000-8000-0000000000e4")
    V1, V2, V3, V4 = ("70000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(11, 15))
    P1, P2, P3 = ("71000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(11, 14))
    A1, A2, A3, A4 = ("72000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(11, 15))
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{CONC}','ACTIVE'),('{DEP}','ACTIVE'),('{RES2}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UA2}','{TA}','FICTICIA 103');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{CONC}',NULL,'PORTARIA VISITAS','CONCIERGE','ACTIVE'),
                ('{TA}','{DEP}','{UA}','DEPENDENTE VISITAS','DEPENDENT','ACTIVE'),
                ('{TA}','{RES2}','{UA2}','MORADOR 103','RESIDENT','ACTIVE');
    """)
    concierge = "SET LOCAL app.capabilities='concierge.operate'; "
    def visitor(identifier, name="VISITANTE FICTICIO", actor=AA):
        return f"INSERT INTO cp.visitors(id,tenant_id,full_name,created_by) VALUES('{identifier}','{TA}','{name}','{actor}')"
    def permit(identifier, visitor_id, unit=UA, actor=AA, start="clock_timestamp()", end="clock_timestamp()+interval '2 hours'"):
        return (f"INSERT INTO cp.visit_permits(id,tenant_id,visitor_id,unit_id,purpose,created_by,valid_from,valid_until) "
                f"VALUES('{identifier}','{TA}','{visitor_id}','{unit}','VISITA FICTICIA','{actor}',{start},{end})")
    query(visitor(V1) + "; " + permit(P1, V1), tenant=TA, role="cp_api")
    expect(value(f"select state||':'||version from cp.visit_permits where id='{P1}'") == "ACTIVE:0", "morador convida para a propria unidade")
    expect(error(visitor(V2) + "; " + permit(P2, V2, unit=UA2), "42501", tenant=TA, role="cp_api"), "morador nao convida para outra unidade")
    expect(error(visitor(V2) + "; " + permit(P2, V2, start="clock_timestamp()-interval '1 day'"), "23514", tenant=TA, role="cp_api"),
           "convite nao comeca no passado")
    expect(error(visitor(V2) + "; " + permit(P2, V2, end="clock_timestamp()+interval '8 days'"), "23514", tenant=TA, role="cp_api"),
           "convite tem vigencia curta")
    expect(error(visitor(V2, actor=DEP) + "; " + permit(P2, V2, actor=DEP), "42501", tenant=TA, actor=DEP, role="cp_api"),
           "dependente sem permissao nao convida")
    expect(value(f"select count(*) from cp.visit_permits where id='{P1}'", tenant=TA, actor=DEP, role="cp_api") == "0",
           "dependente sem permissao nao ve convites da unidade")
    query(f"UPDATE cp.memberships SET permissions='{{\"visits\":{{\"create\":true}}}}' WHERE account_id='{DEP}'")
    query(visitor(V2, actor=DEP) + "; " + permit(P2, V2, actor=DEP), tenant=TA, actor=DEP, role="cp_api")
    expect(value("select string_agg(id::text,',' order by id) from cp.visit_permits", tenant=TA, actor=DEP, role="cp_api") == P2,
           "dependente com permissao ve somente os convites que criou")
    expect(value(f"select count(*) from cp.visit_permits where id in ('{P1}','{P2}')", tenant=TA, role="cp_api") == "2",
           "morador ve todos os convites da unidade")
    expect(error(permit(P3, V1, unit=UA2, actor=RES2), "42501", tenant=TA, actor=RES2, role="cp_api"),
           "convite nao usa pessoa registrada por outra conta")
    expect(error(permit(P3, V1), "23505", tenant=TA, role="cp_api"), "cada pessoa registrada pertence a um unico convite")
    expect(value(f"select count(*) from cp.visit_permits where id in ('{P1}','{P2}')", tenant=TA, actor=RES2, role="cp_api") == "0",
           "outra unidade nao ve convites")
    expect(value(f"select count(*) from cp.visitors where id in ('{V1}','{V2}')", tenant=TA, actor=RES2, role="cp_api") == "0",
           "outra unidade nao ve visitantes")
    expect(value(f"select count(*) from cp.visit_permits where id in ('{P1}','{P2}')", tenant=TA, actor=CONC, role="cp_api") == "0",
           "portaria sem capacidade publicada nao ve convites")
    expect(value(concierge + f"select count(*) from cp.visit_permits where id in ('{P1}','{P2}')", tenant=TA, actor=CONC, role="cp_api") == "2",
           "portaria com capacidade ve os convites para conferir quem chega")
    def revoke(identifier, actor=AA, reason="'Visita cancelada'"):
        return (f"UPDATE cp.visit_permits SET state='REVOKED',revoked_by='{actor}',revoke_reason={reason},version=version+1 "
                f"WHERE id='{identifier}'")
    expect(value(f"WITH u AS ({revoke(P1, actor=RES2)} RETURNING 1) SELECT count(*) FROM u", tenant=TA, actor=RES2, role="cp_api") == "0",
           "outra unidade nao revoga")
    expect(error(revoke(P1, actor=DEP), "42501", tenant=TA, role="cp_api"), "revogacao registra a propria conta")
    expect(error(revoke(P1, reason="NULL"), "23514", tenant=TA, role="cp_api"), "revogacao exige motivo")
    query(revoke(P2), tenant=TA, role="cp_api")
    expect(value(f"select state||':'||version||':'||(revoked_at is not null) from cp.visit_permits where id='{P2}'") == "REVOKED:1:true",
           "morador revoga convite do dependente da unidade")
    expect(error(f"UPDATE cp.visit_permits SET state='ACTIVE',revoked_by=NULL,revoke_reason=NULL,version=version+1 WHERE id='{P2}'",
                 "23514", tenant=TA, role="cp_api"), "convite revogado nao volta")
    # Chegada sem convite: registrada pela portaria, prazo do banco, primeira decisao valida dentro do prazo.
    def arrival(identifier, visitor_id, actor=CONC, unit=UA, permit_id=None, extra_cols="", extra_vals=""):
        permit_value = f"'{permit_id}'" if permit_id else "NULL"
        return (f"INSERT INTO cp.arrival_requests(id,tenant_id,unit_id,visitor_id,permit_id,created_by{extra_cols}) "
                f"VALUES('{identifier}','{TA}','{unit}','{visitor_id}',{permit_value},'{actor}'{extra_vals})")
    expect(error(visitor(V3, actor=AA) + "; " + arrival(A1, V3, actor=AA), "42501", tenant=TA, role="cp_api"),
           "morador nao registra chegada")
    expect(error(concierge + visitor(V3, actor=CONC) + "; " + arrival(A1, V3, extra_cols=",expires_at", extra_vals=",now()+interval '1 day'"),
                 "42501", tenant=TA, actor=CONC, role="cp_api"), "aplicacao nao escolhe o prazo da chegada")
    expect(error(concierge + visitor(V3, actor=CONC) + "; " + arrival(A1, V3, permit_id=P1, unit=UA2), "23514", tenant=TA, actor=CONC,
                 role="cp_api"), "chegada nao usa convite de outra unidade")
    query(concierge + visitor(V3, actor=CONC) + "; " + arrival(A1, V3) + "; " + visitor(V4, actor=CONC) + "; " + arrival(A2, V4),
          tenant=TA, actor=CONC, role="cp_api")
    expect(value(f"select (expires_at between now()+interval '4 minutes' and now()+interval '6 minutes')::text from cp.arrival_requests where id='{A1}'")
           == "true", "prazo de 5 minutos vem do banco")
    expect(value(f"select count(*) from cp.arrival_requests where id in ('{A1}','{A2}')", tenant=TA, role="cp_api") == "2",
           "unidade ve as chegadas que aguardam sua resposta")
    expect(value(f"select count(*) from cp.arrival_requests where id in ('{A1}','{A2}')", tenant=TA, actor=RES2, role="cp_api") == "0",
           "outra unidade nao ve chegadas")
    expect(value(f"select count(*) from cp.arrival_requests where id in ('{A1}','{A2}')", tenant=TA, actor=DEP, role="cp_api") == "0",
           "dependente sem permissao de decidir nao ve chegadas")
    expect(value(f"select count(*) from cp.visitors where id='{V3}'", tenant=TA, role="cp_api") == "1", "unidade ve quem chegou")
    expect(error(concierge + arrival("72000000-0000-4000-8000-000000000020", V1), "42501", tenant=TA, actor=CONC, role="cp_api"),
           "chegada nao usa pessoa registrada por outra conta")
    expect(error(concierge + arrival("72000000-0000-4000-8000-000000000015", V3), "23505", tenant=TA, actor=CONC, role="cp_api"),
           "cada pessoa registrada pertence a uma unica chegada")
    # Chegada vinculada a convite: o mesmo convite valido, da mesma pessoa e de quem ainda pode convidar.
    V5, V6, V7, V8 = ("70000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(23, 27))
    A5, A6, A7, A8 = ("72000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(16, 20))
    expect(error(concierge + visitor(V5, actor=CONC) + "; " + arrival(A5, V5, permit_id=P2), "23514", tenant=TA, actor=CONC, role="cp_api"),
           "convite revogado nao vincula chegada")
    expect(error(concierge + visitor(V5, name="OUTRA PESSOA", actor=CONC) + "; " + arrival(A5, V5, permit_id=P1), "23514", tenant=TA,
                 actor=CONC, role="cp_api"), "chegada vinculada exige a mesma pessoa do convite")
    query(concierge + visitor(V5, actor=CONC) + "; " + arrival(A5, V5, permit_id=P1), tenant=TA, actor=CONC, role="cp_api")
    expect(value(f"select permit_id from cp.arrival_requests where id='{A5}'") == P1, "chegada vinculada ao convite valido da mesma pessoa")
    query(visitor(V6, actor=RES2) + "; " + permit("71000000-0000-4000-8000-000000000015", V6, unit=UA2, actor=RES2), tenant=TA, actor=RES2,
          role="cp_api")
    query(f"UPDATE cp.memberships SET status='REVOKED' WHERE account_id='{RES2}'")
    expect(value(concierge + "select cp.permit_issuer_active(tenant_id,unit_id,created_by)::text from cp.visit_permits "
                 "where id='71000000-0000-4000-8000-000000000015'", tenant=TA, actor=CONC, role="cp_api") == "false",
           "convite de vinculo encerrado deixa de valer para a portaria")
    expect(error(concierge + visitor(V7, actor=CONC) + "; " + arrival(A7, V7, unit=UA2, permit_id="71000000-0000-4000-8000-000000000015"),
                 "23514", tenant=TA, actor=CONC, role="cp_api"), "convite de vinculo encerrado nao vincula chegada")
    query(f"UPDATE cp.memberships SET status='ACTIVE' WHERE account_id='{RES2}'")
    query(f"SET session_replication_role=replica; UPDATE cp.visit_permits SET valid_from=now()-interval '3 hours',"
          f"valid_until=now()-interval '1 hour' WHERE id='71000000-0000-4000-8000-000000000015'")
    expect(error(concierge + visitor(V8, actor=CONC) + "; " + arrival(A8, V8, unit=UA2, permit_id="71000000-0000-4000-8000-000000000015"),
                 "23514", tenant=TA, actor=CONC, role="cp_api"), "convite vencido nao vincula chegada")
    def decide(identifier, state, actor=AA, source="APP", note="NULL", where=" AND state='WAITING'"):
        return (f"UPDATE cp.arrival_requests SET state='{state}',decided_by='{actor}',decision_source='{source}',decision_note={note},"
                f"version=version+1 WHERE id='{identifier}'{where}")
    expect(error(decide(A1, "APPROVED", source="INTERCOM", note="'Confirmado'"), "42501", tenant=TA, role="cp_api"),
           "morador nao registra confirmacao assistida")
    expect(error(concierge + decide(A1, "APPROVED", actor=CONC), "42501", tenant=TA, actor=CONC, role="cp_api"),
           "portaria nao decide em nome do aplicativo")
    expect(error(concierge + decide(A1, "APPROVED", actor=CONC, source="INTERCOM"), "23514", tenant=TA, actor=CONC, role="cp_api"),
           "confirmacao assistida exige a origem anotada")
    expect(error(decide(A1, "CANCELLED", source="CONCIERGE"), "42501", tenant=TA, role="cp_api"), "morador nao cancela a chegada")
    query(decide(A1, "APPROVED"), tenant=TA, role="cp_api")
    expect(value(f"select state||':'||version||':'||decision_source||':'||(decided_at is not null) from cp.arrival_requests where id='{A1}'")
           == "APPROVED:1:APP:true", "primeira decisao valida pelo aplicativo")
    expect(value(f"WITH u AS ({decide(A1, 'DENIED')} RETURNING 1) SELECT count(*) FROM u", tenant=TA, role="cp_api") == "0",
           "segunda decisao nao encontra chegada aguardando")
    expect(error(decide(A1, "DENIED", where=""), "23514", tenant=TA, role="cp_api"), "chegada decidida nao muda")
    query(concierge + decide(A2, "DENIED", actor=CONC, source="INTERCOM", note="'Morador recusou pelo interfone'"), tenant=TA, actor=CONC,
          role="cp_api")
    expect(value(f"select state||':'||decision_source from cp.arrival_requests where id='{A2}'") == "DENIED:INTERCOM",
           "portaria registra a confirmacao por interfone com a origem")
    # Prazo vencido: resposta atrasada nao decide; a portaria ainda pode encerrar a chegada.
    query(concierge + visitor("70000000-0000-4000-8000-000000000021", actor=CONC) + "; "
          + arrival(A3, "70000000-0000-4000-8000-000000000021"), tenant=TA, actor=CONC, role="cp_api")
    query(f"SET session_replication_role=replica; UPDATE cp.arrival_requests SET expires_at=now()-interval '1 minute',"
          f"created_at=now()-interval '10 minutes' WHERE id='{A3}'")
    expect(error(decide(A3, "APPROVED", where=""), "23514", tenant=TA, role="cp_api"), "resposta depois do prazo nao decide")
    expect(value(concierge + f"WITH u AS ({decide(A3, 'APPROVED', actor=CONC, source='IN_PERSON', note=chr(39)+'Morador confirmou na portaria'+chr(39))} "
                 "RETURNING state) SELECT state FROM u", tenant=TA, actor=CONC, role="cp_api") == "APPROVED",
           "confirmacao assistida vale depois do prazo, com a origem anotada")
    expect(error(concierge + decide(A3, "CANCELLED", actor=CONC, source="CONCIERGE", where=""), "23514", tenant=TA, actor=CONC, role="cp_api"),
           "chegada decidida nao e encerrada")
    # Dependente com permissao de decidir.
    query(f"UPDATE cp.memberships SET permissions='{{\"visits\":{{\"create\":true,\"decide\":true}}}}' WHERE account_id='{DEP}'")
    query(concierge + visitor("70000000-0000-4000-8000-000000000022", actor=CONC) + "; "
          + arrival(A4, "70000000-0000-4000-8000-000000000022"), tenant=TA, actor=CONC, role="cp_api")
    query(decide(A4, "APPROVED", actor=DEP), tenant=TA, actor=DEP, role="cp_api")
    expect(value(f"select state from cp.arrival_requests where id='{A4}'") == "APPROVED", "dependente com permissao decide")
    # Eventos e trilha de visitas.
    foreign_event = (f"INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) "
                     f"VALUES('{TA}','ARRIVAL_REQUESTED','{A1}',9,'{{}}')")
    expect(error(foreign_event, "42501", tenant=TA, actor=RES2, role="cp_api"), "outra unidade nao grava evento da chegada")
    trail = (f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) "
             f"VALUES('{TA}','{CONC}','ARRIVAL_DECIDED','ARRIVAL','{A1}','{ROLL}')")
    expect(error(trail, "42501", tenant=TA, role="cp_api"), "trilha de visitas so em nome da propria conta")
    expect(error("select count(*) from cp.visit_permits", "42501", tenant=TA, role="cp_worker"), "worker nao le convites")
    query(f"DELETE FROM cp.arrival_requests WHERE id in ('{A1}','{A2}','{A3}','{A4}','{A5}'); "
          f"DELETE FROM cp.visit_permits WHERE id in ('{P1}','{P2}','71000000-0000-4000-8000-000000000015'); "
          f"DELETE FROM cp.visitors WHERE tenant_id='{TA}' AND id<>'{VA}'; "
          f"DELETE FROM cp.memberships WHERE account_id in ('{CONC}','{DEP}','{RES2}'); DELETE FROM cp.units WHERE id='{UA2}'; "
          f"DELETE FROM platform.accounts WHERE id in ('{CONC}','{DEP}','{RES2}')")

def access_checks():
    """V014: entrada e saida manuais pela portaria so com autorizacao vigente; correcao por evento adicional."""
    CONC, DEP, RES2, UA2 = ("10000000-0000-4000-8000-0000000000f1", "10000000-0000-4000-8000-0000000000f2",
                            "10000000-0000-4000-8000-0000000000f3", "20000000-0000-4000-8000-0000000000f4")
    V = ["73000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(10, 18)]
    P_NOW, P_LATER, P_REVOKED, P_OTHER = ("74000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(10, 14))
    A_OK, A_WAIT, A_DENIED, A_OLD = ("75000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(10, 14))
    E = ["76000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(10, 22)]
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{CONC}','ACTIVE'),('{DEP}','ACTIVE'),('{RES2}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UA2}','{TA}','FICTICIA 104');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{CONC}',NULL,'PORTARIA ACESSOS','CONCIERGE','ACTIVE'),
                ('{TA}','{DEP}','{UA}','DEPENDENTE ACESSOS','DEPENDENT','ACTIVE'),
                ('{TA}','{RES2}','{UA2}','MORADOR 104','RESIDENT','ACTIVE');
        INSERT INTO cp.visitors(id,tenant_id,full_name,created_by)
          SELECT v,'{TA}','VISITANTE ACESSO','{AA}' FROM unnest(ARRAY['{"','".join(V)}']::uuid[]) v;
        INSERT INTO cp.visit_permits(id,tenant_id,visitor_id,unit_id,created_by,valid_from,valid_until)
          VALUES('{P_NOW}','{TA}','{V[0]}','{UA}','{AA}',clock_timestamp()-interval '5 minutes',clock_timestamp()+interval '2 hours'),
                ('{P_LATER}','{TA}','{V[1]}','{UA}','{AA}',clock_timestamp()+interval '3 hours',clock_timestamp()+interval '5 hours'),
                ('{P_REVOKED}','{TA}','{V[2]}','{UA}','{AA}',clock_timestamp()-interval '5 minutes',clock_timestamp()+interval '2 hours'),
                ('{P_OTHER}','{TA}','{V[6]}','{UA2}','{RES2}',clock_timestamp()-interval '5 minutes',clock_timestamp()+interval '2 hours');
        UPDATE cp.visit_permits SET state='REVOKED',revoked_by='{AA}',revoke_reason='Visita desmarcada',version=1 WHERE id='{P_REVOKED}';
        INSERT INTO cp.arrival_requests(id,tenant_id,unit_id,visitor_id,created_by)
          VALUES('{A_OK}','{TA}','{UA}','{V[3]}','{CONC}'),('{A_WAIT}','{TA}','{UA}','{V[4]}','{CONC}'),
                ('{A_DENIED}','{TA}','{UA}','{V[5]}','{CONC}'),('{A_OLD}','{TA}','{UA}','{V[7]}','{CONC}');
        UPDATE cp.arrival_requests SET state='APPROVED',decided_by='{AA}',decision_source='APP',version=1 WHERE id='{A_OK}';
        UPDATE cp.arrival_requests SET state='DENIED',decided_by='{AA}',decision_source='APP',version=1 WHERE id='{A_DENIED}';
        UPDATE cp.arrival_requests SET state='APPROVED',decided_by='{AA}',decision_source='APP',version=1 WHERE id='{A_OLD}';
    """)
    query(f"SET session_replication_role=replica; UPDATE cp.arrival_requests SET created_at=now()-interval '25 hours' WHERE id='{A_OLD}'")
    concierge = "SET LOCAL app.capabilities='concierge.operate'; "
    def event(identifier, kind="MANUAL_IN", arrival=None, permit=None, entry=None, corrects=None, evidence="DOCUMENT_CHECKED",
              note="NULL", actor=CONC, extra_cols="", extra_vals=""):
        ref = lambda v: f"'{v}'" if v else "NULL"
        return (f"INSERT INTO cp.access_events(id,tenant_id,arrival_id,permit_id,event_type,entry_event_id,corrects_event_id,evidence,note,"
                f"recorded_by{extra_cols}) VALUES('{identifier}','{TA}',{ref(arrival)},{ref(permit)},'{kind}',{ref(entry)},{ref(corrects)},"
                f"'{evidence}',{note},'{actor}'{extra_vals})")
    def as_concierge(statement, state=None):
        if state:
            return error(concierge + statement, state, tenant=TA, actor=CONC, role="cp_api")
        return query(concierge + statement, tenant=TA, actor=CONC, role="cp_api")
    expect(error(event(E[0], permit=P_NOW, actor=AA), "42501", tenant=TA, role="cp_api"), "morador nao registra entrada")
    expect(error(event(E[0], permit=P_NOW), "42501", tenant=TA, actor=CONC, role="cp_api"),
           "portaria sem capacidade publicada nao enxerga a autorizacao (falha fechada no gatilho)")
    # A chegada da propria unidade e visivel ao morador: o gatilho passa e quem recusa e a politica de gravacao.
    expect(error(event(E[0], arrival=A_OK, actor=AA), "new row violates row-level security policy", tenant=TA, role="cp_api"),
           "morador nao registra entrada da propria chegada (politica de gravacao)")
    expect(as_concierge(event(E[0], permit=P_NOW, actor=AA), "42501"), "registro sempre em nome da propria conta")
    expect(as_concierge(event(E[0], arrival=A_WAIT), "23514"), "chegada ainda aguardando nao permite entrada")
    expect(as_concierge(event(E[0], arrival=A_DENIED), "23514"), "chegada recusada nao permite entrada")
    expect(as_concierge(event(E[0], arrival=A_OLD), "23514"), "autorizacao de chegada antiga nao vale para entrada")
    expect(as_concierge(event(E[0], permit=P_LATER), "23514"), "convite antes do inicio nao autoriza entrada")
    expect(as_concierge(event(E[0], permit=P_REVOKED), "23514"), "convite revogado nao autoriza entrada")
    expect(as_concierge(event(E[0], arrival=A_OK, permit=P_NOW), "23514"), "entrada tem exatamente uma autorizacao de base")
    expect(as_concierge(event(E[0]), "23502"), "entrada sem autorizacao de base nao grava")
    expect(as_concierge(event(E[0], permit=P_NOW, evidence="EXIT_OBSERVED"), "23514"), "entrada exige evidencia de conferencia")
    for column, value_sql in (("unit_id", f"'{UA2}'"), ("visitor_id", f"'{V[6]}'"), ("occurred_at", "now()-interval '1 day'"),
                              ("source", "'PROVIDER'"), ("received_at", "now()")):
        expect(as_concierge(event(E[0], permit=P_NOW, extra_cols=f",{column}", extra_vals=f",{value_sql}"), "42501"),
               f"aplicacao nao escolhe {column}")
    as_concierge(event(E[0], permit=P_NOW) + "; " + event(E[1], arrival=A_OK, evidence="RECOGNIZED_BY_UNIT"))
    expect(value(f"select string_agg(unit_id||':'||visitor_id||':'||source||':'||(occurred_at=received_at),',' order by id) "
                 f"from cp.access_events where id in ('{E[0]}','{E[1]}')")
           == f"{UA}:{V[0]}:CONCIERGE:true,{UA}:{V[3]}:CONCIERGE:true", "unidade, pessoa, origem e horario vem da autorizacao e do banco")
    expect(as_concierge(event(E[2], arrival=A_OK), "23505"), "uma entrada por chegada autorizada")
    as_concierge(event(E[2], permit=P_NOW))
    expect(value(f"select count(*) from cp.access_events where permit_id='{P_NOW}'") == "2", "convite em vigor admite nova entrada no periodo")
    as_concierge(event(E[3], permit=P_OTHER))
    # Saida e correcao nao carregam base propria (chegada/convite sao sempre os da entrada de referencia).
    expect(as_concierge(event(E[10], kind="MANUAL_OUT", entry=E[2], arrival=A_OK, evidence="EXIT_OBSERVED"), "23514"),
           "saida nao aponta para outra chegada")
    expect(as_concierge(event(E[10], kind="MANUAL_OUT", entry=E[2], permit=P_OTHER, evidence="EXIT_OBSERVED"), "23514"),
           "saida nao aponta para outro convite")
    expect(as_concierge(event(E[10], kind="CORRECTION", corrects=E[2], arrival=A_OK, evidence="OPERATOR_CORRECTION",
                              note="'Base alheia'"), "23514"), "correcao nao aponta para outra chegada")
    expect(value(f"select unit_id from cp.access_events where id='{E[3]}'") == UA2, "portaria registra entrada para qualquer unidade do condominio")
    query(f"UPDATE cp.memberships SET status='REVOKED' WHERE account_id='{RES2}'")
    expect(as_concierge(event(E[11], permit=P_OTHER), "23514"), "convite de quem perdeu o vinculo nao autoriza entrada")
    query(f"UPDATE cp.memberships SET status='ACTIVE' WHERE account_id='{RES2}'")
    query(f"UPDATE cp.units SET status='INACTIVE' WHERE id='{UA}'")
    expect(as_concierge(event(E[11], arrival=A_OK), "23514"), "chegada autorizada de unidade desativada nao permite entrada")
    query(f"UPDATE cp.units SET status='ACTIVE' WHERE id='{UA}'")
    # Saida e correcao.
    expect(as_concierge(event(E[4], kind="MANUAL_OUT", entry=E[0], evidence="DOCUMENT_CHECKED"), "23514"), "saida exige evidencia de saida")
    as_concierge(event(E[4], kind="MANUAL_OUT", entry=E[0], evidence="EXIT_OBSERVED"))
    expect(value(f"select unit_id||':'||visitor_id from cp.access_events where id='{E[4]}'") == f"{UA}:{V[0]}", "saida herda unidade e pessoa da entrada")
    expect(as_concierge(event(E[5], kind="MANUAL_OUT", entry=E[0], evidence="EXIT_OBSERVED"), "23505"), "uma saida por entrada")
    expect(as_concierge(event(E[5], kind="MANUAL_OUT", entry=E[4], evidence="EXIT_OBSERVED"), "23514"), "saida so de entrada")
    expect(as_concierge(event(E[5], kind="CORRECTION", corrects=E[1], evidence="OPERATOR_CORRECTION"), "23514"), "correcao exige motivo")
    as_concierge(event(E[5], kind="CORRECTION", corrects=E[1], evidence="OPERATOR_CORRECTION", note="'Pessoa nao entrou'"))
    expect(as_concierge(event(E[6], kind="CORRECTION", corrects=E[1], evidence="OPERATOR_CORRECTION", note="'De novo'"), "23505"),
           "uma correcao por evento")
    expect(as_concierge(event(E[6], kind="CORRECTION", corrects=E[5], evidence="OPERATOR_CORRECTION", note="'Corrige a correcao'"), "23514"),
           "correcao nao se corrige")
    expect(as_concierge(event(E[6], kind="MANUAL_OUT", entry=E[1], evidence="EXIT_OBSERVED"), "23514"), "entrada corrigida nao recebe saida")
    as_concierge(event(E[6], arrival=A_OK))
    expect(value(f"select count(*) from cp.access_events where arrival_id='{A_OK}' and event_type='MANUAL_IN'") == "2",
           "entrada corrigida admite novo registro da mesma chegada")
    expect(as_concierge(event(E[7], arrival=A_OK), "23505"), "uma entrada valida por chegada")
    expect(value(f"select count(*) from cp.access_events where id in ('{E[0]}','{E[1]}','{E[4]}','{E[5]}')") == "4", "correcao nao apaga o registro")
    as_concierge(event(E[7], kind="CORRECTION", corrects=E[4], evidence="OPERATOR_CORRECTION", note="'Saida de outra pessoa'"))
    as_concierge(event(E[8], kind="MANUAL_OUT", entry=E[0], evidence="EXIT_OBSERVED"))
    expect(as_concierge(event(E[9], kind="MANUAL_OUT", entry=E[0], evidence="EXIT_OBSERVED"), "23505"),
           "saida corrigida admite nova saida, uma valida por vez")
    expect(error(f"UPDATE cp.access_events SET note='alterado' WHERE id='{E[0]}'", "42501", tenant=TA, actor=CONC, role="cp_api"),
           "registro de acesso nao e alterado")
    expect(error(f"DELETE FROM cp.access_events WHERE id='{E[0]}'", "42501", tenant=TA, actor=CONC, role="cp_api"),
           "registro de acesso nao e apagado")
    # Visibilidade: portaria, unidade adulta e dependente com permissao de decidir.
    expect(value(f"select count(*) from cp.access_events where tenant_id='{TA}'", tenant=TA, role="cp_api") == "8", "unidade ve os registros das proprias visitas")
    expect(value(f"select count(*) from cp.access_events where unit_id='{UA}'", tenant=TA, actor=RES2, role="cp_api") == "0", "outra unidade nao ve registros")
    expect(value(f"select string_agg(id::text,',') from cp.access_events", tenant=TA, actor=RES2, role="cp_api") == E[3],
           "cada unidade ve somente os proprios registros")
    expect(value(f"select count(*) from cp.access_events where tenant_id='{TA}'", tenant=TA, actor=DEP, role="cp_api") == "0",
           "dependente sem permissao de decidir nao ve registros")
    query(f"UPDATE cp.memberships SET permissions='{{\"visits\":{{\"decide\":true}}}}' WHERE account_id='{DEP}'")
    expect(value(f"select count(*) from cp.access_events where tenant_id='{TA}'", tenant=TA, actor=DEP, role="cp_api") == "8",
           "dependente com permissao de decidir ve registros da unidade")
    expect(value(f"select count(*) from cp.access_events where tenant_id='{TA}'", tenant=TA, actor=CONC, role="cp_api") == "0",
           "portaria sem capacidade publicada nao ve registros")
    expect(error("select count(*) from cp.access_events", "42501", tenant=TA, role="cp_worker"), "worker nao le registros de acesso")
    # Revogacao depois da entrada nao apaga a entrada; nova entrada pelo convite revogado e recusada.
    query(f"UPDATE cp.visit_permits SET state='REVOKED',revoked_by='{AA}',revoke_reason='Encerrada',version=version+1 WHERE id='{P_NOW}'",
          tenant=TA, role="cp_api")
    expect(as_concierge(event(E[10], permit=P_NOW), "23514"), "convite revogado depois da entrada nao autoriza nova entrada")
    expect(value(f"select count(*) from cp.access_events where permit_id='{P_NOW}' and event_type='MANUAL_IN'") == "2",
           "entradas anteriores continuam registradas")
    # Eventos e trilha: somente a portaria, do registro da propria conta e com a unidade real do registro.
    def access_event(aggregate, unit):
        return (f"INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) "
                f"VALUES('{TA}','ACCESS_ENTRY_RECORDED','{aggregate}',0,jsonb_build_object('id','{aggregate}','unitId','{unit}'))")
    expect(error(access_event(E[0], UA), "42501", tenant=TA, actor=RES2, role="cp_api"), "outra unidade nao grava evento do registro")
    expect(error(access_event(E[0], UA), "42501", tenant=TA, actor=AA, role="cp_api"), "unidade nao grava evento do proprio registro")
    expect(as_concierge(access_event(E[0], UA2), "42501"), "evento leva a unidade real do registro")
    as_concierge(access_event(E[0], UA))
    expect(value(f"select count(*) from cp.outbox where aggregate_id='{E[0]}'") == "1", "portaria grava o evento do proprio registro")
    def access_trail(actor, resource):
        return (f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) "
                f"VALUES('{TA}','{actor}','ACCESS_ENTRY_RECORDED','ACCESS_EVENT','{resource}','{ROLL}')")
    expect(as_concierge(access_trail(AA, E[0]), "42501"), "trilha de acesso so em nome da propria conta")
    expect(as_concierge(access_trail(CONC, ROLL), "42501"), "trilha de acesso so de registro existente")
    expect(error(access_trail(AA, E[0]), "42501", tenant=TA, actor=AA, role="cp_api"), "unidade nao grava trilha de acesso")
    as_concierge(access_trail(CONC, E[0]))
    query(f"DELETE FROM cp.outbox WHERE event_type LIKE 'ACCESS%'; DELETE FROM cp.audit_log WHERE action LIKE 'ACCESS%'; "
          f"DELETE FROM cp.access_events WHERE tenant_id='{TA}'; DELETE FROM cp.arrival_requests WHERE id in ('{A_OK}','{A_WAIT}','{A_DENIED}','{A_OLD}'); "
          f"DELETE FROM cp.visit_permits WHERE id in ('{P_NOW}','{P_LATER}','{P_REVOKED}','{P_OTHER}'); "
          f"DELETE FROM cp.visitors WHERE id in ('{"','".join(V)}'); "
          f"DELETE FROM cp.memberships WHERE account_id in ('{CONC}','{DEP}','{RES2}'); DELETE FROM cp.units WHERE id='{UA2}'; "
          f"DELETE FROM platform.accounts WHERE id in ('{CONC}','{DEP}','{RES2}')")

def worker_checks():
    """V015: outbox arrendado pelo worker com transicoes conferidas e caixa interna so para destinatario apto."""
    DEP, RES2, UA2 = ("10000000-0000-4000-8000-000000000101", "10000000-0000-4000-8000-000000000102", "20000000-0000-4000-8000-000000000103")
    E1, E2, E3, E4 = ("77000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(1, 5))
    P1, P2 = "78000000-0000-4000-8000-000000000001", "78000000-0000-4000-8000-000000000002"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{DEP}','ACTIVE'),('{RES2}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UA2}','{TA}','FICTICIA 105');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{DEP}','{UA}','DEPENDENTE AVISOS','DEPENDENT','ACTIVE'),('{TA}','{RES2}','{UA2}','MORADOR 105','RESIDENT','ACTIVE');
        INSERT INTO cp.parcels(id,tenant_id,unit_id,quantity,description,storage_location,received_by)
          VALUES('{P1}','{TA}','{UA}',1,'CAIXA AVISO','PORTARIA','{AA}'),('{P2}','{TA}','{UA}',1,'CAIXA AVISO 2','PORTARIA','{AA}');
        INSERT INTO cp.outbox(id,tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES
          ('{E1}','{TA}','PARCEL_RECEIVED','{P1}',0,jsonb_build_object('parcelId','{P1}','unitId','{UA}')),
          ('{E2}','{TA}','PARCEL_RECEIVED','{P2}',0,jsonb_build_object('parcelId','{P2}','unitId','{UA}')),
          ('{E3}','{TA}','RESERVATION_CONFIRMED','{ROLL}',1,'{{}}'),
          ('{E4}','{TB}','PARCEL_RECEIVED','{P1}',0,jsonb_build_object('parcelId','{P1}','unitId','{UB}'));
    """)
    def lease(event, until="clock_timestamp()+interval '60 seconds'"):
        return f"UPDATE cp.outbox SET state='LEASED',attempts=attempts+1,lease_until={until} WHERE id='{event}'"
    def notify(event, account, kind="PARCEL_RECEIVED", resource=P1, unit=UA):
        return (f"INSERT INTO cp.notifications(tenant_id,account_id,unit_id,outbox_id,kind,resource_id) "
                f"VALUES('{TA}','{account}','{unit}','{event}','{kind}','{resource}')")
    expect(error(f"INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload,state) "
                 f"VALUES('{TA}','TEST','{ROLL}',9,'{{}}','DELIVERED')", "42501", tenant=TA, role="cp_api"), "aplicacao cria somente tarefa pendente")
    expect(value(f"WITH u AS (UPDATE cp.outbox SET state='DELIVERED' WHERE id='{E1}' RETURNING 1) SELECT count(*) FROM u", tenant=TA, role="cp_api") == "0",
           "aplicacao nao processa tarefa (V032: so a operacao da plataforma altera, e so devolvendo para a fila)")
    expect(error(lease(E1, until="clock_timestamp()-interval '1 second'"), "23514", tenant=TA, role="cp_worker"), "arrendamento exige prazo futuro")
    expect(error(lease(E1, until="clock_timestamp()+interval '1 hour'"), "23514", tenant=TA, role="cp_worker"), "arrendamento tem prazo curto")
    expect(error(f"UPDATE cp.outbox SET payload='{{}}' WHERE id='{E1}'", "42501", tenant=TA, role="cp_worker"), "worker nao muda o conteudo da tarefa")
    expect(error(notify(E1, AA), "42501", tenant=TA, role="cp_worker"), "aviso so para tarefa arrendada")
    query(lease(E1), tenant=TA, role="cp_worker")
    expect(value(f"select state||':'||attempts from cp.outbox where id='{E1}'") == "LEASED:1", "worker arrenda a tarefa com tentativa contada")
    expect(value(f"select count(*) from (select id from cp.outbox where id='{E1}' and state='LEASED' and attempts=1 "
                 "for no key update skip locked) h", tenant=TA, role="cp_worker") == "1",
           "worker trava a propria tarefa arrendada antes do efeito")
    expect(value(f"select count(*) from (select id from cp.outbox where id='{E1}' and state='LEASED' and attempts=0 "
                 "for no key update skip locked) h", tenant=TA, role="cp_worker") == "0",
           "tentativa antiga nao trava a tarefa arrendada de novo")
    expect(value(f"WITH u AS ({lease(E1)} RETURNING 1) SELECT count(*) FROM u", tenant=TB, role="cp_worker") == "0",
           "worker de outro condominio nao alcanca a tarefa")
    expect(error(lease(E1), "23514", tenant=TA, role="cp_worker"), "arrendamento vigente nao e tomado por outro worker")
    expect(error(notify(E1, AA, kind="ARRIVAL_WAITING"), "42501", tenant=TA, role="cp_worker"), "tipo do aviso corresponde ao evento")
    expect(error(notify(E1, AA, unit=UA2), "42501", tenant=TA, role="cp_worker"), "aviso para a unidade do evento")
    expect(error(notify(E1, RES2), "42501", tenant=TA, role="cp_worker"), "morador de outra unidade nao recebe o aviso")
    forged = "77000000-0000-4000-8000-000000000005"
    query(f"INSERT INTO cp.outbox(id,tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES('{forged}','{TA}','PARCEL_RECEIVED',"
          f"'{P1}',7,jsonb_build_object('parcelId','{P1}','unitId','{UA2}'))")
    query(lease(forged), tenant=TA, role="cp_worker")
    expect(error(notify(forged, RES2, unit=UA2), "42501", tenant=TA, role="cp_worker"),
           "payload com unidade diferente da encomenda nao gera aviso")
    query(f"DELETE FROM cp.outbox WHERE id='{forged}'")
    expect(error(notify(E1, DEP), "42501", tenant=TA, role="cp_worker"), "dependente sem permissao de encomendas nao recebe o aviso")
    query(notify(E1, AA), tenant=TA, role="cp_worker")
    expect(error(notify(E1, AA), "23505", tenant=TA, role="cp_worker"), "um aviso por tarefa, destinatario e canal")
    repeated = query(notify(E1, AA) + " ON CONFLICT (tenant_id,outbox_id,account_id,channel) DO NOTHING", tenant=TA, role="cp_worker")
    expect(repeated.returncode == 0 and value(f"select count(*) from cp.notifications where outbox_id='{E1}'") == "1",
           "repeticao do efeito nao duplica o aviso")
    expect(value(f"WITH i AS ({notify(E1, AA)} ON CONFLICT (tenant_id,outbox_id,account_id,channel) DO NOTHING RETURNING 1) "
                 "SELECT count(*) FROM i", tenant=TA, role="cp_worker") == "0",
           "repeticao no formato do worker devolve zero avisos novos")
    expect(error("select kind from cp.notifications", "42501", tenant=TA, role="cp_worker"), "worker nao le o tipo do aviso")
    expect(error("select resource_id from cp.notifications", "42501", tenant=TA, role="cp_worker"), "worker nao le o registro do aviso")
    expect(error("select read_at from cp.notifications", "42501", tenant=TA, role="cp_worker"), "worker nao le a leitura do aviso")
    query(f"UPDATE cp.outbox SET state='DELIVERED' WHERE id='{E1}'", tenant=TA, role="cp_worker")
    expect(value(f"select state||':'||(processed_at is not null)||':'||(lease_until is null) from cp.outbox where id='{E1}'") == "DELIVERED:true:true",
           "conclusao com horario do banco")
    expect(error(f"UPDATE cp.outbox SET state='PENDING',available_at=now()+interval '1 minute',last_error='x' WHERE id='{E1}'", "23514",
                 tenant=TA, role="cp_worker"), "tarefa concluida e final")
    # Nova tentativa e falha visivel.
    query(lease(E2), tenant=TA, role="cp_worker")
    expect(error(f"UPDATE cp.outbox SET state='PENDING',available_at=clock_timestamp()-interval '1 second',last_error='Falha temporaria' "
                 f"WHERE id='{E2}'", "23514", tenant=TA, role="cp_worker"), "nova tentativa tem horario futuro")
    expect(error(f"UPDATE cp.outbox SET state='PENDING',available_at=clock_timestamp()+interval '10 seconds' WHERE id='{E2}'", "23514",
                 tenant=TA, role="cp_worker"), "nova tentativa registra o erro")
    query(f"UPDATE cp.outbox SET state='PENDING',available_at=clock_timestamp()+interval '10 seconds',last_error='Falha temporaria' WHERE id='{E2}'",
          tenant=TA, role="cp_worker")
    expect(value(f"select state||':'||attempts||':'||last_error from cp.outbox where id='{E2}'") == "PENDING:1:Falha temporaria",
           "falha volta para a fila com tentativa e erro")
    expect(error(lease(E2), "23514", tenant=TA, role="cp_worker"), "tarefa em espera nao e arrendada antes do horario")
    query(f"SET session_replication_role=replica; UPDATE cp.outbox SET available_at=now()-interval '1 second' WHERE id='{E2}'")
    query(lease(E2), tenant=TA, role="cp_worker")
    expect(error(f"UPDATE cp.outbox SET state='FAILED' WHERE id='{E2}'", "23514", tenant=TA, role="cp_worker"), "falha exige erro saneado")
    query(f"UPDATE cp.outbox SET state='FAILED',last_error='Destinatarios indisponiveis' WHERE id='{E2}'", tenant=TA, role="cp_worker")
    expect(value(f"select state||':'||attempts||':'||(processed_at is not null) from cp.outbox where id='{E2}'") == "FAILED:2:true",
           "falha definitiva fica visivel")
    expect(error(f"UPDATE cp.outbox SET state='PENDING',available_at=now()+interval '1 minute',last_error='x' WHERE id='{E2}'", "23514",
                 tenant=TA, role="cp_worker"), "falha nao volta sozinha para a fila")
    query(lease(E3), tenant=TA, role="cp_worker")
    query(f"UPDATE cp.outbox SET state='SKIPPED' WHERE id='{E3}'", tenant=TA, role="cp_worker")
    expect(value(f"select state from cp.outbox where id='{E3}'") == "SKIPPED", "evento sem consumidor fica ignorado, nao entregue")
    # Caixa interna do destinatario.
    expect(value(f"select count(*) from cp.notifications where outbox_id='{E1}'", tenant=TA, role="cp_api") == "1", "destinatario ve o proprio aviso")
    expect(value(f"select count(*) from cp.notifications where outbox_id='{E1}'", tenant=TA, actor=RES2, role="cp_api") == "0",
           "outra unidade nao ve o aviso")
    expect(value(f"WITH u AS (UPDATE cp.notifications SET state='READ_IN_APP' WHERE outbox_id='{E1}' RETURNING 1) SELECT count(*) FROM u",
                 tenant=TA, actor=RES2, role="cp_api") == "0", "outra pessoa nao abre o aviso")
    query(f"UPDATE cp.notifications SET state='READ_IN_APP' WHERE outbox_id='{E1}'", tenant=TA, role="cp_api")
    expect(value(f"select state||':'||(read_at is not null) from cp.notifications where outbox_id='{E1}'") == "READ_IN_APP:true",
           "abertura autenticada registra a leitura com horario do banco")
    expect(error(f"UPDATE cp.notifications SET state='READ_IN_APP' WHERE outbox_id='{E1}'", "23514", tenant=TA, role="cp_api"),
           "aviso aberto nao muda mais")
    expect(error(f"UPDATE cp.notifications SET kind='ARRIVAL_WAITING' WHERE outbox_id='{E1}'", "42501", tenant=TA, role="cp_api"),
           "destinatario so altera a abertura")
    expect(error(notify(E1, AA), "42501", tenant=TA, role="cp_api"), "aplicacao nao cria aviso")
    query(f"UPDATE cp.memberships SET permissions='{{\"parcels\":{{\"view\":true}}}}' WHERE account_id='{DEP}'")
    query(f"SET session_replication_role=replica; INSERT INTO cp.notifications(tenant_id,account_id,unit_id,outbox_id,kind,resource_id) "
          f"VALUES('{TA}','{DEP}','{UA}','{E1}','PARCEL_RECEIVED','{P1}')")
    expect(value(f"select count(*) from cp.notifications where account_id='{DEP}'", tenant=TA, actor=DEP, role="cp_api") == "1",
           "dependente com permissao ve o aviso")
    query(f"UPDATE cp.memberships SET permissions='{{}}' WHERE account_id='{DEP}'")
    expect(value(f"select count(*) from cp.notifications where account_id='{DEP}'", tenant=TA, actor=DEP, role="cp_api") == "0",
           "sem a permissao o aviso deixa de aparecer")
    query("UPDATE cp.notifications SET state='READ_IN_APP'", tenant=TA, actor=DEP, role="cp_api")
    expect(value(f"select state from cp.notifications where account_id='{DEP}'") == "AVAILABLE",
           "sem a permissao o aviso tambem nao e aberto")
    expect(value(f"select count(*) from cp.memberships where tenant_id='{TB}'", tenant=TA, role="cp_worker") == "0",
           "worker nao le vinculo de outro condominio")
    expect(error("select description from cp.parcels", "42501", tenant=TA, role="cp_worker"), "worker nao le descricao da encomenda")
    expect(error("select storage_location from cp.parcels", "42501", tenant=TA, role="cp_worker"), "worker nao le local de guarda")
    expect(error("select full_name from cp.visitors", "42501", tenant=TA, role="cp_worker"), "worker nao le nome de visitante")
    expect(error("select visitor_id from cp.arrival_requests", "42501", tenant=TA, role="cp_worker"), "worker nao le quem chegou")
    expect(value(f"select status from cp.parcels where id='{PA}'", tenant=TA, role="cp_worker") in ("RECEIVED", "DELIVERED"),
           "worker le so a situacao da encomenda do condominio em contexto")
    expect(value(f"select count(*) from cp.parcels where id='{PA}'", tenant=TB, role="cp_worker") == "0",
           "worker de outro condominio nao ve a encomenda")
    expect(value(f"select count(*) from cp.arrival_requests where id='{ARR}'", tenant=TB, role="cp_worker") == "0",
           "worker de outro condominio nao ve a chegada")
    expect(value(f"select count(*) from cp.arrival_requests where id='{ARR}'", tenant=TA, role="cp_worker") == "1",
           "worker ve a situacao da chegada do condominio em contexto")
    query(f"DELETE FROM cp.notifications WHERE tenant_id in ('{TA}','{TB}'); DELETE FROM cp.outbox WHERE id in ('{E1}','{E2}','{E3}','{E4}'); "
          f"DELETE FROM cp.parcels WHERE id in ('{P1}','{P2}'); "
          f"DELETE FROM cp.memberships WHERE account_id in ('{DEP}','{RES2}'); DELETE FROM cp.units WHERE id='{UA2}'; "
          f"DELETE FROM platform.accounts WHERE id in ('{DEP}','{RES2}')")

def announcement_checks():
    """V016: avisos da gestao com rascunho, publico-alvo, publicacao, arquivamento e abertura autenticada."""
    MGR, R3, R4, OWN = ("10000000-0000-4000-8000-0000000000a1", "10000000-0000-4000-8000-0000000000a2",
                        "10000000-0000-4000-8000-0000000000a3", "10000000-0000-4000-8000-0000000000a4")
    BK, UA3, UA4 = "21000000-0000-4000-8000-0000000000a1", "20000000-0000-4000-8000-0000000000a3", "20000000-0000-4000-8000-0000000000a4"
    N1, N2, N3, N4 = ("79000000-0000-4000-8000-0000000000" + str(i).zfill(2) for i in range(1, 5))
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MGR}','ACTIVE'),('{R3}','ACTIVE'),('{R4}','ACTIVE'),('{OWN}','ACTIVE');
        INSERT INTO cp.blocks(id,tenant_id,name) VALUES('{BK}','{TA}','BLOCO AVISOS');
        INSERT INTO cp.units(id,tenant_id,label,block_id) VALUES('{UA3}','{TA}','FICTICIA 301','{BK}'),('{UA4}','{TA}','FICTICIA 401',NULL);
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{MGR}',NULL,'GESTAO AVISOS','MANAGER','ACTIVE'),('{TA}','{R3}','{UA3}','MORADOR 301','RESIDENT','ACTIVE'),
                ('{TA}','{R4}','{UA4}','MORADOR 401','RESIDENT','ACTIVE'),('{TA}','{OWN}','{UA3}','PROPRIETARIO 301','OWNER','ACTIVE');
    """)
    publisher = "announcements.publish"
    def draft(identifier, audience="TENANT", ids="'{}'", title="Aviso ficticio"):
        return (f"INSERT INTO cp.announcements(id,tenant_id,title,body,audience,audience_ids,author_id) "
                f"VALUES('{identifier}','{TA}','{title}','Texto ficticio do aviso','{audience}',{ids}::uuid[],'{MGR}')")
    def as_manager(statement, state=None):
        if state:
            return error(capable(publisher, statement), state, tenant=TA, actor=MGR, role="cp_api")
        return query(capable(publisher, statement), tenant=TA, actor=MGR, role="cp_api")
    def visible(identifier, actor, tenant=TA):
        return value(f"select count(*) from cp.announcements where id='{identifier}'", tenant=tenant, actor=actor, role="cp_api")
    def change(identifier, assignments, version):
        return f"UPDATE cp.announcements SET {assignments},version={version + 1} WHERE id='{identifier}'"
    expect(error(draft(N1), "42501", tenant=TA, actor=MGR, role="cp_api"), "sem capacidade publicada a gestao nao cria aviso")
    expect(error(capable("units.manage", draft(N1)), "42501", tenant=TA, actor=MGR, role="cp_api"), "outra capacidade nao cria aviso")
    expect(as_manager(draft(N1).replace(f"'{MGR}')", f"'{AA}')"), "42501"), "aviso sempre em nome da propria conta")
    expect(as_manager(draft(N1).replace("author_id)", "author_id,state)").replace(f"'{MGR}')", f"'{MGR}','PUBLISHED')"), "42501"),
           "aplicacao nao cria aviso ja publicado")
    as_manager(draft(N1))
    expect(value(f"select state||':'||version||':'||(published_at is null) from cp.announcements where id='{N1}'") == "DRAFT:0:true",
           "aviso nasce rascunho")
    expect(visible(N1, AA) == "0", "rascunho nao aparece para a unidade (AC-029)")
    expect(error(f"INSERT INTO cp.announcement_reads(tenant_id,announcement_id,account_id) VALUES('{TA}','{N1}','{AA}')", "42501",
                 tenant=TA, actor=AA, role="cp_api"), "rascunho nao recebe abertura")
    expect(as_manager(change(N1, "title='Titulo alterado'", 5), "23514"), "edicao exige a versao lida")
    as_manager(change(N1, "title='Assembleia ficticia'", 0))
    expect(as_manager(change(N1, "audience='UNITS'", 1), "23514"), "publico por unidade exige alvo")
    expect(as_manager(change(N1, f"audience='UNITS',audience_ids=ARRAY['{UB}']::uuid[]", 1), "23503"), "alvo de outro condominio e recusado")
    expect(as_manager(change(N1, f"audience='UNITS',audience_ids=ARRAY['{UA3}','{UA3}']::uuid[]", 1), "23514"), "alvo repetido e recusado")
    expect(as_manager(change(N1, f"audience_ids=ARRAY['{UA3}']::uuid[]", 1), "23514"), "condominio inteiro nao leva lista")
    expect(as_manager(change(N1, "published_at=now()", 1), "42501"), "aplicacao nao escolhe horario de publicacao")
    as_manager(change(N1, "state='PUBLISHED'", 1))
    expect(value(f"select state||':'||version||':'||(published_by='{MGR}')||':'||(published_at is not null) from cp.announcements where id='{N1}'")
           == "PUBLISHED:2:true:true", "publicacao com autoria e horario do banco")
    expect(as_manager(change(N1, "body='Texto trocado'", 2), "23514"), "publicado nao muda de texto")
    expect(as_manager(change(N1, "state='DRAFT'", 2), "23514"), "publicado nao volta a rascunho")
    expect(visible(N1, AA) == "1" and visible(N1, R3) == "1" and visible(N1, OWN) == "1", "condominio inteiro ve o aviso publicado")
    expect(visible(N1, AB, tenant=TB) == "0", "outro condominio nao ve o aviso")
    expect(visible(N1, MGR) == "0", "gestao sem vinculo de unidade e sem capacidade publicada nao ve como morador")
    # Abertura autenticada: uma por conta, horario do banco, nunca em nome de outra pessoa.
    opening = f"INSERT INTO cp.announcement_reads(tenant_id,announcement_id,account_id) VALUES('{TA}','{N1}','{AA}')"
    expect(error(opening.replace(f"'{AA}')", f"'{R3}')"), "42501", tenant=TA, actor=AA, role="cp_api"), "abertura so em nome da propria conta")
    expect(error(opening.replace("account_id)", "account_id,read_at)").replace(f"'{AA}')", f"'{AA}',now()-interval '1 day')"), "42501",
                 tenant=TA, actor=AA, role="cp_api"), "aplicacao nao escolhe o horario de abertura")
    query(opening, tenant=TA, actor=AA, role="cp_api")
    expect(error(opening, "23505", tenant=TA, actor=AA, role="cp_api"), "uma abertura por conta e aviso")
    query(f"INSERT INTO cp.announcement_reads(tenant_id,announcement_id,account_id) VALUES('{TA}','{N1}','{R3}')", tenant=TA, actor=R3, role="cp_api")
    expect(value(f"select count(*) from cp.announcement_reads where announcement_id='{N1}'", tenant=TA, actor=AA, role="cp_api") == "1",
           "cada pessoa ve so a propria abertura")
    expect(value(capable(publisher, f"select count(*) from cp.announcement_reads where announcement_id='{N1}'"), tenant=TA, actor=MGR,
                 role="cp_api") == "2", "gestao conta as aberturas do condominio")
    expect(error(f"UPDATE cp.announcement_reads SET read_at=now() WHERE account_id='{AA}'", "42501", tenant=TA, actor=AA, role="cp_api"),
           "abertura nao e alterada")
    expect(error(capable(publisher, f"DELETE FROM cp.announcement_reads WHERE announcement_id='{N1}'"), "42501", tenant=TA, actor=MGR,
                 role="cp_api"), "abertura nao e apagada")
    # Publico por unidade e por bloco.
    as_manager(draft(N2, audience="UNITS", ids=f"ARRAY['{UA3}']") + "; " + change(N2, "state='PUBLISHED'", 0))
    expect(visible(N2, R3) == "1" and visible(N2, OWN) == "1", "unidade indicada ve o aviso (morador e proprietario)")
    expect(visible(N2, R4) == "0" and visible(N2, AA) == "0", "outras unidades nao veem aviso dirigido")
    expect(error(f"INSERT INTO cp.announcement_reads(tenant_id,announcement_id,account_id) VALUES('{TA}','{N2}','{R4}')", "42501",
                 tenant=TA, actor=R4, role="cp_api"), "fora do publico nao registra abertura")
    as_manager(draft(N3, audience="BLOCKS", ids=f"ARRAY['{BK}']") + "; " + change(N3, "state='PUBLISHED'", 0))
    expect(visible(N3, R3) == "1" and visible(N3, R4) == "0", "aviso por bloco chega so a unidades do bloco")
    query(f"UPDATE cp.memberships SET status='REVOKED' WHERE account_id='{R3}'")
    expect(visible(N3, R3) == "0", "vinculo encerrado deixa de ver o aviso")
    query(f"UPDATE cp.memberships SET status='ACTIVE' WHERE account_id='{R3}'")
    query(f"UPDATE cp.units SET status='INACTIVE' WHERE id='{UA3}'")
    expect(visible(N3, R3) == "0", "unidade inativa deixa de ver o aviso")
    query(f"UPDATE cp.units SET status='ACTIVE' WHERE id='{UA3}'")
    # Arquivamento e descarte.
    as_manager(change(N1, "state='ARCHIVED'", 2))
    expect(value(f"select state||':'||(archived_by='{MGR}')||':'||(published_at is not null) from cp.announcements where id='{N1}'")
           == "ARCHIVED:true:true", "arquivamento guarda autoria e a publicacao anterior")
    expect(visible(N1, AA) == "0", "arquivado sai da unidade")
    expect(value(f"select count(*) from cp.announcement_reads where announcement_id='{N1}'") == "2", "aberturas continuam registradas")
    expect(as_manager(change(N1, "state='PUBLISHED'", 3), "23514"), "arquivado nao volta")
    as_manager(draft(N4) + "; " + change(N4, "state='ARCHIVED'", 0))
    expect(value(f"select state||':'||(published_at is null) from cp.announcements where id='{N4}'") == "ARCHIVED:true", "rascunho descartado nunca publicado")
    expect(error(capable(publisher, f"DELETE FROM cp.announcements WHERE id='{N4}'"), "42501", tenant=TA, actor=MGR, role="cp_api"),
           "aviso nao e apagado")
    # Eventos e trilha so pela gestao, em nome da propria conta e de aviso existente.
    event = (f"INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) "
             f"VALUES('{TA}','ANNOUNCEMENT_PUBLISHED','{N2}',1,'{{}}')")
    expect(error(event, "42501", tenant=TA, actor=AA, role="cp_api"), "morador nao grava evento de aviso")
    expect(as_manager(event.replace(f"'{N2}'", f"'{ROLL}'"), "42501"), "evento so de aviso existente")
    as_manager(event)
    trail = (f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) "
             f"VALUES('{TA}','{{actor}}','ANNOUNCEMENT_PUBLISHED','ANNOUNCEMENT','{N2}','{ROLL}')")
    expect(as_manager(trail.format(actor=AA), "42501"), "trilha de aviso so em nome da propria conta")
    expect(error(trail.format(actor=AA), "42501", tenant=TA, actor=AA, role="cp_api"), "morador nao grava trilha de aviso")
    as_manager(trail.format(actor=MGR))
    expect(error("select count(*) from cp.announcements", "42501", tenant=TA, role="cp_worker"), "worker nao le avisos")
    query(f"DELETE FROM cp.outbox WHERE event_type LIKE 'ANNOUNCEMENT%'; DELETE FROM cp.audit_log WHERE action LIKE 'ANNOUNCEMENT%'; "
          f"DELETE FROM cp.announcement_reads WHERE tenant_id='{TA}'; DELETE FROM cp.announcements WHERE tenant_id='{TA}'; "
          f"DELETE FROM cp.memberships WHERE account_id in ('{MGR}','{R3}','{R4}','{OWN}'); DELETE FROM cp.units WHERE id in ('{UA3}','{UA4}'); "
          f"DELETE FROM cp.blocks WHERE id='{BK}'; DELETE FROM platform.accounts WHERE id in ('{MGR}','{R3}','{R4}','{OWN}')")

def request_checks():
    """V017: pedidos da unidade com conversa vinculada; comentario interno so para a equipe; fechamento preserva historico."""
    MGR, RES5, R6, DEP6, CONC = ("10000000-0000-4000-8000-0000000000b1", "10000000-0000-4000-8000-0000000000b2",
                                 "10000000-0000-4000-8000-0000000000b3", "10000000-0000-4000-8000-0000000000b4",
                                 "10000000-0000-4000-8000-0000000000b5")
    UA6 = "20000000-0000-4000-8000-0000000000b6"
    Q1, Q2 = "7a000000-0000-4000-8000-000000000001", "7a000000-0000-4000-8000-000000000002"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MGR}','ACTIVE'),('{RES5}','ACTIVE'),('{R6}','ACTIVE'),('{DEP6}','ACTIVE'),('{CONC}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UA6}','{TA}','FICTICIA 601');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{MGR}',NULL,'GESTAO PEDIDOS','MANAGER','ACTIVE'),('{TA}','{RES5}','{UA}','RESPONSAVEL 101','RESPONSIBLE','ACTIVE'),
                ('{TA}','{R6}','{UA6}','MORADOR 601','RESIDENT','ACTIVE'),('{TA}','{DEP6}','{UA}','DEPENDENTE 101','DEPENDENT','ACTIVE'),
                ('{TA}','{CONC}',NULL,'PORTARIA PEDIDOS','CONCIERGE','ACTIVE');
    """)
    staff = "requests.manage"
    def open_request(identifier, actor=AA, unit=UA, extra_cols="", extra_vals=""):
        return (f"INSERT INTO cp.service_requests(id,tenant_id,unit_id,requester_id,category,title,description{extra_cols}) "
                f"VALUES('{identifier}','{TA}','{unit}','{actor}','MAINTENANCE','Vazamento ficticio','Agua no teto do banheiro{extra_vals}')")
    def as_staff(statement, state=None):
        if state:
            return error(capable(staff, statement), state, tenant=TA, actor=MGR, role="cp_api")
        return query(capable(staff, statement), tenant=TA, actor=MGR, role="cp_api")
    def visible(identifier, actor):
        return value(f"select count(*) from cp.service_requests where id='{identifier}'", tenant=TA, actor=actor, role="cp_api")
    def change(identifier, assignments, version):
        return f"UPDATE cp.service_requests SET {assignments},version={version + 1} WHERE id='{identifier}'"
    def message(identifier, request, actor, role="UNIT", visibility="RESIDENT", body="Mensagem ficticia"):
        return (f"INSERT INTO cp.request_messages(id,tenant_id,request_id,author_id,author_role,body,visibility) "
                f"VALUES('{identifier}','{TA}','{request}','{actor}','{role}','{body}','{visibility}')")
    expect(error(open_request(Q1, actor=RES5), "42501", tenant=TA, actor=AA, role="cp_api"), "pedido sempre em nome da propria conta")
    expect(error(open_request(Q1, actor=R6), "42501", tenant=TA, actor=R6, role="cp_api"), "outra unidade nao abre pedido para a unidade")
    expect(error(open_request(Q1, actor=DEP6), "42501", tenant=TA, actor=DEP6, role="cp_api"), "dependente sem permissao nao abre pedido")
    expect(error(open_request(Q1, extra_cols=",state", extra_vals="','RESOLVED"), "42501", tenant=TA, actor=AA, role="cp_api"),
           "unidade nao escolhe o andamento")
    expect(error(open_request(Q1, extra_cols=",requester_name", extra_vals="','OUTRO NOME"), "42501", tenant=TA, actor=AA, role="cp_api"),
           "unidade nao escolhe o nome de quem abriu")
    query(open_request(Q1), tenant=TA, actor=AA, role="cp_api")
    expect(value(f"select requester_name from cp.service_requests where id='{Q1}'") == "PESSOA FICTICIA A",
           "nome de quem abriu vem do proprio vinculo")
    expect(value(f"select state||':'||version||':'||(assignee_membership_id is null) from cp.service_requests where id='{Q1}'") == "OPEN:0:true",
           "pedido nasce aberto, sem responsavel")
    expect(visible(Q1, AA) == "1" and visible(Q1, RES5) == "1", "adultos da unidade acompanham o pedido")
    expect(visible(Q1, R6) == "0" and visible(Q1, DEP6) == "0", "outra unidade e dependente sem permissao nao veem o pedido")
    expect(value(f"select count(*) from cp.service_requests where id='{Q1}'", tenant=TB, actor=AB, role="cp_api") == "0", "outro condominio nao ve o pedido")
    expect(value(capable(staff, f"select count(*) from cp.service_requests where id='{Q1}'"), tenant=TA, actor=MGR, role="cp_api") == "1",
           "gestao com capacidade ve o pedido")
    triage_by_unit = change(Q1, "state='TRIAGED'", 0)
    expect(value(f"WITH u AS ({triage_by_unit} RETURNING 1) SELECT count(*) FROM u", tenant=TA, actor=AA, role="cp_api") == "0",
           "unidade nao muda o andamento")
    expect(as_staff(f"UPDATE cp.service_requests SET title='Outro titulo',version=1 WHERE id='{Q1}'", "42501"), "gestao nao altera o conteudo")
    expect(as_staff(change(Q1, "state='RESOLVED',resolution='Resolvido'", 0), "23514"), "aberto nao vai direto a resolvido")
    expect(as_staff(change(Q1, "state='TRIAGED'", 4), "23514"), "andamento pela versao lida")
    resident_membership = value(f"SELECT id FROM cp.memberships WHERE account_id='{R6}'")
    concierge_membership = value(f"SELECT id FROM cp.memberships WHERE account_id='{CONC}'")
    team = value(f"SELECT count(*) FROM cp.memberships WHERE tenant_id='{TA}' AND role IN ('MANAGER','CONCIERGE') AND status='ACTIVE'")
    expect(value(capable(staff, "select count(*) from cp.memberships where role in ('MANAGER','CONCIERGE')"), tenant=TA, actor=MGR,
                 role="cp_api") == team, "gestao de pedidos ve a equipe vigente para escolher o responsavel")
    expect(value(capable(staff, f"select count(*) from cp.memberships where account_id='{R6}'"), tenant=TA, actor=MGR, role="cp_api") == "0",
           "gestao de pedidos nao ve vinculo residencial")
    expect(as_staff(change(Q1, f"state='TRIAGED',assignee_membership_id='{resident_membership}'", 0), "23514"), "responsavel e da equipe")
    as_staff(change(Q1, f"state='TRIAGED',assignee_membership_id='{concierge_membership}',due_on=current_date+3", 0))
    expect(value(f"select state||':'||version||':'||(due_on is not null) from cp.service_requests where id='{Q1}'") == "TRIAGED:1:true",
           "triagem com responsavel e prazo comunicado")
    # Conversa vinculada ao pedido.
    query(message("7b000000-0000-4000-8000-000000000001", Q1, AA), tenant=TA, actor=AA, role="cp_api")
    expect(error(message("7b000000-0000-4000-8000-000000000002", Q1, AA, visibility="INTERNAL"), "42501", tenant=TA, actor=AA, role="cp_api"),
           "unidade nao escreve comentario interno")
    expect(error(message("7b000000-0000-4000-8000-000000000002", Q1, AA, role="STAFF"), "42501", tenant=TA, actor=AA, role="cp_api"),
           "unidade nao escreve como equipe")
    expect(error(message("7b000000-0000-4000-8000-000000000002", Q1, RES5), "42501", tenant=TA, actor=AA, role="cp_api"),
           "mensagem sempre em nome da propria conta")
    expect(error(message("7b000000-0000-4000-8000-000000000002", Q1, R6), "42501", tenant=TA, actor=R6, role="cp_api"),
           "outra unidade nao escreve no pedido")
    as_staff(message("7b000000-0000-4000-8000-000000000003", Q1, MGR, role="STAFF", visibility="INTERNAL", body="Comentario interno ficticio"))
    as_staff(message("7b000000-0000-4000-8000-000000000004", Q1, MGR, role="STAFF", body="Equipe a caminho"))
    expect(error(capable(staff, message("7b000000-0000-4000-8000-000000000005", Q1, MGR, role="UNIT", visibility="INTERNAL")), "42501",
                 tenant=TA, actor=MGR, role="cp_api"), "equipe nao escreve como unidade")
    expect(error(message("7b000000-0000-4000-8000-000000000005", Q1, MGR, role="UNIT", visibility="INTERNAL"), "23514"),
           "comentario interno e sempre da equipe, tambem pela restricao da tabela")
    expect(value(f"select count(*) from cp.request_messages where request_id='{Q1}'", tenant=TA, actor=AA, role="cp_api") == "2",
           "unidade ve so as mensagens visiveis")
    expect(value(f"select string_agg(author_role||':'||author_name,',' order by created_at) from cp.request_messages where request_id='{Q1}'")
           == "UNIT:PESSOA FICTICIA A,STAFF:GESTAO PEDIDOS,STAFF:GESTAO PEDIDOS", "autor da mensagem pelo nome do proprio vinculo")
    expect(value(f"select count(*) from cp.request_messages where request_id='{Q1}' and visibility='INTERNAL'", tenant=TA, actor=RES5,
                 role="cp_api") == "0", "comentario interno nunca aparece para a unidade")
    expect(value(capable(staff, f"select count(*) from cp.request_messages where request_id='{Q1}'"), tenant=TA, actor=MGR, role="cp_api") == "3",
           "equipe ve toda a conversa")
    expect(value(f"select count(*) from cp.request_messages where request_id='{Q1}'", tenant=TA, actor=R6, role="cp_api") == "0",
           "outra unidade nao le a conversa")
    expect(error(f"UPDATE cp.request_messages SET body='alterada' WHERE request_id='{Q1}'", "42501", tenant=TA, actor=AA, role="cp_api"),
           "mensagem nao e alterada")
    expect(error(capable(staff, f"DELETE FROM cp.request_messages WHERE request_id='{Q1}'"), "42501", tenant=TA, actor=MGR, role="cp_api"),
           "mensagem nao e apagada")
    # Resolucao, reabertura e fechamento.
    as_staff(change(Q1, "state='IN_PROGRESS'", 1))
    expect(as_staff(change(Q1, "state='RESOLVED'", 2), "23514"), "resolvido exige resolucao")
    as_staff(change(Q1, "state='RESOLVED',resolution='Vedacao trocada'", 2))
    expect(value(f"select state||':'||(resolved_at is not null)||':'||resolution from cp.service_requests where id='{Q1}'") == "RESOLVED:true:Vedacao trocada",
           "resolucao com horario do banco")
    expect(as_staff(change(Q1, "resolution='Outra resolucao'", 3), "23514"), "resolucao registrada nao muda")
    as_staff(change(Q1, "state='IN_PROGRESS'", 3))
    expect(value(f"select state||':'||(resolution is null)||':'||(resolved_at is null) from cp.service_requests where id='{Q1}'") == "IN_PROGRESS:true:true",
           "reaberto perde a resolucao vigente")
    as_staff(change(Q1, "state='RESOLVED',resolution='Vedacao trocada de novo'", 4) + "; " + change(Q1, "state='CLOSED'", 5))
    expect(value(f"select state||':'||(closed_at is not null)||':'||resolution from cp.service_requests where id='{Q1}'") == "CLOSED:true:Vedacao trocada de novo",
           "fechamento preserva a resolucao")
    expect(as_staff(change(Q1, "state='IN_PROGRESS'", 6), "23514"), "fechado e final")
    expect(error(message("7b000000-0000-4000-8000-000000000006", Q1, AA), "23514", tenant=TA, actor=AA, role="cp_api"),
           "pedido fechado nao recebe mensagem")
    expect(value(f"select count(*) from cp.request_messages where request_id='{Q1}'", tenant=TA, actor=AA, role="cp_api") == "2",
           "historico continua visivel depois de fechado")
    # Eventos e trilha.
    query(open_request(Q2, actor=RES5), tenant=TA, actor=RES5, role="cp_api")
    event = (f"INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) "
             f"VALUES('{TA}','REQUEST_OPENED','{Q2}',0,jsonb_build_object('id','{Q2}','unitId','{{unit}}'))")
    expect(error(event.format(unit=UA), "42501", tenant=TA, actor=R6, role="cp_api"), "outra unidade nao grava evento do pedido")
    expect(error(event.format(unit=UA6), "42501", tenant=TA, actor=RES5, role="cp_api"), "evento leva a unidade real do pedido")
    query(event.format(unit=UA), tenant=TA, actor=RES5, role="cp_api")
    trail = (f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) "
             f"VALUES('{TA}','{{actor}}','REQUEST_OPENED','SERVICE_REQUEST','{Q2}','{ROLL}')")
    expect(error(trail.format(actor=AA), "42501", tenant=TA, actor=RES5, role="cp_api"), "trilha de pedido so em nome da propria conta")
    query(trail.format(actor=RES5), tenant=TA, actor=RES5, role="cp_api")
    query(f"UPDATE cp.memberships SET status='REVOKED' WHERE account_id='{RES5}'")
    expect(visible(Q2, RES5) == "0", "vinculo encerrado deixa de ver o pedido")
    query(f"UPDATE cp.memberships SET status='ACTIVE' WHERE account_id='{RES5}'")
    expect(error("select title,description,requester_name from cp.service_requests", "42501", tenant=TA, role="cp_worker"),
           "worker nao le o conteudo dos pedidos (V031: so identificador, unidade e situacao)")
    query(f"DELETE FROM cp.outbox WHERE event_type LIKE 'REQUEST%'; DELETE FROM cp.audit_log WHERE action LIKE 'REQUEST%'; "
          f"DELETE FROM cp.request_messages WHERE tenant_id='{TA}'; DELETE FROM cp.service_requests WHERE tenant_id='{TA}'; "
          f"DELETE FROM cp.memberships WHERE account_id in ('{MGR}','{RES5}','{R6}','{DEP6}','{CONC}'); DELETE FROM cp.units WHERE id='{UA6}'; "
          f"DELETE FROM platform.accounts WHERE id in ('{MGR}','{RES5}','{R6}','{DEP6}','{CONC}')")

IMP = "a1000000-0000-4000-8000-000000000001"
IMP_B = "a1000000-0000-4000-8000-000000000002"
IMP_C = "a1000000-0000-4000-8000-000000000003"

def import_checks():
    """V018: lote de importação de unidades em staging, só para a gestão de unidades e sem duplicar."""
    h = "c" * 64
    batch = (f"INSERT INTO cp.import_batches(id,tenant_id,entity_type,source,file_hash,mapping_version,total_rows,valid_rows,"
             f"invalid_rows,duplicate_rows) VALUES('{{id}}','{TA}','UNITS','unidades.csv','{{hash}}',1,3,1,1,1)")
    rows = (f"INSERT INTO cp.import_rows(tenant_id,batch_id,line_number,block_name,unit_label,outcome,error_code) VALUES"
            f"('{TA}','{IMP}',2,'BLOCO IMPORTADO','301','VALID',NULL),('{TA}','{IMP}',3,NULL,NULL,'INVALID','UNIT_LABEL_REQUIRED'),"
            f"('{TA}','{IMP}',4,'bloco importado','301','DUPLICATE',NULL)")
    expect(error(batch.format(id=IMP, hash=h), "42501", tenant=TA, role="cp_api"), "lote nao e aberto sem capacidade de unidades")
    expect(error(capable("amenities.manage", batch.format(id=IMP, hash=h)), "42501", tenant=TA, role="cp_api"),
           "capacidade de areas nao abre lote")
    expect(error(capable("units.manage", batch.format(id=IMP, hash=h).replace(f"'{TA}'", f"'{TB}'")), "42501", tenant=TA, role="cp_api"),
           "lote nao atravessa tenant")
    query(capable("units.manage", batch.format(id=IMP, hash=h) + "; " + rows), tenant=TA, role="cp_api")
    expect(value(f"select created_by||'|'||state||'|'||version from cp.import_batches where id='{IMP}'") == f"{AA}|PREVIEW|0",
           "autoria, estado e versao do lote vem do banco")
    expect(value(f"select count(*) from cp.import_batches where id='{IMP}'", tenant=TA, role="cp_api") == "0",
           "lote nao e lido sem capacidade")
    expect(value(capable("units.manage", f"select count(*) from cp.import_rows where batch_id='{IMP}'"), tenant=TB, actor=AB, role="cp_api") == "0",
           "linhas de outro tenant permanecem invisiveis")
    expect(error(capable("units.manage", batch.format(id=IMP_B, hash=h)), "23505", tenant=TA, role="cp_api"),
           "mesmo arquivo nao abre segundo lote vivo")
    expect(error(capable("units.manage", f"INSERT INTO cp.import_batches(tenant_id,entity_type,source,file_hash,mapping_version,"
                 f"total_rows,valid_rows,invalid_rows,duplicate_rows) VALUES('{TA}','UNITS','x.csv','{'d'*64}',1,3,1,1,0)"), "23514",
                 tenant=TA, role="cp_api"), "totais do lote precisam fechar")
    approve_without_rows = (f"UPDATE cp.import_batches SET state='APPROVED',partial=true,created_units=1,existing_units=0,"
                            f"created_blocks=1,version=version+1 WHERE id='{IMP}'")
    expect(error(capable("units.manage", approve_without_rows), "23514", tenant=TA, role="cp_api"),
           "aprovacao recusada com linha valida ainda nao aplicada")
    query(approve_without_rows, tenant=TA, role="cp_api")
    expect(value(f"select state from cp.import_batches where id='{IMP}'") == "PREVIEW",
           "sem capacidade o lote nem aparece para aprovacao")
    query(capable("units.manage", f"INSERT INTO cp.blocks(tenant_id,name) VALUES('{TA}','BLOCO IMPORTADO');"
          f"INSERT INTO cp.units(id,tenant_id,block_id,label) SELECT '{IMP_C}','{TA}',id,'301' FROM cp.blocks WHERE tenant_id='{TA}' AND name='BLOCO IMPORTADO';"
          f"UPDATE cp.import_rows SET applied='CREATED',unit_id='{IMP_C}' WHERE batch_id='{IMP}' AND line_number=2"), tenant=TA, role="cp_api")
    expect(error(capable("units.manage", f"UPDATE cp.import_rows SET applied='EXISTING',unit_id='{IMP_C}' WHERE batch_id='{IMP}' AND line_number=2"),
                 "23514", tenant=TA, role="cp_api"), "linha aplicada nao muda de novo")
    expect(error(capable("units.manage", f"UPDATE cp.import_rows SET applied='CREATED',unit_id='{IMP_C}' WHERE batch_id='{IMP}' AND line_number=4"),
                 "23514", tenant=TA, role="cp_api"), "linha repetida nao e aplicada")
    expect(error(capable("units.manage", approve_without_rows.replace("partial=true", "partial=false")), "23514", tenant=TA, role="cp_api"),
           "lote com linha invalida so e aprovado como parcial explicito")
    expect(error(capable("units.manage", f"UPDATE cp.import_batches SET state='DISCARDED',version=version+1 WHERE id='{IMP}'"),
                 "23514", tenant=TA, role="cp_api"), "lote com linha aplicada nao e descartado")
    query(capable("units.manage", approve_without_rows), tenant=TA, role="cp_api")
    expect(value(f"select state||'|'||version||'|'||decided_by||'|'||created_units from cp.import_batches where id='{IMP}'")
           == f"APPROVED|1|{AA}|1", "aprovacao grava decisao, autoria e contagens")
    expect(error(capable("units.manage", rows.replace("',2,", "',9,").split("),")[0] + ")"), "23514", tenant=TA, role="cp_api"),
           "lote aprovado nao recebe linha nova")
    expect(error(capable("units.manage", f"UPDATE cp.import_batches SET state='DISCARDED',version=version+1 WHERE id='{IMP}'"),
                 "23514", tenant=TA, role="cp_api"), "lote aprovado nao volta nem e descartado")
    expect(error(capable("units.manage", f"DELETE FROM cp.import_batches WHERE id='{IMP}'"), "42501", tenant=TA, role="cp_api"),
           "aplicacao nao apaga lote")
    expect(error(capable("units.manage", f"DELETE FROM cp.import_rows WHERE batch_id='{IMP}'"), "42501", tenant=TA, role="cp_api"),
           "aplicacao nao apaga linhas do lote")
    expect(error(capable("units.manage", f"UPDATE cp.import_rows SET unit_label='999' WHERE batch_id='{IMP}'"), "42501", tenant=TA, role="cp_api"),
           "conteudo da linha nao muda")
    query(capable("units.manage", batch.format(id=IMP_B, hash="e" * 64)), tenant=TA, role="cp_api")
    query(capable("units.manage", f"UPDATE cp.import_batches SET state='DISCARDED',version=version+1 WHERE id='{IMP_B}'"), tenant=TA, role="cp_api")
    query(capable("units.manage", batch.format(id="a1000000-0000-4000-8000-000000000004", hash="e" * 64)), tenant=TA, role="cp_api")
    expect(value(f"select count(*) from cp.import_batches where file_hash='{'e'*64}'") == "2",
           "arquivo de lote descartado pode ser aberto de novo")
    audit = (f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) "
             f"VALUES('{TA}','{AA}','IMPORT_BATCH_APPROVED','IMPORT_BATCH','{{rid}}','{ROLL}')")
    expect(error(audit.format(rid=IMP), "42501", tenant=TA, role="cp_api"), "trilha da importacao exige capacidade")
    expect(error(capable("units.manage", audit.format(rid=UA)), "42501", tenant=TA, role="cp_api"),
           "trilha da importacao exige lote existente")
    query(capable("units.manage", audit.format(rid=IMP)), tenant=TA, role="cp_api")

def attachment_checks():
    """V019: anexos privados de pedido; leitura so com o pedido visivel, conclusao so por quem enviou, sem apagar."""
    OTHER, MGR2 = "10000000-0000-4000-8000-0000000000c1", "10000000-0000-4000-8000-0000000000c2"
    UC = "20000000-0000-4000-8000-0000000000c3"
    QA, QB, QC = ("7b000000-0000-4000-8000-000000000001", "7b000000-0000-4000-8000-000000000002",
                  "7b000000-0000-4000-8000-000000000003")
    F1, F2, F3 = ("7c000000-0000-4000-8000-000000000001", "7c000000-0000-4000-8000-000000000002",
                  "7c000000-0000-4000-8000-000000000003")
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{OTHER}','ACTIVE'),('{MGR2}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UC}','{TA}','FICTICIA ANEXOS');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{OTHER}','{UC}','MORADOR OUTRA UNIDADE','RESIDENT','ACTIVE'),('{TA}','{MGR2}',NULL,'GESTAO ANEXOS','MANAGER','ACTIVE');
    """)
    query(f"INSERT INTO cp.service_requests(id,tenant_id,unit_id,requester_id,category,title,description) "
          f"VALUES('{QA}','{TA}','{UA}','{AA}','MAINTENANCE','Pedido com anexo','Foto do vazamento')", tenant=TA, role="cp_api")
    query(f"INSERT INTO cp.service_requests(id,tenant_id,unit_id,requester_id,category,title,description) "
          f"VALUES('{QB}','{TA}','{UC}','{OTHER}','MAINTENANCE','Pedido de outra unidade','Sem anexo')", tenant=TA, actor=OTHER, role="cp_api")
    def attach(identifier, request=QA, mime="image/png", size=1024, key=None, tenant=TA, owner=AA):
        object_key = key or f"{tenant}/{identifier}"
        return (f"INSERT INTO cp.attachments(id,tenant_id,owner_id,purpose,entity_type,entity_id,file_name,mime_type,size_bytes,bucket,object_key) "
                f"VALUES('{identifier}','{tenant}','{owner}','REQUEST_ATTACHMENT','SERVICE_REQUEST','{request}','foto.png','{mime}',{size},"
                f"'cp-upload-quarantine','{object_key}')")
    query(attach(F1), tenant=TA, role="cp_api")
    expect(value(f"select state||'|'||(upload_expires_at-created_at)::text from cp.attachments where id='{F1}'") == "PENDING|00:15:00",
           "anexo nasce pendente com prazo de envio do banco")
    expect(error(attach(F2, request=QB), "42501", tenant=TA, role="cp_api"), "anexo nao entra em pedido de outra unidade")
    expect(error(attach(F2, owner=OTHER), "42501", tenant=TA, role="cp_api"), "anexo so em nome da propria conta")
    expect(error(attach(F2, key=f"{TA}/foto.png"), "23514", tenant=TA, role="cp_api"), "chave do objeto nao vem do nome enviado")
    expect(error(attach(F2, mime="image/gif"), "23514", tenant=TA, role="cp_api"), "tipo fora da lista e recusado")
    expect(error(attach(F2, size=10485761), "23514", tenant=TA, role="cp_api"), "imagem acima de 10 MB e recusada")
    expect(value(f"select count(*) from cp.attachments where id='{F1}'", tenant=TA, actor=OTHER, role="cp_api") == "0",
           "outra unidade nao ve o anexo nem pelo ID")
    expect(value(f"select count(*) from cp.attachments where id='{F1}'", tenant=TB, actor=AB, role="cp_api") == "0",
           "outro condominio nao ve o anexo")
    expect(value(capable("requests.manage", f"select count(*) from cp.attachments where id='{F1}'"), tenant=TA, actor=MGR2, role="cp_api") == "1",
           "gestao de pedidos ve o anexo do condominio")
    expect(value(f"select count(*) from cp.attachments where id='{F1}'", tenant=TA, actor=MGR2, role="cp_api") == "0",
           "gestao sem capacidade publicada nao ve o anexo")
    expect(error(capable("requests.manage", f"UPDATE cp.attachments SET state='AVAILABLE',sha256=repeat('a',64) WHERE id='{F1}'"), "42501",
                 tenant=TA, actor=MGR2, role="cp_api"), "so quem enviou conclui o anexo")
    expect(error(f"UPDATE cp.attachments SET state='AVAILABLE' WHERE id='{F1}'", "23514", tenant=TA, role="cp_api"),
           "disponivel exige hash conferido")
    expect(error(f"UPDATE cp.attachments SET size_bytes=10 WHERE id='{F1}'", "42501", tenant=TA, role="cp_api"),
           "tamanho declarado nao muda")
    query(f"UPDATE cp.attachments SET state='AVAILABLE',sha256=repeat('b',64) WHERE id='{F1}'", tenant=TA, role="cp_api")
    expect(value(f"select state||'|'||(completed_at is not null) from cp.attachments where id='{F1}'") == "AVAILABLE|true",
           "conclusao grava horario do banco")
    expect(error(f"UPDATE cp.attachments SET state='REJECTED',rejection='TYPE_MISMATCH',sha256=NULL WHERE id='{F1}'", "23514", tenant=TA, role="cp_api"),
           "anexo disponivel nao volta a ser rejeitado")
    query(attach(F2), tenant=TA, role="cp_api")
    query(f"UPDATE cp.attachments SET state='REJECTED',rejection='TYPE_MISMATCH' WHERE id='{F2}'", tenant=TA, role="cp_api")
    expect(error(f"DELETE FROM cp.attachments WHERE id='{F2}'", "42501", tenant=TA, role="cp_api"), "aplicacao nao apaga anexo")
    for index in range(9):
        query(attach(f"7c000000-0000-4000-8000-0000000001{index:02d}"), tenant=TA, role="cp_api")
    expect(error(attach(F3), "23514", tenant=TA, role="cp_api"), "no maximo 10 anexos nao rejeitados por pedido")
    query("SET session_replication_role=replica; UPDATE cp.attachments SET upload_expires_at=clock_timestamp()-interval '6 minutes' "
          "WHERE id='7c000000-0000-4000-8000-000000000100'; SET session_replication_role=origin")
    query(attach("7c000000-0000-4000-8000-000000000199"), tenant=TA, role="cp_api")
    expect(value("select count(*) from cp.attachments where id='7c000000-0000-4000-8000-000000000199'") == "1",
           "pendente vencido nao ocupa vaga do pedido")
    expect(error(f"UPDATE cp.attachments SET state='AVAILABLE',sha256=repeat('c',64) WHERE id='7c000000-0000-4000-8000-000000000100'",
                 "23514", tenant=TA, role="cp_api"), "pendente vencido nao e liberado")
    query(f"UPDATE platform.accounts SET status='ACTIVE' WHERE id='{AA}'")
    query(capable("requests.manage", f"UPDATE cp.service_requests SET state='CLOSED',version=version+1 WHERE id='{QA}'"), tenant=TA, actor=MGR2, role="cp_api")
    query(f"INSERT INTO cp.service_requests(id,tenant_id,unit_id,requester_id,category,title,description) "
          f"VALUES('{QC}','{TA}','{UA}','{AA}','MAINTENANCE','Outro pedido','Texto')", tenant=TA, role="cp_api")
    query(capable("requests.manage", f"UPDATE cp.service_requests SET state='CLOSED',version=version+1 WHERE id='{QC}'"), tenant=TA, actor=MGR2, role="cp_api")
    expect(error(attach(F3, request=QC), "42501", tenant=TA, role="cp_api"), "pedido fechado nao recebe anexo")
    audit = (f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) "
             f"VALUES('{TA}','{{actor}}','ATTACHMENT_UPLOADED','ATTACHMENT','{F1}','{ROLL}')")
    expect(error(audit.format(actor=AA), "42501", tenant=TA, actor=OTHER, role="cp_api"), "trilha do anexo so em nome da propria conta")
    expect(error(audit.format(actor=OTHER), "42501", tenant=TA, actor=OTHER, role="cp_api"), "trilha exige anexo visivel")
    query(audit.format(actor=AA), tenant=TA, role="cp_api")

def permit_link_checks():
    """V020: link de convite por hash; quem abre ve so aquele convite em vigor; criar outro revoga o anterior."""
    DEPL, RESL, ULB = ("10000000-0000-4000-8000-0000000000f1", "10000000-0000-4000-8000-0000000000f2",
                       "20000000-0000-4000-8000-0000000000f3")
    VL1, VL2, VL3 = ("70000000-0000-4000-8000-0000000000f1", "70000000-0000-4000-8000-0000000000f2", "70000000-0000-4000-8000-0000000000f3")
    PL1, PL2, PL3 = ("71000000-0000-4000-8000-0000000000f1", "71000000-0000-4000-8000-0000000000f2", "71000000-0000-4000-8000-0000000000f3")
    L1, L2, L3, L4 = ("73000000-0000-4000-8000-0000000000f1", "73000000-0000-4000-8000-0000000000f2",
                      "73000000-0000-4000-8000-0000000000f3", "73000000-0000-4000-8000-0000000000f4")
    H1, H2, H3, H4 = ("1" * 64, "2" * 64, "3" * 64, "4" * 64)
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{DEPL}','ACTIVE'),('{RESL}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{ULB}','{TA}','FICTICIA LINK');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status,permissions)
          VALUES('{TA}','{DEPL}','{UA}','DEPENDENTE LINK','DEPENDENT','ACTIVE','{{"visits":{{"create":true}}}}'),
                ('{TA}','{RESL}','{ULB}','MORADOR LINK','RESIDENT','ACTIVE','{{}}');
    """)
    def permit(visitor_id, permit_id, actor=AA, end="clock_timestamp()+interval '2 hours'"):
        return (f"INSERT INTO cp.visitors(id,tenant_id,full_name,created_by) VALUES('{visitor_id}','{TA}','CONVIDADA LINK SOBRENOME','{actor}'); "
                f"INSERT INTO cp.visit_permits(id,tenant_id,visitor_id,unit_id,purpose,created_by,valid_from,valid_until) "
                f"VALUES('{permit_id}','{TA}','{visitor_id}','{UA}','VISITA LINK','{actor}',clock_timestamp(),{end})")
    def link(identifier, permit_id, token, actor=AA, unit=UA):
        return (f"INSERT INTO cp.permit_links(id,tenant_id,permit_id,unit_id,token_hash,created_by) "
                f"VALUES('{identifier}','{TA}','{permit_id}','{unit}','{token}','{actor}')")
    query(permit(VL1, PL1), tenant=TA, role="cp_api")
    query(permit(VL2, PL2, actor=DEPL), tenant=TA, actor=DEPL, role="cp_api")
    query(link(L1, PL1, H1, unit=ULB), tenant=TA, role="cp_api")
    expect(value(f"select unit_id||'|'||(revoked_at is null) from cp.permit_links where id='{L1}'") == f"{UA}|true",
           "unidade do link vem do convite, nao do pedido")
    expect(error(link(L2, PL1, H2), "23505", tenant=TA, role="cp_api"), "um link em vigor por convite")
    expect(error(link(L2, PL1, H2, actor=RESL), "42501", tenant=TA, actor=RESL, role="cp_api"), "outra unidade nao cria link")
    expect(error(link(L2, PL1, H2, actor=DEPL), "42501", tenant=TA, actor=DEPL, role="cp_api"),
           "dependente nao compartilha convite que nao criou")
    query(link(L2, PL2, H2, actor=DEPL), tenant=TA, actor=DEPL, role="cp_api")
    expect(value(f"select count(*) from cp.permit_links where id in ('{L1}','{L2}')", tenant=TA, actor=DEPL, role="cp_api") == "1",
           "dependente ve so o link do convite que criou")
    expect(value(f"select count(*) from cp.permit_links where id in ('{L1}','{L2}')", tenant=TA, actor=RESL, role="cp_api") == "0",
           "outra unidade nao ve links")
    expect(value(f"select count(*) from cp.permit_links", tenant=TB, actor=AB, role="cp_api") == "0", "outro condominio nao ve links")
    expect(value("SET LOCAL app.capabilities='concierge.operate'; select count(*) from cp.permit_links", tenant=TA, role="cp_api") == "2",
           "morador da unidade ve os links da unidade")
    # Quem abre o link: sem conta, so com o hash.
    # Sem app.tenant_id: o fluxo publico nunca define condominio.
    public = lambda token, statement: value(f"SET LOCAL app.permit_link_hash='{token}'; {statement}", actor="", role="cp_api")
    expect(public(H1, "select tenant_id||'|'||permit_id from cp.permit_links") == f"{TA}|{PL1}", "hash apresentado acha so o proprio link")
    expect(public(H1, "select count(*) from platform.tenants") == "1", "quem abre o link ve o condominio do convite")
    expect(public(H1, "select string_agg(id::text,',') from cp.visit_permits") == PL1, "quem abre o link ve so aquele convite")
    expect(public(H1, "select full_name from cp.visitors") == "CONVIDADA LINK SOBRENOME", "e so a pessoa daquele convite")
    expect(public(H3, "select count(*) from cp.permit_links") == "0", "hash desconhecido nao acha nada")
    expect(public(H1, "select count(*) from cp.units") == "0", "link nao abre unidades")
    expect(public(H1, "select count(*) from cp.arrival_requests") == "0", "link nao abre chegadas")
    expect(value("select count(*) from cp.permit_links", actor="", role="cp_api") == "0", "sem hash nem contexto nao ha links")
    expect(error(f"SET LOCAL app.permit_link_hash='{H1}'; UPDATE cp.permit_links SET revoked_by='{AA}' WHERE id='{L1}'",
                 "42501", tenant=TA, actor="", role="cp_api") or
           value(f"SET LOCAL app.permit_link_hash='{H1}'; WITH u AS (UPDATE cp.permit_links SET revoked_by='{AA}' WHERE id='{L1}' RETURNING 1) "
                 "SELECT count(*) FROM u", tenant=TA, actor="", role="cp_api") == "0", "quem abre o link nao o revoga")
    # Revogar: uma vez, com horario do banco; outro link pode nascer depois.
    expect(value(f"WITH u AS (UPDATE cp.permit_links SET revoked_by='{RESL}' WHERE id='{L1}' RETURNING 1) SELECT count(*) FROM u",
                 tenant=TA, actor=RESL, role="cp_api") == "0", "outra unidade nao revoga link")
    expect(error(f"UPDATE cp.permit_links SET revoked_by='{DEPL}' WHERE id='{L1}'", "42501", tenant=TA, role="cp_api"),
           "revogacao registra a propria conta")
    query(f"UPDATE cp.permit_links SET revoked_by='{AA}' WHERE id='{L1}'", tenant=TA, role="cp_api")
    expect(value(f"select (revoked_at is not null)::text from cp.permit_links where id='{L1}'") == "true", "revogacao grava horario do banco")
    expect(error(f"UPDATE cp.permit_links SET revoked_by='{AA}' WHERE id='{L1}'", "23514", tenant=TA, role="cp_api"), "link revogado nao muda de novo")
    expect(error(f"UPDATE cp.permit_links SET token_hash='{H4}' WHERE id='{L1}'", "42501", tenant=TA, role="cp_api"), "hash nao muda")
    expect(public(H1, "select count(*) from cp.visit_permits") == "0", "link revogado nao abre mais o convite")
    expect(public(H1, "select count(*) from platform.tenants") == "0", "nem o condominio")
    query(link(L3, PL1, H3), tenant=TA, role="cp_api")
    expect(public(H3, "select count(*) from cp.visit_permits") == "1", "novo link vale depois da revogacao do anterior")
    query(f"UPDATE cp.visit_permits SET state='REVOKED',revoked_by='{AA}',revoke_reason='Fim',version=version+1 WHERE id='{PL1}'",
          tenant=TA, role="cp_api")
    expect(error(link(L4, PL1, H4), "23514", tenant=TA, role="cp_api"), "convite revogado nao ganha link")
    query(permit(VL3, PL3, end="clock_timestamp()+interval '2 hours'"), tenant=TA, role="cp_api")
    query("SET session_replication_role=replica; UPDATE cp.visit_permits SET valid_from=clock_timestamp()-interval '3 hours', "
          f"valid_until=clock_timestamp()-interval '1 hour' WHERE id='{PL3}'; SET session_replication_role=origin")
    expect(error(link(L4, PL3, H4), "23514", tenant=TA, role="cp_api"), "convite vencido nao ganha link")
    expect(error(f"DELETE FROM cp.permit_links WHERE id='{L1}'", "42501", tenant=TA, role="cp_api"), "aplicacao nao apaga link")
    expect(error(f"INSERT INTO cp.permit_links(id,tenant_id,permit_id,unit_id,token_hash,created_by,revoked_at) "
                 f"VALUES('{L4}','{TA}','{PL2}','{UA}','{H4}','{AA}',now())", "42501", tenant=TA, role="cp_api"),
           "link nao nasce revogado nem com horario escolhido")
    expect(error(link(L4, PL2, "ABC", actor=DEPL), "23514", tenant=TA, actor=DEPL, role="cp_api"), "hash so em hexadecimal de 64")

def reservation_guest_checks():
    """V021: convidados de reserva com janela do evento; encerrar a reserva revoga todos na mesma transacao."""
    MGRG, RESG, UG = ("10000000-0000-4000-8000-0000000000d1", "10000000-0000-4000-8000-0000000000d2", "20000000-0000-4000-8000-0000000000d3")
    RG1, RG2, RG3 = ("31000000-0000-4000-8000-0000000000d1", "31000000-0000-4000-8000-0000000000d2", "31000000-0000-4000-8000-0000000000d3")
    AG = "30000000-0000-4000-8000-0000000000d4"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MGRG}','ACTIVE'),('{RESG}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UG}','{TA}','FICTICIA CONVIDADOS');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{MGRG}',NULL,'GESTAO CONVIDADOS','MANAGER','ACTIVE'),('{TA}','{RESG}','{UG}','MORADOR CONVIDADOS','RESIDENT','ACTIVE');
        INSERT INTO cp.amenities(id,tenant_id,name,capacity,approval_required) VALUES('{AG}','{TA}','AREA CONVIDADOS',2,false);
    """)
    def booking(identifier, offset_hours, status="CONFIRMED", unit=UA, actor=AA, kind="BOOKING", snapshot='{"capacity":2}'):
        start = f"date_trunc('hour',clock_timestamp())+interval '{offset_hours} hours'"
        expires = f"{start}-interval '1 hour'" if status == "PENDING_APPROVAL" else "NULL"
        return (f"INSERT INTO cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot,expires_at) "
                f"VALUES('{identifier}','{TA}','{AG}','{unit}','{actor}','{kind}','{status}',{start},{start}+interval '2 hours',{start},{start}+interval '2 hours','{snapshot}'::jsonb,{expires})")
    query(booking(RG1, 26), tenant=TA, role="cp_api")
    query(booking(RG2, 50, status="PENDING_APPROVAL"), tenant=TA, role="cp_api")
    query(booking(RG3, 74, unit=UG, actor=RESG), tenant=TA, actor=RESG, role="cp_api")
    counter = [0]
    def guest(reservation, actor=AA, unit=UA, before="1 hour", end_shift="0 minutes"):
        counter[0] += 1
        visitor = f"74000000-0000-4000-8000-{counter[0]:012d}"
        permit = f"75000000-0000-4000-8000-{counter[0]:012d}"
        window = (f"(SELECT starts_at-interval '{before}' FROM cp.reservations WHERE id='{reservation}'),"
                  f"(SELECT ends_at+interval '{end_shift}' FROM cp.reservations WHERE id='{reservation}')")
        return permit, (f"INSERT INTO cp.visitors(id,tenant_id,full_name,created_by) VALUES('{visitor}','{TA}','CONVIDADO {counter[0]}','{actor}'); "
                        f"INSERT INTO cp.visit_permits(id,tenant_id,visitor_id,unit_id,purpose,created_by,valid_from,valid_until,reservation_id) "
                        f"VALUES('{permit}','{TA}','{visitor}','{unit}','CONVIDADO DE RESERVA','{actor}',{window},'{reservation}')")
    g1, sql = guest(RG1)
    query(sql, tenant=TA, role="cp_api")
    expect(value(f"select state from cp.visit_permits where id='{g1}'") == "ACTIVE", "morador convida para a propria reserva confirmada")
    _, sql = guest(RG1, before="2 hours")
    expect(error(sql, "23514", tenant=TA, role="cp_api"), "convidado nao comeca mais de 1 hora antes do evento")
    _, sql = guest(RG1, end_shift="1 hour")
    expect(error(sql, "23514", tenant=TA, role="cp_api"), "convidado termina junto com o evento")
    _, sql = guest(RG2)
    expect(error(sql, "23514", tenant=TA, role="cp_api"), "pedido aguardando aprovacao nao recebe convidados")
    _, sql = guest(RG3)
    expect(error(sql, "42501", tenant=TA, role="cp_api"), "reserva de outra unidade nao recebe convidados desta")
    _, sql = guest(RG3, actor=RESG, unit=UA)
    expect(error(sql, "42501", tenant=TA, actor=RESG, role="cp_api"), "outra unidade nao convida em nome desta")
    g2, sql = guest(RG1)
    query(sql, tenant=TA, role="cp_api")
    _, sql = guest(RG1)
    expect(error(sql, "23514", tenant=TA, role="cp_api"), "convidados ativos ate a capacidade da area")
    # Gestao cancela a reserva: todos os convidados revogados na mesma transacao, em nome de quem cancelou.
    expect(value(f"select count(*) from cp.visit_permits where reservation_id='{RG1}'", tenant=TA, actor=MGRG, role="cp_api") == "0",
           "gestao nao ve convidados no dia a dia")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{RG1}'", tenant=TA, actor=MGRG, role="cp_api")
    expect(value(f"select string_agg(state||':'||revoked_by||':'||revoke_reason,',' order by id) from cp.visit_permits where reservation_id='{RG1}'")
           == f"REVOKED:{MGRG}:Reserva cancelada,REVOKED:{MGRG}:Reserva cancelada", "cancelar a reserva revoga todos os convidados")
    expect(value(f"select count(*) from cp.outbox where event_type='VISIT_PERMIT_REVOKED' and payload->>'reservationId'='{RG1}'") == "2",
           "um evento de revogacao por convidado, na mesma transacao")
    expect(value(f"select count(*) from cp.visit_permits where reservation_id='{RG1}'", tenant=TA, actor=MGRG, role="cp_api") == "0",
           "a liberacao para quem encerra nao sobra depois")
    expect(error(f"UPDATE cp.visit_permits SET state='ACTIVE',revoked_by=NULL,revoke_reason=NULL,version=version+1 WHERE id='{g1}'",
                 "23514", tenant=TA, role="cp_api"), "convidado revogado nao volta")
    _, sql = guest(RG1)
    expect(error(sql, "23514", tenant=TA, role="cp_api"), "reserva cancelada nao recebe convidados")
    # Repeticao do cancelamento (mesmo estado) nao reativa nem falha.
    expect(error(f"UPDATE cp.reservations SET status='CONFIRMED',version=version+1 WHERE id='{RG1}'", "23514", tenant=TA, role="cp_api"),
           "reserva cancelada nao volta a confirmar")
    query(f"UPDATE cp.reservations SET version=version WHERE id='{RG1}'", tenant=TA, role="cp_api")
    expect(value(f"select count(*) from cp.visit_permits where reservation_id='{RG1}' and state='ACTIVE'") == "0", "repeticao nao reativa convidados")
    # Morador cancela a propria reserva com convidado: revogado em nome dele.
    g3, sql = guest(RG3, actor=RESG, unit=UG)
    query(sql, tenant=TA, actor=RESG, role="cp_api")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{RG3}'", tenant=TA, actor=RESG, role="cp_api")
    expect(value(f"select state||':'||revoked_by from cp.visit_permits where id='{g3}'") == f"REVOKED:{RESG}", "cancelamento pelo morador revoga convidado")
    # Sem conta (processo sem ator) nao encerra reserva com convidados.
    RG4 = "31000000-0000-4000-8000-0000000000d4"
    query(booking(RG4, 98), tenant=TA, role="cp_api")
    g4, sql = guest(RG4)
    query(sql, tenant=TA, role="cp_api")
    expect(error(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{RG4}'", "42501", tenant=TA, actor="", role="cp_api"),
           "encerramento sem conta nao deixa convidado valido")
    expect(value(f"select state from cp.visit_permits where id='{g4}'") == "ACTIVE", "nada muda na recusa")
    expect(value(f"select count(*) from cp.visit_permits where id='{g4}'", tenant=TA, actor=MGRG, role="cp_api") == "0",
           "marca de encerramento nao vaza")
    # Dependente com permissao de convidar: a capacidade conta todos os convidados da reserva, sem expor os dos outros.
    DEPG, RG5 = "10000000-0000-4000-8000-0000000000d5", "31000000-0000-4000-8000-0000000000d5"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{DEPG}','ACTIVE');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status,permissions)
          VALUES('{TA}','{DEPG}','{UG}','DEPENDENTE CONVIDADOS','DEPENDENT','ACTIVE','{{"visits":{{"create":true}}}}');
    """)
    query(booking(RG5, 122, unit=UG, actor=RESG), tenant=TA, actor=RESG, role="cp_api")
    for _ in range(2):
        _, sql = guest(RG5, actor=RESG, unit=UG)
        query(sql, tenant=TA, actor=RESG, role="cp_api")
    _, sql = guest(RG5, actor=DEPG, unit=UG)
    expect(error(sql, "23514", tenant=TA, actor=DEPG, role="cp_api"), "dependente nao passa da capacidade preenchida por outro morador")
    expect(value(f"select cp.reservation_active_guests('{TA}','{RG5}')", tenant=TA, actor=DEPG, role="cp_api") == "2",
           "dependente conta todos os convidados da reserva")
    expect(value(f"select count(*) from cp.visit_permits where reservation_id='{RG5}'", tenant=TA, actor=DEPG, role="cp_api") == "0",
           "dependente continua sem ver os convidados dos outros")
    expect(value(f"select coalesce(cp.reservation_active_guests('{TA}','{RG5}')::text,'null')", tenant=TA, actor=AA, role="cp_api") == "null",
           "outra unidade nao conta convidados de reserva que nao enxerga")
    # Inclusao e cancelamento simultaneos se serializam: o convidado nunca sobra ativo numa reserva encerrada.
    RG6, RG7 = "31000000-0000-4000-8000-0000000000d6", "31000000-0000-4000-8000-0000000000d7"
    query(booking(RG6, 146), tenant=TA, role="cp_api")
    query(booking(RG7, 170), tenant=TA, role="cp_api")
    cancel = "UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{}'"
    g6, sql = guest(RG6)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        adding = pool.submit(query, sql + "; SELECT pg_sleep(2)", tenant=TA, role="cp_api", check=False)
        time.sleep(0.7)
        cancelling = pool.submit(query, cancel.format(RG6), tenant=TA, actor=MGRG, role="cp_api", check=False)
        added, cancelled = adding.result(), cancelling.result()
    expect(added.returncode == 0 and cancelled.returncode == 0, "inclusao e cancelamento simultaneos terminam")
    expect(value(f"select state||':'||revoked_by from cp.visit_permits where id='{g6}'") == f"REVOKED:{MGRG}",
           "cancelamento espera a inclusao em curso e revoga o convidado")
    expect(value(f"select count(*) from cp.outbox where event_type='VISIT_PERMIT_REVOKED' and aggregate_id='{g6}'") == "1",
           "evento de revogacao do convidado incluido durante o cancelamento")
    g7, sql = guest(RG7)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        cancelling = pool.submit(query, cancel.format(RG7) + "; SELECT pg_sleep(2)", tenant=TA, actor=MGRG, role="cp_api", check=False)
        time.sleep(0.7)
        adding = pool.submit(query, sql, tenant=TA, role="cp_api", check=False)
        cancelled, added = cancelling.result(), adding.result()
    expect(cancelled.returncode == 0 and added.returncode != 0 and "23514" in added.stderr,
           "inclusao que espera o cancelamento ve a reserva encerrada e e recusada")
    expect(value(f"select count(*) from cp.visit_permits where id='{g7}'") == "0", "nenhum convidado criado depois do cancelamento")
    # Caminho da API: o cancelamento trava a unidade e depois a linha (FOR UPDATE) antes de mudar o status; a inclusao
    # concorrente espera a mesma trava da unidade em vez de travar em ordem inversa (deadlock).
    RG8 = "31000000-0000-4000-8000-0000000000d8"
    query(booking(RG8, 194), tenant=TA, role="cp_api")
    g8, sql = guest(RG8)
    api_cancel = (f"SELECT pg_advisory_xact_lock(hashtextextended('cp.reservations:{TA}:{UA}',0)); "
                  f"SELECT 1 FROM cp.reservations WHERE id='{RG8}' FOR UPDATE; SELECT pg_sleep(2); " + cancel.format(RG8))
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        cancelling = pool.submit(query, api_cancel, tenant=TA, actor=MGRG, role="cp_api", check=False)
        time.sleep(0.7)
        adding = pool.submit(query, sql, tenant=TA, role="cp_api", check=False)
        cancelled, added = cancelling.result(), adding.result()
    expect(cancelled.returncode == 0 and "40P01" not in added.stderr and "23514" in added.stderr,
           "cancelamento pela ordem da API e inclusao concorrente sem deadlock; inclusao recusada")
    expect(value(f"select count(*) from cp.visit_permits where id='{g8}'") == "0", "nenhum convidado na reserva cancelada pela API")
    # Capacidade aceita na reserva: mudar a area depois nao altera a reserva; snapshot sem capacidade falha fechado.
    RG9, RG10 = "31000000-0000-4000-8000-0000000000d9", "31000000-0000-4000-8000-0000000000da"
    query(booking(RG9, 218), tenant=TA, role="cp_api")
    query(booking(RG10, 242, snapshot="{}"), tenant=TA, role="cp_api")
    query(f"UPDATE cp.amenities SET capacity=1 WHERE id='{AG}'")
    for _ in range(2):
        _, sql = guest(RG9)
        query(sql, tenant=TA, role="cp_api")
    expect(value(f"select count(*) from cp.visit_permits where reservation_id='{RG9}' and state='ACTIVE'") == "2",
           "reducao posterior da area nao reduz a capacidade aceita")
    query(f"UPDATE cp.amenities SET capacity=50 WHERE id='{AG}'")
    _, sql = guest(RG9)
    expect(error(sql, "23514", tenant=TA, role="cp_api"), "aumento posterior da area nao amplia a capacidade aceita")
    _, sql = guest(RG10)
    expect(error(sql, "23514", tenant=TA, role="cp_api"), "reserva sem capacidade nas regras aceitas nao recebe convidados")
    # Expiracao de pedido vencido de outra unidade (caminho da criacao) nao toma a trava daquela unidade: sem deadlock.
    RG11 = "31000000-0000-4000-8000-0000000000db"
    start = "date_trunc('hour',clock_timestamp())+interval '266 hours'"
    query(f"INSERT INTO cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot,expires_at) "
          f"VALUES('{RG11}','{TA}','{AG}','{UG}','{RESG}','BOOKING','PENDING_APPROVAL',{start},{start}+interval '2 hours',{start},{start}+interval '2 hours','{{}}'::jsonb,clock_timestamp()-interval '1 minute')")
    lock = "SELECT pg_advisory_xact_lock(hashtextextended('cp.reservations:{}:{}',0)); "
    expire_other = (lock.format(TA, UA) + f"SELECT 1 FROM cp.reservations WHERE id='{RG11}' FOR UPDATE; SELECT pg_sleep(2); "
                    f"UPDATE cp.reservations SET status='EXPIRED',version=version+1 WHERE id='{RG11}'")
    expire_own = lock.format(TA, UG) + f"SELECT 1 FROM cp.reservations WHERE id='{RG11}' FOR UPDATE"
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(query, expire_other, tenant=TA, role="cp_api", check=False)
        time.sleep(0.7)
        second = pool.submit(query, expire_own, tenant=TA, actor=RESG, role="cp_api", check=False)
        first, second = first.result(), second.result()
    expect(first.returncode == 0 and second.returncode == 0, "expiracao de pedido de outra unidade e comando da unidade sem deadlock")
    # Revogacao individual de convidado (trava do convite e depois a linha) e cancelamento da reserva sem deadlock.
    RG12 = "31000000-0000-4000-8000-0000000000dc"
    query(booking(RG12, 290), tenant=TA, role="cp_api")
    g12, sql = guest(RG12)
    query(sql, tenant=TA, role="cp_api")
    revoke_one = (f"SELECT cp.lock_permit_access('{TA}','{g12}'); SELECT pg_sleep(2); "
                  f"UPDATE cp.visit_permits SET state='REVOKED',revoked_by='{AA}',revoke_reason='Removido',version=version+1 WHERE id='{g12}' AND state='ACTIVE'")
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        removing = pool.submit(query, revoke_one, tenant=TA, role="cp_api", check=False)
        time.sleep(0.7)
        cancelling = pool.submit(query, cancel.format(RG12), tenant=TA, actor=MGRG, role="cp_api", check=False)
        removed, cancelled = removing.result(), cancelling.result()
    expect(removed.returncode == 0 and cancelled.returncode == 0, "remocao de convidado e cancelamento da reserva sem deadlock")
    expect(value(f"select state||':'||revoked_by from cp.visit_permits where id='{g12}'") == f"REVOKED:{AA}", "convidado removido antes do cancelamento continua revogado")

def visit_rule_checks():
    """V022: regra de prestador gera janelas explicitas no fuso do condominio; fora da janela nao ha convite; revogar derruba janelas."""
    # As janelas sao "hoje 00:00-23:59" no fuso do condominio: perto da meia-noite local a de hoje ja terminou e o
    # "hoje" muda no meio do teste (CI 40994604349 falhou as 23:58-23:59 de Sao Paulo). Espera o dia virar antes de comecar.
    left = float(value(f"SELECT extract(epoch FROM (date_trunc('day', clock_timestamp() AT TIME ZONE t.time_zone) + interval '1 day') "
                       f"- (clock_timestamp() AT TIME ZONE t.time_zone)) FROM platform.tenants t WHERE t.id='{TA}'"))
    if left < 600:
        time.sleep(left + 5)
    CONR, DEPR, RESR, UR = ("10000000-0000-4000-8000-0000000000b1", "10000000-0000-4000-8000-0000000000b2",
                            "10000000-0000-4000-8000-0000000000b3", "20000000-0000-4000-8000-0000000000b4")
    VR1, VR2, VR3 = ("76000000-0000-4000-8000-0000000000b1", "76000000-0000-4000-8000-0000000000b2", "76000000-0000-4000-8000-0000000000b3")
    R1, R2, R3 = ("77000000-0000-4000-8000-0000000000b1", "77000000-0000-4000-8000-0000000000b2", "77000000-0000-4000-8000-0000000000b3")
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{CONR}','ACTIVE'),('{DEPR}','ACTIVE'),('{RESR}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UR}','{TA}','FICTICIA REGRAS');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status,permissions)
          VALUES('{TA}','{CONR}',NULL,'PORTARIA REGRAS','CONCIERGE','ACTIVE','{{}}'),
                ('{TA}','{DEPR}','{UA}','DEPENDENTE REGRAS','DEPENDENT','ACTIVE','{{}}'),
                ('{TA}','{RESR}','{UR}','MORADOR REGRAS','RESIDENT','ACTIVE','{{}}');
    """)
    concierge = "SET LOCAL app.capabilities='concierge.operate'; "
    today = "cp.tenant_today('" + TA + "')"
    def rule(identifier, visitor_id, days="ARRAY[1,2,3,4,5,6,7]::smallint[]", start="'00:00'", end="'23:59'", vfrom=today, vuntil=None, actor=AA, unit=UA):
        vuntil = vuntil or f"{today}+30"
        return (f"INSERT INTO cp.visitors(id,tenant_id,full_name,created_by) VALUES('{visitor_id}','{TA}','PRESTADOR FICTICIO','{actor}'); "
                f"INSERT INTO cp.visit_rules(id,tenant_id,unit_id,visitor_id,purpose,week_days,starts_local,ends_local,valid_from,valid_until,created_by) "
                f"VALUES('{identifier}','{TA}','{unit}','{visitor_id}','LIMPEZA',{days},{start},{end},{vfrom},{vuntil},'{actor}')")
    query(rule(R1, VR1), tenant=TA, role="cp_api")
    expect(value(f"select state||':'||version from cp.visit_rules where id='{R1}'") == "ACTIVE:0", "morador cria regra de prestador")
    expect(error(rule(R2, VR2, vfrom=f"{today}-1"), "23514", tenant=TA, role="cp_api"), "regra nao comeca no passado")
    expect(error(rule(R2, VR2, vuntil=f"{today}+181"), "23514", tenant=TA, role="cp_api"), "regra dura ate 180 dias")
    expect(error(rule(R2, VR2, days="ARRAY[1,1]::smallint[]"), "23514", tenant=TA, role="cp_api"), "dias sem repeticao")
    expect(error(rule(R2, VR2, days="ARRAY[8]::smallint[]"), "23514", tenant=TA, role="cp_api"), "dias de 1 a 7")
    expect(error(rule(R2, VR2, start="'10:00'", end="'09:00'"), "23514", tenant=TA, role="cp_api"), "fim depois do inicio")
    expect(error(rule(R2, VR2, actor=DEPR), "42501", tenant=TA, actor=DEPR, role="cp_api"), "dependente sem permissao nao cria regra")
    expect(error(rule(R2, VR2, unit=UR), "42501", tenant=TA, role="cp_api"), "morador nao cria regra para outra unidade")
    expect(value(f"select count(*) from cp.visit_rules where id='{R1}'", tenant=TA, actor=RESR, role="cp_api") == "0", "outra unidade nao ve a regra")
    # A regra sozinha nao autoriza: sem janela materializada nao ha convite.
    expect(value(f"select count(*) from cp.visit_permits where rule_id='{R1}'") == "0", "regra nao e convite")
    expect(value(f"select cp.expand_visit_rules('{TA}','{UA}',2)", tenant=TA, role="cp_api") == "3", "morador materializa hoje e os 2 proximos dias")
    expect(value(f"select cp.expand_visit_rules('{TA}','{UA}',2)", tenant=TA, role="cp_api") == "0", "expansao e idempotente")
    expect(value(f"select string_agg(((valid_from at time zone 'America/Sao_Paulo')::time)::text||'-'||((valid_until at time zone 'America/Sao_Paulo')::time)::text,',') "
                 f"from cp.visit_permits where rule_id='{R1}' and window_date={today}") == "00:00:00-23:59:00",
           "janela no horario local do condominio")
    expect(value(concierge + f"select cp.expand_visit_rules('{TA}',NULL,3)", tenant=TA, actor=CONR, role="cp_api") == "1",
           "portaria materializa o dia seguinte do horizonte")
    expect(value(f"select count(distinct created_by)||':'||min(created_by::text) from cp.visit_permits where rule_id='{R1}'") == f"1:{AA}",
           "janela fica em nome de quem criou a regra")
    expect(value(f"select count(*) from cp.visit_permits where id in (select id from cp.visit_permits where rule_id='{R1}')", tenant=TA, actor=RESR, role="cp_api") == "0",
           "outra unidade nao ve janelas")
    # Janela forjada (horario diferente da regra) nao entra, nem pela portaria.
    forged = (f"INSERT INTO cp.visit_permits(id,tenant_id,visitor_id,unit_id,purpose,created_by,valid_from,valid_until,rule_id,window_date) "
              f"VALUES(gen_random_uuid(),'{TA}','{VR1}','{UA}','LIMPEZA','{AA}',clock_timestamp(),clock_timestamp()+interval '30 days','{R1}',{today}+20)")
    expect(error(concierge + forged, "42501", tenant=TA, actor=CONR, role="cp_api"), "janela fora do horario da regra e recusada")
    # Regra so em dias que nao sao hoje: nada para hoje.
    query(rule(R3, VR3, days=f"ARRAY[((extract(isodow from {today})::int % 7)+1)]::smallint[]", vuntil=f"{today}+1"), tenant=TA, role="cp_api")
    query(f"select cp.expand_visit_rules('{TA}','{UA}',1)", tenant=TA, role="cp_api")
    expect(value(f"select count(*)||':'||coalesce(min(window_date-{today}),-1) from cp.visit_permits where rule_id='{R3}'") == "1:1",
           "dia fora da regra nao gera janela")
    expect(error(f"INSERT INTO cp.visit_permits(id,tenant_id,visitor_id,unit_id,purpose,created_by,valid_from,valid_until) "
                 f"VALUES(gen_random_uuid(),'{TA}','{VR1}','{UA}','X','{AA}',clock_timestamp(),clock_timestamp()+interval '1 hour')", "23514", tenant=TA, role="cp_api"),
           "pessoa da regra nao vira convite comum")
    # Revogacao individual de janela (trava do convite e depois a linha) concorrente com a revogacao da regra: sem deadlock.
    w3 = value(f"select id from cp.visit_permits where rule_id='{R3}' and state='ACTIVE' order by id limit 1")
    one = (f"SELECT cp.lock_permit_access('{TA}','{w3}'); SELECT pg_sleep(2); "
           f"UPDATE cp.visit_permits SET state='REVOKED',revoked_by='{AA}',revoke_reason='Hoje nao',version=version+1 WHERE id='{w3}' AND state='ACTIVE'")
    whole = f"UPDATE cp.visit_rules SET state='REVOKED',revoked_by='{AA}',revoke_reason='Encerrado',version=version+1 WHERE id='{R3}'"
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        single = pool.submit(query, one, tenant=TA, role="cp_api", check=False)
        time.sleep(0.7)
        bulk = pool.submit(query, whole, tenant=TA, role="cp_api", check=False)
        single, bulk = single.result(), bulk.result()
    expect(single.returncode == 0 and bulk.returncode == 0, "revogacao de janela e da regra concorrentes sem deadlock")
    expect(value(f"select count(*) from cp.visit_permits where rule_id='{R3}' and state='ACTIVE'") == "0", "nenhuma janela ativa depois das duas revogacoes")
    # Expansao e revogacao da mesma regra se serializam: nenhuma janela ativa sobra numa regra revogada.
    R4, VR4 = "77000000-0000-4000-8000-0000000000b4", "76000000-0000-4000-8000-0000000000b4"
    query(rule(R4, VR4), tenant=TA, role="cp_api")
    whole4 = f"UPDATE cp.visit_rules SET state='REVOKED',revoked_by='{AA}',revoke_reason='Encerrado',version=version+1 WHERE id='{R4}'"
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        expanding = pool.submit(query, f"SELECT cp.expand_visit_rules('{TA}','{UA}',2); SELECT pg_sleep(2)", tenant=TA, role="cp_api", check=False)
        time.sleep(0.7)
        revoking = pool.submit(query, whole4, tenant=TA, role="cp_api", check=False)
        expanded, revoked4 = expanding.result(), revoking.result()
    expect(expanded.returncode == 0 and revoked4.returncode == 0, "expansao e revogacao concorrentes terminam")
    expect(value(f"select count(*) filter(where state='ACTIVE')||':'||count(*) from cp.visit_permits where rule_id='{R4}'") == "0:3",
           "janelas geradas durante a revogacao sao revogadas junto")
    # Eventos de regra referem-se a regra visivel; id inexistente e recusado.
    expect(error(f"INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES('{TA}','VISIT_RULE_CREATED',gen_random_uuid(),0,'{{}}')",
                 "42501", tenant=TA, role="cp_api"), "evento de regra sem regra e recusado")
    query(f"INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES('{TA}','VISIT_RULE_CREATED','{R4}',0,'{{}}')",
          tenant=TA, role="cp_api")
    expect(value(f"select count(*) from cp.outbox where event_type='VISIT_RULE_CREATED' and aggregate_id='{R4}'") == "1", "evento de regra existente e aceito")
    # Outro adulto da unidade ve a pessoa da regra ainda sem janela (comeca alem do horizonte).
    ADR, R5, VR5 = "10000000-0000-4000-8000-0000000000b5", "77000000-0000-4000-8000-0000000000b5", "76000000-0000-4000-8000-0000000000b5"
    query(f"INSERT INTO platform.accounts(id,status) VALUES('{ADR}','ACTIVE'); "
          f"INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status,permissions) VALUES('{TA}','{ADR}','{UA}','ADULTO REGRAS','RESIDENT','ACTIVE','{{}}')")
    query(rule(R5, VR5, vfrom=f"{today}+30", vuntil=f"{today}+40"), tenant=TA, role="cp_api")
    expect(value(f"select count(*) from cp.visit_rules r join cp.visitors v on v.tenant_id=r.tenant_id and v.id=r.visitor_id where r.id='{R5}'",
                 tenant=TA, actor=ADR, role="cp_api") == "1", "outro adulto ve a regra e a pessoa antes da primeira janela")
    # Revogar a regra derruba as janelas validas na mesma transacao, com evento.
    def revoke(identifier, actor=AA):
        return (f"UPDATE cp.visit_rules SET state='REVOKED',revoked_by='{actor}',revoke_reason='Contrato encerrado',version=version+1 WHERE id='{identifier}'")
    expect(value(f"WITH u AS ({revoke(R1, actor=RESR)} RETURNING 1) SELECT count(*) FROM u", tenant=TA, actor=RESR, role="cp_api") == "0", "outra unidade nao revoga")
    query(revoke(R1), tenant=TA, role="cp_api")
    expect(value(f"select count(*) filter(where state='ACTIVE')||':'||count(*) filter(where revoke_reason='Regra de prestador revogada') "
                 f"from cp.visit_permits where rule_id='{R1}'") == "0:4", "revogar a regra revoga as janelas")
    expect(value(f"select count(*) from cp.outbox where event_type='VISIT_PERMIT_REVOKED' and payload->>'ruleId'='{R1}'") == "4", "um evento por janela revogada")
    expect(value(f"select cp.expand_visit_rules('{TA}','{UA}',3)", tenant=TA, role="cp_api") == "0", "regra revogada nao gera janela nova")
    expect(error(f"UPDATE cp.visit_rules SET state='ACTIVE',revoked_by=NULL,revoked_at=NULL,revoke_reason=NULL,version=version+1 WHERE id='{R1}'", "42501", tenant=TA, role="cp_api"),
           "regra revogada nao volta")
    expect(error(f"DELETE FROM cp.visit_rules WHERE id='{R3}'", "42501", tenant=TA, role="cp_api"), "aplicacao nao apaga regra")

def waitlist_checks():
    """V023: fila de espera ordenada; oferta cria HELD com prazo; duas ofertas nunca capturam o mesmo periodo."""
    W1, W2, W3, DEPW = ("10000000-0000-4000-8000-0000000000e1", "10000000-0000-4000-8000-0000000000e2",
                        "10000000-0000-4000-8000-0000000000e3", "10000000-0000-4000-8000-0000000000e4")
    UW1, UW2, UW3 = "20000000-0000-4000-8000-0000000000e1", "20000000-0000-4000-8000-0000000000e2", "20000000-0000-4000-8000-0000000000e3"
    AW = "30000000-0000-4000-8000-0000000000e1"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{W1}','ACTIVE'),('{W2}','ACTIVE'),('{W3}','ACTIVE'),('{DEPW}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UW1}','{TA}','FILA 1'),('{UW2}','{TA}','FILA 2'),('{UW3}','{TA}','FILA 3');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status,permissions)
          VALUES('{TA}','{W1}','{UW1}','FILA UM','RESIDENT','ACTIVE','{{}}'),('{TA}','{W2}','{UW2}','FILA DOIS','RESIDENT','ACTIVE','{{}}'),
                ('{TA}','{W3}','{UW3}','FILA TRES','RESIDENT','ACTIVE','{{}}'),('{TA}','{DEPW}','{UW1}','FILA DEP','DEPENDENT','ACTIVE','{{}}');
        INSERT INTO cp.amenities(id,tenant_id,name,capacity,approval_required) VALUES('{AW}','{TA}','AREA FILA',5,false);
    """)
    counter = [0]
    def slot(hours):
        return (f"date_trunc('hour',clock_timestamp())+interval '{hours} hours'", f"date_trunc('hour',clock_timestamp())+interval '{hours + 2} hours'")
    def booking(hours, unit=UA, actor=AA):
        counter[0] += 1
        rid = f"78000000-0000-4000-8000-{counter[0]:012d}"
        start, end = slot(hours)
        query(f"INSERT INTO cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot) "
              f"VALUES('{rid}','{TA}','{AW}','{unit}','{actor}','BOOKING','CONFIRMED',{start},{end},{start},{end},'{{\"approvalRequired\":false}}'::jsonb)",
              tenant=TA, actor=actor, role="cp_api")
        return rid
    def join(hours, unit, actor, approval="false", creator=None, quota=3, length=2):
        counter[0] += 1
        wid = f"79000000-0000-4000-8000-{counter[0]:012d}"
        start, _ = slot(hours)
        end = f"date_trunc('hour',clock_timestamp())+interval '{hours + length} hours'"
        return wid, (f"INSERT INTO cp.reservation_waitlist(id,tenant_id,amenity_id,unit_id,created_by,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot) "
                     f"VALUES('{wid}','{TA}','{AW}','{unit}','{creator or actor}',{start},{end},{start},{end},"
                     f"'{{\"approvalRequired\":{approval},\"policy\":{{\"maximumActivePerUnit\":{quota}}}}}'::jsonb)")
    offer = f"SELECT cp.waitlist_offer('{TA}','{AW}')"
    def entry(wid):
        return value(f"select state||':'||version from cp.reservation_waitlist where id='{wid}'")
    def cancel(rid, actor):
        query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{rid}'", tenant=TA, actor=actor, role="cp_api")

    r0 = booking(48)
    e1, sql = join(48, UW1, W1)
    query(sql, tenant=TA, actor=W1, role="cp_api")
    expect(entry(e1) == "WAITING:0", "unidade entra na fila de periodo ocupado")
    e2, sql = join(48, UW2, W2)
    query(sql, tenant=TA, actor=W2, role="cp_api")
    _, sql = join(48, UW1, W1)
    expect(error(sql, "23505", tenant=TA, actor=W1, role="cp_api"), "uma entrada aberta por unidade e periodo")
    _, sql = join(72, UW2, W1)
    expect(error(sql, "42501", tenant=TA, actor=W1, role="cp_api"), "nao entra na fila por outra unidade")
    _, sql = join(72, UW1, W1, creator=W2)
    expect(error(sql, "42501", tenant=TA, actor=W1, role="cp_api"), "nao entra na fila em nome de outra pessoa")
    _, sql = join(72, UW1, DEPW)
    expect(error(sql, "42501", tenant=TA, actor=DEPW, role="cp_api"), "dependente sem permissao de reservar nao entra na fila")
    _, sql = join(-1, UW1, W1)
    expect(error(sql, "23514", tenant=TA, actor=W1, role="cp_api"), "periodo ja iniciado nao entra na fila")
    expect(value(f"select count(*) from cp.reservation_waitlist where id='{e1}'", tenant=TA, actor=W2, role="cp_api") == "0",
           "outra unidade nao ve a entrada da fila")
    expect(error(f"INSERT INTO cp.reservations(tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,expires_at,rule_snapshot) "
                 f"VALUES('{TA}','{AW}','{UW1}','{W1}','BOOKING','HELD',{slot(96)[0]},{slot(96)[1]},{slot(96)[0]},{slot(96)[1]},{slot(96)[0]},'{{}}'::jsonb)",
                 "42501", tenant=TA, actor=W1, role="cp_api"), "retencao HELD so nasce pela oferta da fila")
    expect(value(offer, tenant=TA, actor=AA, role="cp_api") == "0", "periodo ocupado nao gera oferta")
    expect(value(f"select cp.waitlist_position('{TA}','{e2}')", tenant=TA, actor=W2, role="cp_api") == "2", "segunda unidade ve a propria posicao na fila")
    expect(value(f"select coalesce(cp.waitlist_position('{TA}','{e1}')::text,'null')", tenant=TA, actor=W2, role="cp_api") == "null",
           "posicao de entrada de outra unidade nao aparece")
    expect(value(f"select count(*) from cp.reservation_waitlist", tenant=TA, actor=W2, role="cp_api") == "1", "marca da posicao nao sobra depois")
    expect(entry(e1) == "WAITING:0" and entry(e2) == "WAITING:0", "fila continua aguardando")

    # Cancelamento libera o periodo: a oferta vai ao primeiro da fila, em nome dele, por ate 15 minutos.
    cancel(r0, AA)
    expect(value(offer, tenant=TA, actor=AA, role="cp_api") == "1", "periodo liberado gera uma oferta")
    expect(entry(e1) == "OFFERED:1" and entry(e2) == "WAITING:0", "oferta vai ao primeiro da fila")
    held1 = value(f"select offer_reservation_id from cp.reservation_waitlist where id='{e1}'")
    expect(value(f"select status||':'||created_by||':'||unit_id||':'||(expires_at<=clock_timestamp()+interval '15 minutes')::text from cp.reservations where id='{held1}'")
           == f"HELD:{W1}:{UW1}:true", "retencao HELD em nome de quem entrou na fila, com prazo de ate 15 minutos")
    expect(value(f"select count(*) from cp.outbox where event_type='WAITLIST_OFFERED' and aggregate_id='{e1}'") == "1", "evento da oferta no outbox")
    expect(value(f"select count(*) from cp.reservation_waitlist where id='{e1}'", tenant=TA, actor=AA, role="cp_api") == "0",
           "marca da oferta nao sobra depois")
    expect(value(offer, tenant=TA, actor=AA, role="cp_api") == "0", "periodo retido nao gera segunda oferta")
    expect(error(f"SELECT cp.waitlist_accept('{TA}','{e1}',1)", "42501", tenant=TA, actor=W2, role="cp_api"), "outra unidade nao aceita a oferta")
    expect(error(f"UPDATE cp.reservations SET status='CONFIRMED',version=version+1,expires_at=NULL WHERE id='{held1}'", "42501", tenant=TA, actor=W1, role="cp_api"),
           "confirmar a retencao sem o aceite da fila e recusado")

    # Oferta vencida avanca a fila sem dupla confirmacao.
    query(f"UPDATE cp.reservations SET expires_at=clock_timestamp()-interval '1 second' WHERE id='{held1}'")
    expect(value(offer, tenant=TA, actor=W3, role="cp_api") == "1", "oferta vencida avanca a fila")
    expect(entry(e1) == "EXPIRED:2" and value(f"select status from cp.reservations where id='{held1}'") == "EXPIRED", "oferta vencida encerra a entrada e a retencao")
    expect(error(f"SELECT cp.waitlist_accept('{TA}','{e1}',2)", "23514", tenant=TA, actor=W1, role="cp_api"), "oferta vencida nao e aceita")
    expect(entry(e2) == "OFFERED:1", "segundo da fila recebe a oferta")
    held2 = value(f"select offer_reservation_id from cp.reservation_waitlist where id='{e2}'")
    # Recusar (cancelar a retencao) encerra a entrada como LEFT e libera o periodo.
    cancel(held2, W2)
    expect(entry(e2) == "LEFT:2", "recusar a oferta encerra a entrada")
    expect(value(offer, tenant=TA, actor=W2, role="cp_api") == "0", "fila vazia nao gera oferta")
    expect(error(f"UPDATE cp.reservation_waitlist SET state='WAITING',version=version+1 WHERE id='{e2}'", "23514", tenant=TA, actor=W2, role="cp_api"),
           "entrada encerrada nao volta para a fila")

    # Aceitar: confirmada sem a capacidade de decidir; com aprovacao nas regras aceitas, vira pedido com prazo.
    r1 = booking(120)
    e3, sql = join(120, UW3, W3)
    query(sql, tenant=TA, actor=W3, role="cp_api")
    cancel(r1, AA)
    query(offer, tenant=TA, actor=AA, role="cp_api")
    expect(error(f"SELECT cp.waitlist_accept('{TA}','{e3}',0)", "23514", tenant=TA, actor=W3, role="cp_api"), "aceite com versao antiga e recusado")
    expect(value(f"SELECT cp.waitlist_accept('{TA}','{e3}',1)", tenant=TA, actor=W3, role="cp_api") == "CONFIRMED", "aceite confirma a reserva")
    held3 = value(f"select offer_reservation_id from cp.reservation_waitlist where id='{e3}'")
    expect(entry(e3) == "ACCEPTED:2" and value(f"select status||':'||coalesce(expires_at::text,'-') from cp.reservations where id='{held3}'") == "CONFIRMED:-",
           "entrada aceita e reserva confirmada")
    expect(error(f"SELECT cp.waitlist_accept('{TA}','{e3}',2)", "23514", tenant=TA, actor=W3, role="cp_api"), "aceite repetido nao confirma de novo")
    r2 = booking(144)
    e4, sql = join(144, UW1, W1, approval="true")
    query(sql, tenant=TA, actor=W1, role="cp_api")
    cancel(r2, AA)
    query(offer, tenant=TA, actor=AA, role="cp_api")
    expect(value(f"SELECT cp.waitlist_accept('{TA}','{e4}',1)", tenant=TA, actor=W1, role="cp_api") == "PENDING_APPROVAL",
           "regras aceitas com aprovacao viram pedido")
    held4 = value(f"select offer_reservation_id from cp.reservation_waitlist where id='{e4}'")
    expect(value(f"select (expires_at is not null and expires_at<=starts_at)::text from cp.reservations where id='{held4}'") == "true",
           "pedido da fila com prazo de decisao")

    # Entrada cujo periodo comecou sem vaga encerra; ofertas concorrentes nunca capturam o mesmo periodo.
    r3 = booking(168)
    e5, sql = join(168, UW2, W2)
    query(sql, tenant=TA, actor=W2, role="cp_api")
    query(f"ALTER TABLE cp.reservation_waitlist DISABLE TRIGGER reservation_waitlist_guard; "
          f"UPDATE cp.reservation_waitlist SET starts_at=now()-interval '1 minute',occupied_from=now()-interval '1 minute' WHERE id='{e5}'; "
          f"ALTER TABLE cp.reservation_waitlist ENABLE TRIGGER reservation_waitlist_guard")
    query(offer, tenant=TA, actor=AA, role="cp_api")
    expect(entry(e5) == "CLOSED:1", "periodo iniciado sem vaga encerra a entrada")
    r4 = booking(192)
    waits = []
    for unit, actor in ((UW1, W1), (UW2, W2), (UW3, W3)):
        wid, sql = join(192, unit, actor)
        query(sql, tenant=TA, actor=actor, role="cp_api")
        waits.append(wid)
    cancel(r4, AA)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(lambda _: query(offer, tenant=TA, actor=AA, role="cp_api", check=False), range(6)))
    expect(all(r.returncode == 0 for r in results), "ofertas concorrentes terminam sem erro")
    expect(value(f"select string_agg(state,',' order by created_at,id) from cp.reservation_waitlist where id in ('{waits[0]}','{waits[1]}','{waits[2]}')")
           == "OFFERED,WAITING,WAITING", "ofertas concorrentes: so o primeiro da fila recebe o periodo")
    start, end = slot(192)
    expect(value(f"select count(*) from cp.reservations where amenity_id='{AW}' and status in ('HELD','PENDING_APPROVAL','CONFIRMED') "
                 f"and starts_at={start}") == "1", "um unico ocupante do periodo")
    # Prioridade da fila: periodo livre com alguem aguardando bloqueia a reserva direta; ocupado segue como conflito comum.
    r6 = booking(216)
    e7, sql = join(216, UW1, W1)
    query(sql, tenant=TA, actor=W1, role="cp_api")
    start6, end6 = slot(216)
    blocks = f"SELECT cp.waitlist_blocks('{TA}','{AW}',{start6},{end6})::text"
    expect(value(blocks, tenant=TA, actor=W2, role="cp_api") == "false", "periodo ocupado nao e prioridade da fila (conflito comum)")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{r6}'")
    expect(value(blocks, tenant=TA, actor=W2, role="cp_api") == "true", "periodo liberado com fila aguardando bloqueia a reserva direta")
    expect(value(f"select count(*) from cp.reservation_waitlist where id='{e7}'", tenant=TA, actor=W2, role="cp_api") == "0",
           "marca da prioridade nao expoe a entrada")
    query(offer, tenant=TA, actor=W2, role="cp_api")
    expect(entry(e7) == "OFFERED:1" and value(blocks, tenant=TA, actor=W2, role="cp_api") == "false", "oferta feita: periodo retido volta a ser conflito comum")
    # Area desativada: a oferta nao acontece e a oferta ja feita nao e aceita.
    query(f"UPDATE cp.amenities SET active=false WHERE id='{AW}'")
    r7 = booking(240)
    e8, sql = join(240, UW3, W3)
    query(sql, tenant=TA, actor=W3, role="cp_api")
    cancel(r7, AA)
    expect(value(offer, tenant=TA, actor=AA, role="cp_api") == "0" and entry(e8) == "WAITING:0", "area inativa nao recebe oferta")
    expect(error(f"SELECT cp.waitlist_accept('{TA}','{e7}',1)", "23514", tenant=TA, actor=W1, role="cp_api"), "oferta de area inativa nao e aceita")
    query(f"UPDATE cp.amenities SET active=true WHERE id='{AW}'")
    query(f"UPDATE cp.reservation_waitlist SET state='LEFT',version=version+1 WHERE id='{e8}'", tenant=TA, actor=W3, role="cp_api")
    # Periodo liberado a poucos minutos do inicio ainda vai para a fila (a oferta vence no inicio).
    soon = "date_trunc('minute',clock_timestamp())+interval '3 minutes'"
    counter[0] += 1
    r5 = f"78000000-0000-4000-8000-{counter[0]:012d}"
    query(f"INSERT INTO cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot) "
          f"VALUES('{r5}','{TA}','{AW}','{UA}','{AA}','BOOKING','CONFIRMED',{soon},{soon}+interval '1 hour',{soon},{soon}+interval '1 hour','{{}}'::jsonb)",
          tenant=TA, role="cp_api")
    counter[0] += 1
    e6 = f"79000000-0000-4000-8000-{counter[0]:012d}"
    query(f"INSERT INTO cp.reservation_waitlist(id,tenant_id,amenity_id,unit_id,created_by,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot) "
          f"VALUES('{e6}','{TA}','{AW}','{UW3}','{W3}',{soon},{soon}+interval '1 hour',{soon},{soon}+interval '1 hour','{{\"approvalRequired\":false,\"policy\":{{\"maximumActivePerUnit\":3}}}}'::jsonb)",
          tenant=TA, actor=W3, role="cp_api")
    cancel(r5, AA)
    expect(value(offer, tenant=TA, actor=AA, role="cp_api") == "1" and entry(e6) == "OFFERED:1", "periodo liberado minutos antes do inicio vai para a fila")
    expect(value(f"select (expires_at=starts_at)::text from cp.reservations where id=(select offer_reservation_id from cp.reservation_waitlist where id='{e6}')") == "true",
           "oferta perto do inicio vence no inicio")
    expect(value(f"select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='cp' and c.relname='reservation_waitlist' "
                 f"and c.relrowsecurity and c.relforcerowsecurity") == "1", "fila com RLS completa")
    expect(value("select has_table_privilege('cp_api','cp.reservation_waitlist','DELETE')::text") == "false", "aplicacao nao apaga entradas da fila")
    # Worker (sem conta): a mesma passada anda a fila sem requisicao da API, so dentro da marca da oferta.
    held6 = value(f"select offer_reservation_id from cp.reservation_waitlist where id='{e6}'")
    r9 = booking(300)
    e9, sql = join(300, UW2, W2)
    query(sql, tenant=TA, actor=W2, role="cp_api")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{r9}'")
    query(f"UPDATE cp.reservations SET expires_at=clock_timestamp()-interval '1 second' WHERE id='{held6}'")
    expect(value(offer, tenant=TA, actor=None, role="cp_worker") == "1", "worker oferece periodo liberado sem requisicao")
    expect(entry(e9) == "OFFERED:1" and value(f"select status||':'||created_by from cp.reservations where id=(select offer_reservation_id from cp.reservation_waitlist where id='{e9}')")
           == f"HELD:{W2}", "retencao do worker em nome de quem entrou na fila")
    expect(entry(e6) == "EXPIRED:2" and value(f"select status from cp.reservations where id='{held6}'") == "EXPIRED", "worker vence oferta nao aceita")
    expect(value(f"select count(*) from cp.outbox where event_type in ('WAITLIST_OFFERED','RESERVATION_EXPIRED') and aggregate_id in ('{e9}','{held6}')") == "2",
           "eventos da passada do worker no outbox")
    expect(value("select count(*) from cp.reservation_waitlist", tenant=TA, actor=None, role="cp_worker") == "0", "worker nao le a fila fora da oferta")
    expect(error(f"UPDATE cp.reservation_waitlist SET state='CLOSED',version=version+1 WHERE id='{e9}'", "42501", tenant=TA, actor=None, role="cp_worker")
           or value(f"select state from cp.reservation_waitlist where id='{e9}'") == "OFFERED", "worker nao altera a fila fora da oferta")
    expect(error(f"INSERT INTO cp.reservations(tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot) "
                 f"VALUES('{TA}','{AW}','{UA}','{AA}','BOOKING','CONFIRMED',now()+interval '400 hours',now()+interval '401 hours',now()+interval '400 hours',"
                 f"now()+interval '401 hours','{{}}'::jsonb)", "42501", tenant=TA, actor=None, role="cp_worker"), "worker nao cria reserva fora da oferta")
    expect(error(f"INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES('{TA}','WAITLIST_OFFERED','{e9}',9,'{{}}')",
                 "42501", tenant=TA, actor=None, role="cp_worker"), "worker nao grava evento fora da oferta")
    expect(error(f"SELECT cp.waitlist_offer('{TB}','{AW}')", "42501", tenant=TA, actor=None, role="cp_worker"), "worker nao oferece em outro condominio")
    # Autor sem vinculo elegivel nao segura o periodo: a entrada e encerrada e a oferta vai ao proximo (API e worker).
    for role, actor, hours in (("cp_api", AA, 340), ("cp_worker", None, 380)):
        rb = booking(hours)
        eb1, sql = join(hours, UW1, DEPW, creator=DEPW)
        query(f"UPDATE cp.memberships SET permissions='{{\"reservations\":{{\"create\":true}}}}'::jsonb WHERE account_id='{DEPW}'")
        query(sql, tenant=TA, actor=DEPW, role="cp_api")
        eb2, sql = join(hours, UW3, W3)
        query(sql, tenant=TA, actor=W3, role="cp_api")
        query(f"UPDATE cp.memberships SET permissions='{{}}'::jsonb WHERE account_id='{DEPW}'")
        query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{rb}'")
        expect(value(offer, tenant=TA, actor=actor, role=role) == "1", f"oferta pula autor sem vinculo elegivel ({role})")
        expect(entry(eb1) == "CLOSED:1" and entry(eb2) == "OFFERED:1", f"entrada do autor inelegivel encerrada e proximo ofertado ({role})")
        query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id=(select offer_reservation_id from cp.reservation_waitlist where id='{eb2}')")
    expect(value("select count(*) from cp.memberships", tenant=TA, actor=W2, role="cp_api") == "1", "fora da oferta a API segue lendo so o proprio vinculo")
    # Cota da unidade pelas regras aceitas: unidade no limite continua aguardando e o proximo recebe a oferta.
    rq = booking(420)
    own = booking(500, unit=UW1, actor=W1)
    def active(unit):
        return int(value(f"select count(*) from cp.reservations where unit_id='{unit}' and kind='BOOKING' and status in ('HELD','PENDING_APPROVAL','CONFIRMED') "
                         f"and ends_at>clock_timestamp() and not (status in ('HELD','PENDING_APPROVAL') and expires_at<=clock_timestamp())"))
    eq1, sql = join(420, UW1, W1, quota=active(UW1))
    query(sql, tenant=TA, actor=W1, role="cp_api")
    eq2, sql = join(420, UW2, W2, quota=active(UW2) + 1)
    query(sql, tenant=TA, actor=W2, role="cp_api")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{rq}'")
    expect(value(offer, tenant=TA, actor=AA, role="cp_api") == "1" and entry(eq1) == "WAITING:0" and entry(eq2) == "OFFERED:1",
           "unidade no limite da cota nao recebe oferta; o proximo recebe")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id in ('{own}',(select offer_reservation_id from cp.reservation_waitlist where id='{eq2}'))")
    expect(value(offer, tenant=TA, actor=AA, role="cp_api") == "1" and entry(eq1) == "OFFERED:1", "com a cota liberada a unidade recebe a oferta")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id=(select offer_reservation_id from cp.reservation_waitlist where id='{eq1}')")
    # Oferta sob a trava da unidade ocupada (reserva direta em andamento): a passada para, sem impasse nem pular a ordem.
    rl = booking(460)
    el1, sql = join(460, UW1, W1, quota=active(UW1) + 1)
    query(sql, tenant=TA, actor=W1, role="cp_api")
    el2, sql = join(460, UW2, W2, quota=active(UW2) + 1)
    query(sql, tenant=TA, actor=W2, role="cp_api")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{rl}'")
    holder = subprocess.Popen(["psql", "-X", "-q", "-c", f"BEGIN; SELECT pg_advisory_xact_lock(hashtextextended('cp.reservations:{TA}:{UW1}',0)); "
                               "SELECT pg_sleep(4); COMMIT;"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.5)
    started = time.monotonic()
    blocked = value(offer, tenant=TA, actor=AA, role="cp_api")
    expect(blocked == "0" and time.monotonic() - started < 2 and entry(el1) == "WAITING:0" and entry(el2) == "WAITING:0",
           "trava da unidade ocupada encerra a passada sem esperar e sem pular a ordem da fila")
    holder.wait(timeout=30)
    expect(value(offer, tenant=TA, actor=AA, role="cp_api") == "1" and entry(el1) == "OFFERED:1" and entry(el2) == "WAITING:0",
           "liberada a trava, o primeiro da fila recebe a oferta")
    # Aceite em curso trava a entrada antes da retencao; a expiracao trava a retencao antes da entrada (gatilho).
    # A passada pula a retencao cuja entrada esta ocupada, sem esperar (sem impasse), e expira na passada seguinte.
    query(f"UPDATE cp.reservations SET expires_at=clock_timestamp()-interval '1 second' WHERE id=(select offer_reservation_id from cp.reservation_waitlist where id='{el1}')")
    holder = subprocess.Popen(["psql", "-X", "-q", "-c", f"BEGIN; SELECT 1 FROM cp.reservation_waitlist WHERE id='{el1}' FOR UPDATE; "
                               "SELECT pg_sleep(4); COMMIT;"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.5)
    started = time.monotonic()
    skipped = value(offer, tenant=TA, actor=AA, role="cp_api")
    held_status = value(f"SELECT r.status FROM cp.reservations r JOIN cp.reservation_waitlist w ON w.offer_reservation_id=r.id WHERE w.id='{el1}'")
    expect(skipped == "0" and time.monotonic() - started < 2 and held_status == "HELD" and entry(el1) == "OFFERED:1",
           "entrada travada por aceite em curso: a expiracao pula a retencao sem esperar")
    holder.wait(timeout=30)
    expect(value(offer, tenant=TA, actor=AA, role="cp_api") == "1" and entry(el1) == "EXPIRED:2" and entry(el2) == "OFFERED:1",
           "liberada a entrada, a passada expira a retencao e oferece ao proximo")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id=(select offer_reservation_id from cp.reservation_waitlist where id='{el2}')")
    # Entradas sem cota nao ocupam o lote da passada: alem das 50 primeiras, a elegivel ainda recebe a oferta.
    query(f"UPDATE cp.reservation_waitlist SET state='LEFT',version=version+1 WHERE amenity_id='{AW}' AND state='WAITING'")
    full = active(UW1)
    for index in range(52):
        rb = booking(700 + index * 3)
        eb, sql = join(700 + index * 3, UW1, W1, quota=full)
        query(sql, tenant=TA, actor=W1, role="cp_api")
        query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{rb}'")
    rlast = booking(900)
    elast, sql = join(900, UW2, W2, quota=active(UW2) + 1)
    query(sql, tenant=TA, actor=W2, role="cp_api")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{rlast}'")
    expect(value(offer, tenant=TA, actor=AA, role="cp_api") == "1" and entry(elast) == "OFFERED:1",
           "entradas de unidade sem cota nao impedem a oferta a quem vem depois das 50 primeiras")
    s0, s1 = f"date_trunc('hour',clock_timestamp())+interval '703 hours'", f"date_trunc('hour',clock_timestamp())+interval '704 hours'"
    expect(value(f"SELECT cp.waitlist_blocks('{TA}','{AW}',{s0},{s1})::text", tenant=TA, actor=AA, role="cp_api") == "false",
           "quem aguarda sem cota nao bloqueia a reserva direta do periodo liberado")
    query(f"UPDATE cp.reservation_waitlist SET state='LEFT',version=version+1 WHERE unit_id='{UW1}' AND state='WAITING'", tenant=TA, actor=W1, role="cp_api")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id=(select offer_reservation_id from cp.reservation_waitlist where id='{elast}')")
    # Bloqueio da reserva direta so por quem aguarda periodo inteiro livre.
    rp = booking(600)  # 600-602 ocupado
    ep, sql = join(599, UW3, W3, length=3)  # aguarda 599-602, ainda ocupado em parte
    query(sql, tenant=TA, actor=W3, role="cp_api")
    s0, s1 = f"date_trunc('hour',clock_timestamp())+interval '599 hours'", f"date_trunc('hour',clock_timestamp())+interval '600 hours'"
    expect(value(f"SELECT cp.waitlist_blocks('{TA}','{AW}',{s0},{s1})::text", tenant=TA, actor=AA, role="cp_api") == "false",
           "entrada de periodo ainda ocupado nao bloqueia o horario livre ao lado")
    query(f"UPDATE cp.reservations SET status='CANCELLED',version=version+1 WHERE id='{rp}'")
    expect(value(f"SELECT cp.waitlist_blocks('{TA}','{AW}',{s0},{s1})::text", tenant=TA, actor=AA, role="cp_api") == "true",
           "com o periodo aguardado livre, a fila bloqueia a reserva direta sobreposta")

def delegation_checks():
    """V024: retirada delegada com codigo de 8 digitos por encomenda; 5 tentativas; codigo usado nao volta a valer."""
    RD, DD, CD, RO = ("10000000-0000-4000-8000-0000000001f1", "10000000-0000-4000-8000-0000000001f2",
                      "10000000-0000-4000-8000-0000000001f3", "10000000-0000-4000-8000-0000000001f4")
    UD, UO = "20000000-0000-4000-8000-0000000001f1", "20000000-0000-4000-8000-0000000001f2"
    Q1, Q2, Q3, Q4 = ("40000000-0000-4000-8000-0000000001f1", "40000000-0000-4000-8000-0000000001f2",
                      "40000000-0000-4000-8000-0000000001f3", "40000000-0000-4000-8000-0000000001f4")
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{RD}','ACTIVE'),('{DD}','ACTIVE'),('{CD}','ACTIVE'),('{RO}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UD}','{TA}','DELEGACAO 1'),('{UO}','{TA}','DELEGACAO 2');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status,permissions)
          VALUES('{TA}','{RD}','{UD}','MORADOR DELEGA','RESIDENT','ACTIVE','{{}}'),
                ('{TA}','{DD}','{UD}','DEPENDENTE DELEGA','DEPENDENT','ACTIVE','{{"parcels":{{"view":true}}}}'),
                ('{TA}','{CD}',NULL,'PORTARIA DELEGA','CONCIERGE','ACTIVE','{{}}'),
                ('{TA}','{RO}','{UO}','OUTRA UNIDADE DELEGA','RESIDENT','ACTIVE','{{}}');
        INSERT INTO cp.parcels(id,tenant_id,unit_id,quantity,description,storage_location,received_by)
          VALUES('{Q1}','{TA}','{UD}',1,'CAIXA 1','A1','{CD}'),('{Q2}','{TA}','{UD}',1,'CAIXA 2','A2','{CD}'),
                ('{Q3}','{TA}','{UD}',1,'CAIXA 3','A3','{CD}'),('{Q4}','{TA}','{UO}',1,'CAIXA 4','A4','{CD}');
    """)
    # O codigo e protegido por HMAC com segredo do servidor (fora do banco): o banco so compara o autenticador.
    secret = bytes(range(32))
    def mac(did, code):
        return hmac.new(secret, f"{did}:{code}".encode(), hashlib.sha256).hexdigest()
    counter = [0]
    def delegate(parcel, unit=UD, code="12345678", until="clock_timestamp()+interval '2 days'", name="TERCEIRO FICTICIO"):
        counter[0] += 1
        did = f"41000000-0000-4000-8000-{counter[0]:012d}"
        return did, (f"INSERT INTO cp.parcel_delegations(id,tenant_id,parcel_id,unit_id,delegate_name,document_last4,code_hmac,valid_until,created_by) "
                     f"VALUES('{did}','{TA}','{parcel}','{unit}','{name}','9Z','{mac(did, code)}',{until},platform.current_actor_id())")
    concierge = "SET LOCAL app.capabilities='concierge.operate'; "
    def check(parcel, code, did=None, candidate=None):
        did = did or value(f"select coalesce(max(id::text),'41000000-0000-4000-8000-ffffffffffff') from cp.parcel_delegations "
                           f"where parcel_id='{parcel}' and state='ACTIVE'")
        candidate = candidate if candidate is not None else mac(did, code)
        return value(concierge + f"SELECT cp.parcel_delegation_check('{TA}','{parcel}','{did}','{candidate}')", tenant=TA, actor=CD, role="cp_api")
    def state(did):
        return value(f"select state||':'||attempts from cp.parcel_delegations where id='{did}'")

    d1, sql = delegate(Q1)
    query(sql, tenant=TA, actor=RD, role="cp_api")
    expect(state(d1) == "ACTIVE:0", "morador autoriza retirada por terceiro")
    _, sql = delegate(Q1)
    expect(error(sql, "23505", tenant=TA, actor=RD, role="cp_api"), "uma delegacao em vigor por encomenda")
    _, sql = delegate(Q2)
    expect(error(sql, "42501", tenant=TA, actor=DD, role="cp_api"), "dependente sem permissao de retirada nao delega")
    _, sql = delegate(Q4, unit=UO)
    expect(error(sql, "42501", tenant=TA, actor=RD, role="cp_api"), "nao delega encomenda de outra unidade")
    _, sql = delegate(Q2, unit=UO)
    expect(error(sql, "42501", tenant=TA, actor=RO, role="cp_api"), "unidade da delegacao precisa ser a da encomenda")
    _, sql = delegate(Q2, until="clock_timestamp()+interval '8 days'")
    expect(error(sql, "23514", tenant=TA, actor=RD, role="cp_api"), "validade de ate 7 dias")
    expect(value(f"select count(*) from cp.parcel_delegations where id='{d1}'", tenant=TA, actor=RO, role="cp_api") == "0",
           "outra unidade nao ve a delegacao")
    expect(value(f"select count(*) from cp.parcel_delegations where id='{d1}'", tenant=TA, actor=DD, role="cp_api") == "1",
           "quem ve as encomendas da unidade acompanha a delegacao")
    expect(error(f"SELECT cp.parcel_delegation_check('{TA}','{Q1}','{d1}','{mac(d1, '12345678')}')", "42501", tenant=TA, actor=RD, role="cp_api"),
           "somente a portaria confere o codigo")
    expect(value("select count(*) from information_schema.columns where table_schema='cp' and table_name='parcel_delegations' "
                 "and column_name in ('code_salt','code_hash')") == "0", "banco nao guarda sal nem hash simples do codigo")
    plain = value("select encode(sha256(convert_to('12345678','UTF8')),'hex')")
    expect(check(Q1, "12345678", candidate=plain) == "WRONG" and state(d1) == "ACTIVE:1",
           "sem o segredo do servidor o codigo nao confere (tentativa contada)")
    expect(check(Q1, "12345678", did="41000000-0000-4000-8000-ffffffffffff") == "CHANGED" and state(d1) == "ACTIVE:1",
           "autenticador de outra delegacao nao conta tentativa nem confere")
    # Codigo da encomenda Q1 nao vale para outra encomenda (sem delegacao em Q2).
    expect(check(Q2, "12345678") == "NONE", "delegacao de outra encomenda nao e aceita")
    expect(check(Q1, "00000000") == "WRONG" and state(d1) == "ACTIVE:2", "codigo errado conta tentativa")
    expect(check(Q1, "abc", candidate="abc") == "WRONG" and state(d1) == "ACTIVE:3", "formato invalido tambem conta tentativa")
    expect(check(Q1, "12345678") == "OK" and state(d1) == "USED:3", "codigo certo usa a delegacao")
    expect(value(f"select used_by from cp.parcel_delegations where id='{d1}'") == CD, "uso em nome da portaria")
    expect(check(Q1, "12345678") == "NONE", "codigo usado nao volta a valer")
    # Retirada por delegacao: registro em nome da portaria com o nome da pessoa autorizada.
    deliver = (concierge + f"UPDATE cp.parcels SET status='DELIVERED',version=version+1 WHERE id='{{p}}'; "
               f"INSERT INTO cp.parcel_handoffs(tenant_id,parcel_id,operator_id,recipient_name,evidence_kind) "
               f"VALUES('{TA}','{{p}}','{CD}','{{name}}','DELEGATION')")
    expect(error(deliver.format(p=Q1, name="OUTRO NOME"), "42501", tenant=TA, actor=CD, role="cp_api"),
           "retirada por delegacao exige o nome autorizado")
    query(deliver.format(p=Q1, name="TERCEIRO FICTICIO"), tenant=TA, actor=CD, role="cp_api")
    expect(value(f"select evidence_kind||':'||recipient_name from cp.parcel_handoffs where parcel_id='{Q1}'") == "DELEGATION:TERCEIRO FICTICIO",
           "retirada registrada por delegacao")
    expect(error(deliver.format(p=Q2, name="TERCEIRO FICTICIO"), "42501", tenant=TA, actor=CD, role="cp_api"),
           "sem delegacao usada nao ha retirada por delegacao")
    # Cinco tentativas erradas bloqueiam; bloqueada nao aceita nem o codigo certo.
    d2, sql = delegate(Q2, code="87654321")
    query(sql, tenant=TA, actor=RD, role="cp_api")
    for attempt in range(4):
        expect(check(Q2, f"1111111{attempt}") == "WRONG", f"tentativa errada {attempt + 1}")
    expect(check(Q2, "11111119") == "LOCKED" and state(d2) == "LOCKED:5", "quinta tentativa errada bloqueia")
    expect(check(Q2, "87654321") == "NONE", "delegacao bloqueada nao aceita o codigo certo")
    expect(error(f"UPDATE cp.parcel_delegations SET attempts=0,version=version+1 WHERE id='{d2}'", "23514", tenant=TA, actor=RD, role="cp_api"),
           "morador nao zera tentativas")
    # Vencida: nao usa; nova delegacao possivel depois de encerrar a anterior.
    d3, sql = delegate(Q3)
    query(sql, tenant=TA, actor=RD, role="cp_api")
    query(f"ALTER TABLE cp.parcel_delegations DISABLE TRIGGER parcel_delegations_guard; "
          f"UPDATE cp.parcel_delegations SET valid_until=clock_timestamp()-interval '1 minute' WHERE id='{d3}'; "
          f"ALTER TABLE cp.parcel_delegations ENABLE TRIGGER parcel_delegations_guard")
    expect(check(Q3, "12345678") == "EXPIRED" and state(d3) == "ACTIVE:0", "delegacao vencida nao e usada")
    expect(check(Q3, "12345678", did="41000000-0000-4000-8000-ffffffffffff") == "EXPIRED" and state(d3) == "ACTIVE:0",
           "vencida responde vencida mesmo sem a delegacao lida (nao como alterada)")
    query(f"UPDATE cp.parcel_delegations SET state='REVOKED',revoked_by='{RD}',version=version+1 WHERE id='{d3}'", tenant=TA, actor=RD, role="cp_api")
    expect(state(d3) == "REVOKED:0", "morador revoga a delegacao")
    expect(error(f"UPDATE cp.parcel_delegations SET state='ACTIVE',version=version+1 WHERE id='{d3}'", "23514", tenant=TA, actor=RD, role="cp_api"),
           "delegacao encerrada nao volta")
    # Retirada assistida (outro caminho) encerra a delegacao em vigor na mesma transacao.
    d4, sql = delegate(Q3)
    query(sql, tenant=TA, actor=RD, role="cp_api")
    member = value(f"select id from cp.memberships where account_id='{RD}'")
    query(concierge + f"UPDATE cp.parcels SET status='DELIVERED',version=version+1 WHERE id='{Q3}'; "
          f"INSERT INTO cp.parcel_handoffs(tenant_id,parcel_id,operator_id,recipient_name,recipient_membership_id,evidence_kind) "
          f"VALUES('{TA}','{Q3}','{CD}','MORADOR DELEGA','{member}','ASSISTED')", tenant=TA, actor=CD, role="cp_api")
    expect(state(d4) == "CLOSED:0", "retirada por outro caminho encerra a delegacao")
    _, sql = delegate(Q3)
    expect(error(sql, "42501", tenant=TA, actor=RD, role="cp_api"), "encomenda retirada nao recebe delegacao")
    expect(value("select has_table_privilege('cp_api','cp.parcel_delegations','DELETE')::text") == "false", "aplicacao nao apaga delegacoes")
    expect(value("select has_column_privilege('cp_api','cp.parcel_delegations','code_hmac','UPDATE')::text") == "false",
           "aplicacao nao troca o codigo de uma delegacao")

def move_checks():
    """V025: mudanca com regras configuradas pela gestao; decisao com nota; portaria ve so aprovadas."""
    MG, RM, DM, CM, RO = ("10000000-0000-4000-8000-0000000002a1", "10000000-0000-4000-8000-0000000002a2", "10000000-0000-4000-8000-0000000002a3",
                          "10000000-0000-4000-8000-0000000002a4", "10000000-0000-4000-8000-0000000002a5")
    UM, UO = "20000000-0000-4000-8000-0000000002a1", "20000000-0000-4000-8000-0000000002a2"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MG}','ACTIVE'),('{RM}','ACTIVE'),('{DM}','ACTIVE'),('{CM}','ACTIVE'),('{RO}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UM}','{TA}','MUDANCA 1'),('{UO}','{TA}','MUDANCA 2');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{MG}',NULL,'GESTAO MUDANCA','MANAGER','ACTIVE'),('{TA}','{RM}','{UM}','MORADOR MUDANCA','RESIDENT','ACTIVE'),
                ('{TA}','{DM}','{UM}','DEPENDENTE MUDANCA','DEPENDENT','ACTIVE'),('{TA}','{CM}',NULL,'PORTARIA MUDANCA','CONCIERGE','ACTIVE'),
                ('{TA}','{RO}','{UO}','OUTRA MUDANCA','RESIDENT','ACTIVE');
    """)
    manager = "SET LOCAL app.capabilities='requests.manage'; "
    concierge = "SET LOCAL app.capabilities='concierge.operate'; "
    rules = (f"INSERT INTO cp.move_rules(tenant_id,rules_text,week_days,starts_local,ends_local,min_notice_hours,max_duration_minutes,updated_by) "
             f"VALUES('{TA}','Elevador de servico com protecao; portaria avisada.',ARRAY[1,2,3,4,5,6,7]::smallint[],'08:00','18:00',24,240,'{MG}')")
    day = "(date_trunc('day',clock_timestamp() AT TIME ZONE 'America/Sao_Paulo')+interval '3 days')"
    def at(hour, minutes=0):
        return f"(({day}+interval '{hour} hours {minutes} minutes') AT TIME ZONE 'America/Sao_Paulo')"
    counter = [0]
    def request(unit=UM, start=None, end=None, version=1, actor=RM):
        counter[0] += 1
        mid = f"42000000-0000-4000-8000-{counter[0]:012d}"
        return mid, (f"INSERT INTO cp.move_requests(id,tenant_id,unit_id,requester_id,kind,starts_at,ends_at,provider_name,vehicle_plate,items,rules_version) "
                     f"VALUES('{mid}','{TA}','{unit}','{actor}','MOVE_IN',{start or at(9)},{end or at(12)},'TRANSPORTADORA FICTICIA','ABC1D23','Sofa e caixas',{version})")
    _, sql = request()
    expect(error(sql, "23514", tenant=TA, actor=RM, role="cp_api"), "sem regras configuradas nao ha pedido de mudanca")
    expect(error(rules, "42501", tenant=TA, actor=RM, role="cp_api"), "morador nao configura regras")
    query(manager + rules, tenant=TA, actor=MG, role="cp_api")
    m1, sql = request()
    query(sql, tenant=TA, actor=RM, role="cp_api")
    expect(value(f"select state||':'||version from cp.move_requests where id='{m1}'") == "REQUESTED:0", "morador pede mudanca dentro das regras")
    for label, start, end in (("antes do horario", at(7), at(9)), ("depois do horario", at(16), at(19)), ("mais longo que o limite", at(9), at(14)),
                              ("sem antecedencia minima", "clock_timestamp()+interval '2 hours'", "clock_timestamp()+interval '3 hours'")):
        _, sql = request(start=start, end=end)
        expect(error(sql, "23514", tenant=TA, actor=RM, role="cp_api"), f"periodo {label} e recusado")
    _, sql = request(version=2)
    expect(error(sql, "23514", tenant=TA, actor=RM, role="cp_api"), "versao de regras diferente e recusada")
    _, sql = request(actor=DM)
    expect(error(sql, "42501", tenant=TA, actor=DM, role="cp_api"), "dependente nao pede mudanca")
    _, sql = request(unit=UO)
    expect(error(sql, "42501", tenant=TA, actor=RM, role="cp_api"), "morador nao pede para outra unidade")
    expect(value(f"select count(*) from cp.move_requests where id='{m1}'", tenant=TA, actor=RO, role="cp_api") == "0", "outra unidade nao ve o pedido")
    expect(value(concierge + f"select count(*) from cp.move_requests where id='{m1}'", tenant=TA, actor=CM, role="cp_api") == "0",
           "portaria nao ve pedido nao aprovado")
    expect(error(f"UPDATE cp.move_requests SET state='APPROVED',decided_by='{RM}',version=version+1 WHERE id='{m1}'", "42501", tenant=TA, actor=RM, role="cp_api"),
           "morador nao aprova o proprio pedido")
    query(manager + f"UPDATE cp.move_requests SET state='APPROVED',decided_by='{MG}',decision_note='Use a garagem',version=version+1 WHERE id='{m1}'",
          tenant=TA, actor=MG, role="cp_api")
    expect(value(f"select state||':'||(decided_at is not null)::text from cp.move_requests where id='{m1}'") == "APPROVED:true", "gestao aprova com nota")
    expect(value(concierge + f"select count(*) from cp.move_requests where id='{m1}'", tenant=TA, actor=CM, role="cp_api") == "1",
           "portaria ve a mudanca aprovada")
    expect(error(manager + f"UPDATE cp.move_requests SET state='REJECTED',decided_by='{MG}',decision_note='x',version=version+1 WHERE id='{m1}'",
                 "23514", tenant=TA, actor=MG, role="cp_api"), "aprovada nao volta a ser decidida")
    expect(error(manager + f"UPDATE cp.move_requests SET starts_at=starts_at+interval '1 hour',version=version+1 WHERE id='{m1}'",
                 "42501", tenant=TA, actor=MG, role="cp_api"), "periodo nao muda depois do pedido")
    query(f"UPDATE cp.move_requests SET state='CANCELLED',cancelled_by='{RM}',version=version+1 WHERE id='{m1}'", tenant=TA, actor=RM, role="cp_api")
    expect(value(f"select state from cp.move_requests where id='{m1}'") == "CANCELLED", "morador retira a mudanca antes do inicio")
    m2, sql = request(start=at(13), end=at(15))
    query(sql, tenant=TA, actor=RM, role="cp_api")
    expect(error(manager + f"UPDATE cp.move_requests SET state='REJECTED',decided_by='{MG}',version=version+1 WHERE id='{m2}'", "23514", tenant=TA, actor=MG, role="cp_api"),
           "rejeicao exige nota")
    # Regras mudam de versao: pedidos ja feitos guardam a versao aceita; novos precisam da nova.
    query(manager + f"UPDATE cp.move_rules SET rules_text='Novas regras',version=version+1,updated_by='{MG}' WHERE tenant_id='{TA}'", tenant=TA, actor=MG, role="cp_api")
    _, sql = request(start=at(10), end=at(11))
    expect(error(sql, "23514", tenant=TA, actor=RM, role="cp_api"), "pedido com a versao antiga das regras e recusado")
    expect(value(f"select rules_version from cp.move_requests where id='{m2}'") == "1", "pedido guarda a versao aceita")
    expect(value("select has_table_privilege('cp_api','cp.move_requests','DELETE')::text") == "false", "aplicacao nao apaga pedidos de mudanca")
def credential_checks():
    """V026: estado desejado versionado; revogacao final; confirmacao de versao antiga nao reativa credencial revogada."""
    MG, RS, OT, CN, OW = ("10000000-0000-4000-8000-0000000002c1", "10000000-0000-4000-8000-0000000002c2", "10000000-0000-4000-8000-0000000002c3",
                          "10000000-0000-4000-8000-0000000002c4", "10000000-0000-4000-8000-0000000002c5")
    U1, U2 = "20000000-0000-4000-8000-0000000002c1", "20000000-0000-4000-8000-0000000002c2"
    MR, MO, MW = "51000000-0000-4000-8000-0000000002c1", "51000000-0000-4000-8000-0000000002c2", "51000000-0000-4000-8000-0000000002c3"
    K1, K2, KD = "43000000-0000-4000-8000-0000000002c1", "43000000-0000-4000-8000-0000000002c2", "43000000-0000-4000-8000-0000000002c3"
    D1, D2, DX = "44000000-0000-4000-8000-0000000002c1", "44000000-0000-4000-8000-0000000002c2", "44000000-0000-4000-8000-0000000002c3"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MG}','ACTIVE'),('{RS}','ACTIVE'),('{OT}','ACTIVE'),('{CN}','ACTIVE'),('{OW}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{U1}','{TA}','CREDENCIAL 1'),('{U2}','{TA}','CREDENCIAL 2');
        INSERT INTO cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status)
          VALUES(gen_random_uuid(),'{TA}','{MG}',NULL,'GESTAO CREDENCIAL','MANAGER','ACTIVE'),('{MR}','{TA}','{RS}','{U1}','MORADOR CREDENCIAL','RESIDENT','ACTIVE'),
                ('{MO}','{TA}','{OT}','{U2}','OUTRO CREDENCIAL','RESIDENT','ACTIVE'),(gen_random_uuid(),'{TA}','{CN}',NULL,'PORTARIA CREDENCIAL','CONCIERGE','ACTIVE'),
                ('{MW}','{TA}','{OW}','{U2}','PROPRIETARIO CREDENCIAL','OWNER','ACTIVE');
        INSERT INTO cp.access_connectors(id,tenant_id,provider,label) VALUES('{K1}','{TA}','SIMULATED','Simulador'),('{K2}','{TA}','SIMULATED','Outro simulador');
        INSERT INTO cp.access_connectors(id,tenant_id,provider,label,status) VALUES('{KD}','{TA}','SIMULATED','Desligado','DISABLED');
        INSERT INTO cp.access_devices(id,tenant_id,connector_id,external_id,label,direction)
          VALUES('{D1}','{TA}','{K1}','leitor-1','Entrada social','ENTRY'),('{D2}','{TA}','{K1}','leitor-2','Garagem','BOTH'),('{DX}','{TA}','{K2}','leitor-x','Outro','ENTRY');
    """)
    manager = "SET LOCAL app.capabilities='memberships.manage'; "
    concierge = "SET LOCAL app.capabilities='concierge.operate'; "
    counter = [0]
    def create(membership=MR, unit=U1, connector=K1, kind="CARD", actor=MG):
        counter[0] += 1
        cid = f"45000000-0000-4000-8000-{counter[0]:012d}"
        return cid, (f"INSERT INTO cp.access_credentials(id,tenant_id,connector_id,membership_id,unit_id,kind,label,created_by) "
                     f"VALUES('{cid}','{TA}','{connector}','{membership}','{unit}','{kind}','Cartao final 1234','{actor}')")
    c1, sql = create()
    expect(error(sql, "42501", tenant=TA, actor=RS, role="cp_api"), "morador nao cadastra a propria credencial")
    expect(error(concierge + sql.replace(MG, CN), "42501", tenant=TA, actor=CN, role="cp_api"), "portaria nao cadastra credencial")
    query(manager + sql, tenant=TA, actor=MG, role="cp_api")
    expect(value(f"select desired_state||':'||desired_version||':'||(external_ref ~ '^cpc_[0-9a-f]{{32}}$')::text from cp.access_credentials where id='{c1}'")
           == "ACTIVE:1:true", "gestao cadastra credencial ativa na versao 1 com referencia opaca")
    expect(value(f"select (valid_until is null)::text from cp.access_credentials where id='{c1}'") == "true", "vinculo sem prazo gera credencial sem prazo")
    query(f"UPDATE cp.memberships SET valid_until=clock_timestamp()+interval '30 days' WHERE id='{MO}'")
    co, sql = create(membership=MO, unit=U2)
    query(manager + sql, tenant=TA, actor=MG, role="cp_api")
    expect(value(f"select (c.valid_until=m.valid_until)::text from cp.access_credentials c join cp.memberships m on m.id=c.membership_id where c.id='{co}'") == "true",
           "credencial herda a validade do vinculo, definida pelo banco")
    expect(error(manager + f"UPDATE cp.access_credentials SET valid_until=NULL WHERE id='{co}'", "42501", tenant=TA, actor=MG, role="cp_api"),
           "validade da credencial nao muda pela aplicacao")
    _, sql = create(membership=MW, unit=U2)
    expect(error(manager + sql, "42501", tenant=TA, actor=MG, role="cp_api"), "proprietario nao residente nao recebe credencial")
    _, sql = create(membership=MR, unit=U2)
    expect(error(manager + sql, "42501", tenant=TA, actor=MG, role="cp_api"), "credencial na unidade de outro vinculo e recusada")
    _, sql = create(connector=KD)
    expect(error(manager + sql, "42501", tenant=TA, actor=MG, role="cp_api"), "conector desligado nao recebe credencial nova")
    _, sql = create(kind="FACE")
    expect(error(manager + sql, "23514", tenant=TA, actor=MG, role="cp_api"), "credencial facial fica fora deste recorte")
    expect(error(manager + f"INSERT INTO cp.access_connectors(tenant_id,provider,label) VALUES('{TA}','PARTNER','x')", "42501", tenant=TA, actor=MG, role="cp_api"),
           "aplicacao nao cadastra conector")
    expect(value(f"select count(*) from cp.access_credentials where id='{c1}'", tenant=TA, actor=RS, role="cp_api") == "1", "morador ve a propria credencial")
    expect(value(f"select count(*) from cp.access_credentials where id='{c1}'", tenant=TA, actor=OT, role="cp_api") == "0", "outra unidade nao ve a credencial")
    expect(value(concierge + f"select count(*) from cp.access_credentials where id='{c1}'", tenant=TA, actor=CN, role="cp_api") == "1",
           "portaria ve a credencial para orientar a contingencia")
    expect(value(manager + f"select count(*) from cp.access_credentials where id='{c1}'", tenant=TB, actor=MG, role="cp_api") == "0",
           "outro condominio nao ve a credencial")
    worker = lambda sql, **kw: query(sql, tenant=TA, actor=None, role="cp_worker", **kw)
    def sync(device, version, action, outcome):
        return (f"INSERT INTO cp.credential_syncs(tenant_id,credential_id,device_id,attempted_version,attempted_action,outcome) "
                f"VALUES('{TA}','{c1}','{device}',{version},'{action}','{outcome}') ON CONFLICT(tenant_id,credential_id,device_id) DO UPDATE SET "
                f"attempted_version=EXCLUDED.attempted_version,attempted_action=EXCLUDED.attempted_action,outcome=EXCLUDED.outcome,attempts=credential_syncs.attempts+1")
    worker(sync(D1, 1, "APPLY", "APPLIED"))
    worker(sync(D2, 1, "APPLY", "UNKNOWN"))
    expect(value(f"select observed_version||':'||observed_action||':'||(confirmed_at is not null)::text from cp.credential_syncs where device_id='{D1}' and credential_id='{c1}'")
           == "1:APPLY:true", "equipamento confirma a versao aplicada")
    expect(value(f"select coalesce(observed_version::text,'-') from cp.credential_syncs where device_id='{D2}' and credential_id='{c1}'") == "-",
           "resultado incerto nao confirma nada")
    expect(error(f"INSERT INTO cp.credential_syncs(tenant_id,credential_id,device_id,attempted_version,attempted_action,outcome) "
                 f"VALUES('{TA}','{c1}','{DX}',1,'APPLY','APPLIED')", "23514", tenant=TA, actor=None, role="cp_worker"),
           "equipamento de outro conector nao recebe resultado")
    expect(error(manager + sync(D2, 1, "APPLY", "APPLIED"), "42501", tenant=TA, actor=MG, role="cp_api"), "aplicacao nao grava resultado de equipamento")
    expect(error(f"UPDATE cp.access_credentials SET desired_state='REVOKED' WHERE id='{c1}'", "42501", tenant=TA, actor=None, role="cp_worker"),
           "worker nao revoga credencial")
    revoke = (f"UPDATE cp.access_credentials SET desired_state='REVOKED',desired_version=desired_version+1,revoke_reason='Vinculo encerrado',"
              f"revoked_by='{MG}',version=version+1 WHERE id='{c1}'")
    query(revoke.replace(MG, RS), tenant=TA, actor=RS, role="cp_api")
    expect(value(f"select desired_state from cp.access_credentials where id='{c1}'") == "ACTIVE", "morador nao revoga credencial")
    query(manager + revoke, tenant=TA, actor=MG, role="cp_api")
    expect(value(f"select desired_state||':'||desired_version||':'||(revoked_at is not null)::text from cp.access_credentials where id='{c1}'")
           == "REVOKED:2:true", "revogacao sobe a versao desejada")
    # Confirmacao atrasada do comando antigo (APPLY v1) chega depois da revogacao: descartada, nada reativa.
    expect(error(sync(D2, 1, "APPLY", "APPLIED"), "23514", tenant=TA, actor=None, role="cp_worker"),
           "confirmacao de versao antiga nao reativa credencial revogada")
    expect(error(sync(D1, 2, "APPLY", "APPLIED"), "23514", tenant=TA, actor=None, role="cp_worker"), "versao nova com acao errada nao confirma")
    expect(value(f"select count(*) from cp.credential_syncs where credential_id='{c1}' and observed_action='APPLY' and observed_version=2") == "0",
           "nenhum destino confirma ativacao da versao revogada")
    worker(sync(D1, 2, "REVOKE", "APPLIED"))
    worker(sync(D2, 2, "REVOKE", "UNAVAILABLE"))
    expect(value(f"select string_agg(device_id::text||'='||coalesce(observed_version::text,'-')||coalesce(observed_action,''),',' order by device_id) "
                 f"from cp.credential_syncs where credential_id='{c1}'") == f"{D1}=2REVOKE,{D2}=-",
           "revogacao confirmada no destino; destino fora do ar fica pendente sem perder a confirmacao anterior")
    expect(error(manager + f"UPDATE cp.access_credentials SET desired_state='ACTIVE',desired_version=desired_version+1,version=version+1,revoked_by='{MG}' WHERE id='{c1}'",
                 "23514", tenant=TA, actor=MG, role="cp_api"), "credencial revogada nao volta a valer")
    expect(error(manager + f"UPDATE cp.access_credentials SET label='Outro' WHERE id='{c1}'", "42501", tenant=TA, actor=MG, role="cp_api"),
           "rotulo e referencia nao mudam depois do cadastro")
    expect(value(f"select count(*) from cp.credential_syncs where credential_id='{c1}'", tenant=TA, actor=RS, role="cp_api") == "2",
           "morador ve o resultado por equipamento da propria credencial")
    expect(value(f"select count(*) from cp.credential_syncs where credential_id='{c1}'", tenant=TA, actor=OT, role="cp_api") == "0",
           "outra unidade nao ve o resultado")
    expect(value(f"select count(*) from cp.access_devices where connector_id='{K1}'", tenant=TA, actor=RS, role="cp_api") == "2",
           "morador ve os equipamentos de destino da propria credencial")
    expect(value(f"select count(*) from cp.access_devices where connector_id='{K2}'", tenant=TA, actor=RS, role="cp_api") == "0",
           "morador nao ve equipamentos de outra integracao")
    expect(value(f"select count(*) from cp.access_devices", tenant=TA, actor=OW, role="cp_api") == "0",
           "morador sem credencial nao ve equipamentos")
    expect(error(f"INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES('{TA}','CREDENTIAL_REVOKED','{c1}',9,'{{}}')",
                 "42501", tenant=TA, actor=RS, role="cp_api"), "morador nao grava evento de credencial")
    expect(error(f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) VALUES('{TA}','{RS}','CREDENTIAL_REVOKED','ACCESS_CREDENTIAL','{c1}',gen_random_uuid())",
                 "42501", tenant=TA, actor=RS, role="cp_api"), "morador nao grava trilha de credencial")
    for table in ("access_credentials", "credential_syncs", "access_devices", "access_connectors"):
        expect(value(f"select has_table_privilege('cp_api','cp.{table}','DELETE')::text") == "false", f"aplicacao nao apaga cp.{table}")

def device_event_checks():
    """V027: eventos do parceiro por conector, sem duplicar, com unidade so pela credencial e sem dado inventado."""
    MG, RS, OT, CN = ("10000000-0000-4000-8000-0000000002e1", "10000000-0000-4000-8000-0000000002e2", "10000000-0000-4000-8000-0000000002e3",
                      "10000000-0000-4000-8000-0000000002e4")
    U1, U2 = "20000000-0000-4000-8000-0000000002e1", "20000000-0000-4000-8000-0000000002e2"
    MR, MO = "51000000-0000-4000-8000-0000000002e1", "51000000-0000-4000-8000-0000000002e2"
    KA, KB, KX = "43000000-0000-4000-8000-0000000002e1", "43000000-0000-4000-8000-0000000002e2", "43000000-0000-4000-8000-0000000002e3"
    DA, DB, DX = "44000000-0000-4000-8000-0000000002e1", "44000000-0000-4000-8000-0000000002e2", "44000000-0000-4000-8000-0000000002e3"
    CA = "45000000-0000-4000-8000-0000000002e1"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MG}','ACTIVE'),('{RS}','ACTIVE'),('{OT}','ACTIVE'),('{CN}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{U1}','{TA}','EVENTO 1'),('{U2}','{TA}','EVENTO 2');
        INSERT INTO cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status)
          VALUES(gen_random_uuid(),'{TA}','{MG}',NULL,'GESTAO EVENTO','MANAGER','ACTIVE'),('{MR}','{TA}','{RS}','{U1}','MORADOR EVENTO','RESIDENT','ACTIVE'),
                ('{MO}','{TA}','{OT}','{U2}','OUTRO EVENTO','RESIDENT','ACTIVE'),(gen_random_uuid(),'{TA}','{CN}',NULL,'PORTARIA EVENTO','CONCIERGE','ACTIVE');
        INSERT INTO cp.access_connectors(id,tenant_id,provider,label) VALUES('{KA}','{TA}','SIMULATED','A'),('{KX}','{TA}','SIMULATED','X'),('{KB}','{TB}','SIMULATED','B');
        INSERT INTO cp.access_devices(id,tenant_id,connector_id,external_id,label) VALUES('{DA}','{TA}','{KA}','leitor-1','Entrada'),
          ('{DX}','{TA}','{KX}','leitor-1','Outro'),('{DB}','{TB}','{KB}','leitor-1','Condominio B');
    """)
    query("SET LOCAL app.capabilities='memberships.manage'; "
          f"INSERT INTO cp.access_credentials(id,tenant_id,connector_id,membership_id,unit_id,kind,label,created_by) VALUES('{CA}','{TA}','{KA}','{MR}','{U1}','CARD','Cartao','{MG}')",
          tenant=TA, actor=MG, role="cp_api")
    def event(eid, tenant=TA, connector=KA, device=DA, credential=CA, kind="PASSAGE_DETECTED", direction="ENTRY", state="ACCEPTED", reason="NULL", unit="DEFAULT"):
        dev = f"'{device}'" if device else "NULL"
        cred = f"'{credential}'" if credential else "NULL"
        dirv = f"'{direction}'" if direction else "NULL"
        rsn = f"'{reason}'" if reason != "NULL" else "NULL"
        return (f"INSERT INTO cp.device_events(tenant_id,connector_id,external_event_id,device_id,credential_id,kind,direction,occurred_at,received_at,state,quarantine_reason) "
                f"VALUES('{tenant}','{connector}','{eid}',{dev},{cred},'{kind}',{dirv},clock_timestamp(),clock_timestamp(),'{state}',{rsn}) "
                f"ON CONFLICT(tenant_id,connector_id,external_event_id) DO NOTHING")
    worker = lambda sql, tenant=TA: query(sql, tenant=tenant, actor=None, role="cp_worker")
    worker(event("ev-1"))
    worker(event("ev-1"))
    expect(value("select count(*) from cp.device_events where external_event_id='ev-1'") == "1", "repeticao do mesmo evento nao duplica")
    expect(value(f"select unit_id from cp.device_events where external_event_id='ev-1'") == U1, "unidade vem da credencial do mesmo conector")
    worker(event("ev-2", direction=None, kind="AUTHORIZED"))
    expect(value("select coalesce(direction,'-')||':'||coalesce(evidence,'-') from cp.device_events where external_event_id='ev-2'") == "-:-",
           "direcao e evidencia ausentes continuam ausentes")
    expect(error(event("ev-3", device=DX), "23514", tenant=TA, actor=None, role="cp_worker"), "equipamento de outro conector e recusado")
    expect(error(event("ev-4", connector=KX, device=DX), "23514", tenant=TA, actor=None, role="cp_worker"), "credencial de outro conector e recusada")
    expect(error(event("ev-5", tenant=TB, connector=KB, device=DB), "23514", tenant=TB, actor=None, role="cp_worker"),
           "evento do conector B nao aponta credencial do condominio A")
    expect(value("select count(*) from cp.device_events where external_event_id='ev-5'") == "0", "nada gravado no condominio B")
    expect(query(event("ev-6", tenant=TB, connector=KB, device=DB, credential=None), tenant=TA, actor=None, role="cp_worker", check=False).returncode != 0
           and value("select count(*) from cp.device_events where external_event_id='ev-6'") == "0", "worker no contexto do condominio A nao grava no B")
    worker(event("ev-7", device=None, credential=None, state="QUARANTINED", reason="UNKNOWN_DEVICE"))
    expect(value("select state||':'||coalesce(unit_id::text,'-') from cp.device_events where external_event_id='ev-7'") == "QUARANTINED:-",
           "equipamento desconhecido fica em quarentena sem unidade")
    expect(error(event("ev-8", device=None, credential=None), "23514", tenant=TA, actor=None, role="cp_worker"), "evento aceito exige equipamento conhecido")
    expect(value("select count(*) from cp.device_events where external_event_id like 'ev-%'", tenant=TA, actor=RS, role="cp_api") == "2",
           "morador ve os eventos da propria credencial")
    expect(value("select count(*) from cp.device_events where external_event_id like 'ev-%'", tenant=TA, actor=OT, role="cp_api") == "0",
           "outra unidade nao ve os eventos")
    expect(value("SET LOCAL app.capabilities='concierge.operate'; select count(*) from cp.device_events where external_event_id like 'ev-%'",
                 tenant=TA, actor=CN, role="cp_api") == "3", "portaria ve os eventos do condominio, inclusive a quarentena")
    expect(error(f"INSERT INTO cp.device_events(tenant_id,connector_id,external_event_id,device_id,kind,occurred_at,received_at,state) "
                 f"VALUES('{TA}','{KA}','ev-api','{DA}','DOOR_OPENED',clock_timestamp(),clock_timestamp(),'ACCEPTED')", "42501", tenant=TA, actor=MG, role="cp_api"),
           "aplicacao nao grava evento de equipamento")
    expect(error(f"UPDATE cp.device_events SET direction='EXIT' WHERE external_event_id='ev-1'", "42501", tenant=TA, actor=None, role="cp_worker"),
           "evento recebido nao muda")
    expect(error(f"INSERT INTO cp.device_event_cursors(tenant_id,connector_id,continuation) VALUES('{TA}','{KA}','pagina-2')", "23514", tenant=TA, actor=None, role="cp_worker"),
           "continuacao exige a janela em que vale")
    worker(f"INSERT INTO cp.device_event_cursors(tenant_id,connector_id,last_received_at,continuation,window_from,window_until) "
           f"VALUES('{TA}','{KA}',clock_timestamp(),'pagina-2',clock_timestamp()-interval '1 hour',clock_timestamp())")
    expect(value(f"select continuation from cp.device_event_cursors where connector_id='{KA}'") == "pagina-2", "worker guarda a continuacao com a janela")
    for table in ("device_events", "device_event_cursors"):
        expect(value(f"select has_table_privilege('cp_api','cp.{table}','DELETE')::text||has_table_privilege('cp_worker','cp.{table}','DELETE')::text") == "falsefalse",
               f"ninguem apaga cp.{table}")

def device_health_checks():
    """V028: saude por conector e equipamento gravada so pelo worker, com horarios do banco e leitura so de gestao e portaria."""
    MG, RS, CN = ("10000000-0000-4000-8000-0000000002f1", "10000000-0000-4000-8000-0000000002f2", "10000000-0000-4000-8000-0000000002f3")
    U1, MR = "20000000-0000-4000-8000-0000000002f1", "51000000-0000-4000-8000-0000000002f1"
    KA, KB = "43000000-0000-4000-8000-0000000002f1", "43000000-0000-4000-8000-0000000002f2"
    DA, DB = "44000000-0000-4000-8000-0000000002f1", "44000000-0000-4000-8000-0000000002f2"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MG}','ACTIVE'),('{RS}','ACTIVE'),('{CN}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{U1}','{TA}','SAUDE 1');
        INSERT INTO cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status)
          VALUES(gen_random_uuid(),'{TA}','{MG}',NULL,'GESTAO SAUDE','MANAGER','ACTIVE'),('{MR}','{TA}','{RS}','{U1}','MORADOR SAUDE','RESIDENT','ACTIVE'),
                (gen_random_uuid(),'{TA}','{CN}',NULL,'PORTARIA SAUDE','CONCIERGE','ACTIVE');
        INSERT INTO cp.access_connectors(id,tenant_id,provider,label) VALUES('{KA}','{TA}','SIMULATED','A'),('{KB}','{TB}','SIMULATED','B');
        INSERT INTO cp.access_devices(id,tenant_id,connector_id,external_id,label) VALUES('{DA}','{TA}','{KA}','leitor-1','Entrada'),
          ('{DB}','{TB}','{KB}','leitor-1','Condominio B');
    """)
    worker = lambda sql, tenant=TA: query(sql, tenant=tenant, actor=None, role="cp_worker")
    connector = lambda reachable, pending="NULL", delay="NULL", reason="NULL": (
        f"INSERT INTO cp.access_connector_health(tenant_id,connector_id,reachable,pending_commands,observed_delay_ms,failure_reason) "
        f"VALUES('{TA}','{KA}',{reachable},{pending},{delay},{reason}) ON CONFLICT(tenant_id,connector_id) DO UPDATE SET reachable=excluded.reachable,"
        f"pending_commands=excluded.pending_commands,observed_delay_ms=excluded.observed_delay_ms,failure_reason=excluded.failure_reason")
    device = lambda online, skew="NULL", dev=DA, tenant=TA: (
        f"INSERT INTO cp.access_device_health(tenant_id,device_id,online,last_heartbeat,clock_skew_ms) VALUES('{tenant}','{dev}',{online},clock_timestamp(),{skew}) "
        f"ON CONFLICT(tenant_id,device_id) DO UPDATE SET online=excluded.online,last_heartbeat=excluded.last_heartbeat,clock_skew_ms=excluded.clock_skew_ms")
    worker(connector("true", "3", "1500"))
    expect(value(f"select (last_reachable_at=checked_at)::text from cp.access_connector_health where connector_id='{KA}'") == "true",
           "conector respondeu: ultima resposta e a propria verificacao")
    first = value(f"select last_reachable_at from cp.access_connector_health where connector_id='{KA}'")
    worker(connector("false", reason="'ADAPTER_ERROR'"))
    expect(value(f"select reachable::text||':'||(last_reachable_at::text='{first}')::text||':'||(checked_at>last_reachable_at)::text "
                 f"from cp.access_connector_health where connector_id='{KA}'") == "false:true:true", "falha mantem a ultima resposta e avanca a verificacao")
    expect(error(connector("false"), "23514", tenant=TA, actor=None, role="cp_worker"), "conector sem resposta exige motivo")
    expect(error(connector("false", "2", reason="'ADAPTER_ERROR'"), "23514", tenant=TA, actor=None, role="cp_worker"), "sem resposta nao ha fila informada")
    expect(error(f"UPDATE cp.access_connector_health SET checked_at=now()-interval '1 day'", "42501", tenant=TA, actor=None, role="cp_worker"),
           "worker nao escolhe o horario da verificacao")
    worker(device("true", "1200"))
    since = value(f"select state_since from cp.access_device_health where device_id='{DA}'")
    worker(device("true", "800"))
    expect(value(f"select (state_since::text='{since}')::text||':'||clock_skew_ms from cp.access_device_health where device_id='{DA}'") == "true:800",
           "mesmo estado mantem o desde quando")
    worker(device("false"))
    expect(value(f"select online::text||':'||(state_since=observed_at)::text||':'||(state_since::text<>'{since}')::text from cp.access_device_health where device_id='{DA}'")
           == "false:true:true", "fora do ar comeca a contar quando o estado muda")
    expect(error(device("true", "90000000"), "23514", tenant=TA, actor=None, role="cp_worker"), "relogio fora da faixa e recusado")
    expect(query(device("true", dev=DB, tenant=TB), tenant=TA, actor=None, role="cp_worker", check=False).returncode != 0
           and value(f"select count(*) from cp.access_device_health where device_id='{DB}'") == "0", "worker no condominio A nao grava saude do B")
    expect(error(f"INSERT INTO cp.access_device_health(tenant_id,device_id,online) VALUES('{TA}','{DB}',true)", "23503", tenant=TA, actor=None, role="cp_worker"),
           "equipamento do condominio B nao entra no A")
    expect(error(f"UPDATE cp.access_device_health SET observed_at=now()-interval '1 day'", "42501", tenant=TA, actor=None, role="cp_worker"),
           "worker nao escolhe o horario da observacao")
    expect(value("SET LOCAL app.capabilities='memberships.manage'; select count(*) from cp.access_device_health", tenant=TA, actor=MG, role="cp_api") == "1",
           "gestao ve a saude")
    expect(value("SET LOCAL app.capabilities='concierge.operate'; select count(*) from cp.access_connector_health", tenant=TA, actor=CN, role="cp_api") == "1",
           "portaria ve a saude")
    expect(value("select (select count(*) from cp.access_device_health)+(select count(*) from cp.access_connector_health)", tenant=TA, actor=RS, role="cp_api") == "0",
           "morador nao ve a saude dos equipamentos")
    expect(error(f"INSERT INTO cp.access_device_health(tenant_id,device_id,online) VALUES('{TA}','{DA}',true)", "42501", tenant=TA, actor=MG, role="cp_api"),
           "aplicacao nao grava saude")
    for table in ("access_connector_health", "access_device_health"):
        expect(value(f"select has_table_privilege('cp_api','cp.{table}','DELETE')::text||has_table_privilege('cp_worker','cp.{table}','DELETE')::text") == "falsefalse",
               f"ninguem apaga cp.{table}")
        expect(value(f"select relforcerowsecurity::text from pg_class where oid='cp.{table}'::regclass") == "true", f"RLS forcado em cp.{table}")

def waitlist_management_checks():
    """V029: gestao que decide reservas le a fila do condominio; nao entra, nao sai e nao muda entradas."""
    MG, OT = "10000000-0000-4000-8000-0000000003a1", "10000000-0000-4000-8000-0000000003a2"
    UO = "20000000-0000-4000-8000-0000000003a1"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MG}','ACTIVE'),('{OT}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UO}','{TA}','FILA OUTRA');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{MG}',NULL,'GESTAO FILA','MANAGER','ACTIVE'),('{TA}','{OT}','{UO}','OUTRA FILA','RESIDENT','ACTIVE');
    """)
    total = value(f"select count(*) from cp.reservation_waitlist where tenant_id='{TA}'")
    expect(int(total) > 0, "ha entradas da fila para conferir")
    expect(value("SET LOCAL app.capabilities='reservations.decide'; select count(*) from cp.reservation_waitlist", tenant=TA, actor=MG, role="cp_api") == total,
           "gestao que decide reservas ve a fila inteira do condominio")
    expect(value("SET LOCAL app.capabilities='memberships.manage'; select count(*) from cp.reservation_waitlist", tenant=TA, actor=MG, role="cp_api") == "0",
           "outra capacidade da gestao nao ve a fila")
    expect(value("select count(*) from cp.reservation_waitlist", tenant=TA, actor=OT, role="cp_api") == "0",
           "morador de outra unidade nao ve a fila das demais")
    expect(value("SET LOCAL app.capabilities='reservations.decide'; select count(*) from cp.reservation_waitlist", tenant=TB, actor=MG, role="cp_api",
                 check=False) in ("0", ""), "gestao do condominio A nao ve a fila do B")
    expect(value(f"SET LOCAL app.capabilities='reservations.decide'; WITH changed AS (UPDATE cp.reservation_waitlist SET state='LEFT',version=version+1 "
                 f"WHERE tenant_id='{TA}' AND state='WAITING' RETURNING 1) SELECT count(*) FROM changed", tenant=TA, actor=MG, role="cp_api") == "0",
           "gestao nao altera entradas da fila")
    expect(value(f"select count(*) from cp.reservation_waitlist where tenant_id='{TA}'") == total, "nada mudou")

def waitlist_offer_notice_checks():
    """V030: oferta da fila vira aviso so para quem entrou na fila, com o prazo ainda valendo; o worker nao avisa outros."""
    MO, DO = "10000000-0000-4000-8000-0000000004a1", "10000000-0000-4000-8000-0000000004a2"
    H1, W1, E1 = ("81000000-0000-4000-8000-000000000001", "82000000-0000-4000-8000-000000000001", "83000000-0000-4000-8000-000000000001")
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MO}','ACTIVE'),('{DO}','ACTIVE');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{MO}','{UA}','OUTRO MORADOR OFERTA','RESIDENT','ACTIVE'),('{TA}','{DO}','{UA}','DEPENDENTE OFERTA','DEPENDENT','ACTIVE');
        SET session_replication_role=replica;
        INSERT INTO cp.reservations(id,tenant_id,amenity_id,unit_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot,expires_at)
          VALUES('{H1}','{TA}','{RA}','{UA}','{AA}','BOOKING','HELD',now()+interval '30 days',now()+interval '30 days 2 hours',
                 now()+interval '30 days',now()+interval '30 days 2 hours','{{}}',clock_timestamp()+interval '15 minutes');
        INSERT INTO cp.reservation_waitlist(id,tenant_id,amenity_id,unit_id,created_by,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot,
            state,offer_reservation_id,offered_at,offer_expires_at)
          VALUES('{W1}','{TA}','{RA}','{UA}','{AA}',now()+interval '30 days',now()+interval '30 days 2 hours',now()+interval '30 days',
                 now()+interval '30 days 2 hours','{{}}','OFFERED','{H1}',clock_timestamp(),clock_timestamp()+interval '15 minutes');
        INSERT INTO cp.outbox(id,tenant_id,event_type,aggregate_id,aggregate_version,payload)
          VALUES('{E1}','{TA}','WAITLIST_OFFERED','{W1}',1,jsonb_build_object('waitlistId','{W1}','unitId','{UA}','amenityId','{RA}',
                 'reservationId','{H1}','expiresAt',clock_timestamp()+interval '15 minutes'));
    """)
    def notify(account, kind="WAITLIST_OFFERED", resource=W1):
        return (f"INSERT INTO cp.notifications(tenant_id,account_id,unit_id,outbox_id,kind,resource_id) "
                f"VALUES('{TA}','{account}','{UA}','{E1}','{kind}','{resource}')")
    query(f"UPDATE cp.outbox SET state='LEASED',attempts=1,lease_until=clock_timestamp()+interval '60 seconds' WHERE id='{E1}'", tenant=TA, role="cp_worker")
    expect(error(notify(MO), "42501", tenant=TA, role="cp_worker"), "outro morador da unidade nao recebe a oferta de quem entrou na fila")
    expect(error(notify(AA, kind="PARCEL_RECEIVED"), "42501", tenant=TA, role="cp_worker"), "tipo do aviso corresponde ao evento da oferta")
    expect(error(notify(AA, resource=H1), "42501", tenant=TA, role="cp_worker"), "aviso aponta a entrada da fila, nao a retencao")
    query(notify(AA), tenant=TA, role="cp_worker")
    expect(value(f"select count(*) from cp.notifications where outbox_id='{E1}'") == "1", "quem entrou na fila recebe o aviso da oferta")
    expect(value(f"select count(*) from cp.notifications where outbox_id='{E1}'", tenant=TA, role="cp_api") == "1", "autor ve o aviso da oferta")
    expect(value(f"select count(*) from cp.notifications where outbox_id='{E1}'", tenant=TA, actor=MO, role="cp_api") == "0",
           "outro morador nao ve o aviso da oferta")
    # Dependente autor so com a permissao de reservar (a mesma que permite entrar na fila).
    query(f"SET session_replication_role=replica; UPDATE cp.reservations SET created_by='{DO}' WHERE id='{H1}'; "
          f"UPDATE cp.reservation_waitlist SET created_by='{DO}' WHERE id='{W1}'")
    expect(error(notify(DO), "42501", tenant=TA, role="cp_worker"), "dependente sem permissao de reservar nao recebe a oferta")
    query(f"UPDATE cp.memberships SET permissions='{{\"reservations\":{{\"create\":true}}}}' WHERE account_id='{DO}'")
    query(notify(DO), tenant=TA, role="cp_worker")
    expect(value(f"select count(*) from cp.notifications where outbox_id='{E1}' and account_id='{DO}'", tenant=TA, actor=DO, role="cp_api") == "1",
           "dependente com permissao de reservar ve a propria oferta")
    query(f"UPDATE cp.memberships SET permissions='{{}}' WHERE account_id='{DO}'")
    expect(value(f"select count(*) from cp.notifications where account_id='{DO}'", tenant=TA, actor=DO, role="cp_api") == "0",
           "sem a permissao o aviso da oferta deixa de aparecer")
    query(f"DELETE FROM cp.notifications WHERE outbox_id='{E1}'; DELETE FROM cp.outbox WHERE id='{E1}'; "
          f"SET session_replication_role=replica; DELETE FROM cp.reservation_waitlist WHERE id='{W1}'; DELETE FROM cp.reservations WHERE id='{H1}'; "
          f"DELETE FROM cp.memberships WHERE account_id in ('{MO}','{DO}'); DELETE FROM platform.accounts WHERE id in ('{MO}','{DO}')")

def unit_notice_checks():
    """V031: resposta ou mudanca de andamento do pedido pela equipe e decisao da mudanca viram aviso para morador e
    responsavel da unidade; mensagem da propria unidade e pedido de outra unidade nao geram aviso."""
    RX, DX, RO = "10000000-0000-4000-8000-0000000005a1", "10000000-0000-4000-8000-0000000005a2", "10000000-0000-4000-8000-0000000005a3"
    UO = "20000000-0000-4000-8000-0000000005a1"
    Q, MS, MU, MV = ("84000000-0000-4000-8000-000000000001", "85000000-0000-4000-8000-000000000001",
                     "85000000-0000-4000-8000-000000000002", "86000000-0000-4000-8000-000000000001")
    E = ["87000000-0000-4000-8000-00000000000" + str(i) for i in range(1, 6)]
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{RX}','ACTIVE'),('{DX}','ACTIVE'),('{RO}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UO}','{TA}','OUTRA AVISO');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{RX}','{UA}','RESPONSAVEL AVISO','RESPONSIBLE','ACTIVE'),('{TA}','{DX}','{UA}','DEPENDENTE AVISO','DEPENDENT','ACTIVE'),
                ('{TA}','{RO}','{UO}','OUTRA UNIDADE AVISO','RESIDENT','ACTIVE');
        SET session_replication_role=replica;
        INSERT INTO cp.service_requests(id,tenant_id,unit_id,requester_id,requester_name,category,title,description)
          VALUES('{Q}','{TA}','{UA}','{AA}','PESSOA FICTICIA A','MAINTENANCE','Aviso ficticio','Descricao ficticia');
        INSERT INTO cp.request_messages(id,tenant_id,request_id,author_id,author_name,author_role,body,visibility)
          VALUES('{MS}','{TA}','{Q}','{AA}','EQUIPE','STAFF','Resposta da equipe','RESIDENT'),('{MU}','{TA}','{Q}','{AA}','PESSOA FICTICIA A','UNIT','Mensagem da unidade','RESIDENT');
        INSERT INTO cp.move_requests(id,tenant_id,unit_id,requester_id,kind,starts_at,ends_at,provider_name,rules_version,state,decided_by,decided_at)
          VALUES('{MV}','{TA}','{UA}','{AA}','MOVE_IN',now()+interval '5 days',now()+interval '5 days 2 hours','TRANSPORTADORA FICTICIA',1,'APPROVED','{AA}',now());
        INSERT INTO cp.outbox(id,tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES
          ('{E[0]}','{TA}','REQUEST_UPDATED','{Q}',1,jsonb_build_object('id','{Q}','unitId','{UA}','version',1)),
          ('{E[1]}','{TA}','REQUEST_MESSAGE_ADDED','{Q}',1,jsonb_build_object('id','{Q}','unitId','{UA}','version',1,'messageId','{MS}')),
          ('{E[2]}','{TA}','REQUEST_MESSAGE_ADDED','{Q}',2,jsonb_build_object('id','{Q}','unitId','{UA}','version',1,'messageId','{MU}')),
          ('{E[3]}','{TA}','MOVE_APPROVED','{MV}',1,jsonb_build_object('moveId','{MV}','unitId','{UA}','state','APPROVED','version',1)),
          ('{E[4]}','{TA}','REQUEST_UPDATED','{Q}',2,jsonb_build_object('id','{Q}','unitId','{UO}','version',2));
    """)
    for event in E:
        query(f"UPDATE cp.outbox SET state='LEASED',attempts=1,lease_until=clock_timestamp()+interval '60 seconds' WHERE id='{event}'", tenant=TA, role="cp_worker")
    def notify(event, account, kind, resource, unit=UA):
        return (f"INSERT INTO cp.notifications(tenant_id,account_id,unit_id,outbox_id,kind,resource_id) "
                f"VALUES('{TA}','{account}','{unit}','{event}','{kind}','{resource}')")
    query(notify(E[0], AA, "REQUEST_UPDATED", Q), tenant=TA, role="cp_worker")
    query(notify(E[0], RX, "REQUEST_UPDATED", Q), tenant=TA, role="cp_worker")
    expect(value(f"select count(*) from cp.notifications where outbox_id='{E[0]}'") == "2", "andamento do pedido avisa morador e responsavel")
    expect(error(notify(E[0], DX, "REQUEST_UPDATED", Q), "42501", tenant=TA, role="cp_worker"), "dependente nao recebe aviso de pedido")
    expect(error(notify(E[0], RO, "REQUEST_UPDATED", Q, unit=UO), "42501", tenant=TA, role="cp_worker"), "outra unidade nao recebe aviso do pedido")
    expect(error(notify(E[4], RO, "REQUEST_UPDATED", Q, unit=UO), "42501", tenant=TA, role="cp_worker"),
           "evento com unidade diferente da do pedido nao gera aviso")
    query(notify(E[1], AA, "REQUEST_UPDATED", Q), tenant=TA, role="cp_worker")
    expect(error(notify(E[2], AA, "REQUEST_UPDATED", Q), "42501", tenant=TA, role="cp_worker"), "mensagem da propria unidade nao gera aviso")
    expect(error(notify(E[3], AA, "REQUEST_UPDATED", MV), "42501", tenant=TA, role="cp_worker"), "tipo do aviso corresponde ao evento")
    query(notify(E[3], AA, "MOVE_DECIDED", MV), tenant=TA, role="cp_worker")
    expect(error(notify(E[3], DX, "MOVE_DECIDED", MV), "42501", tenant=TA, role="cp_worker"), "dependente nao recebe aviso de mudanca")
    expect(value(f"select count(*) from cp.notifications where outbox_id in ('{E[1]}','{E[3]}')", tenant=TA, role="cp_api") == "2",
           "morador ve resposta do pedido e decisao da mudanca")
    expect(value(f"select count(*) from cp.notifications where outbox_id in ('{E[0]}','{E[1]}','{E[3]}')", tenant=TA, actor=RO, role="cp_api") == "0",
           "outra unidade nao ve os avisos")
    expect(error("select title from cp.service_requests", "42501", tenant=TA, role="cp_worker"), "worker nao le o titulo do pedido")
    expect(error("select body from cp.request_messages", "42501", tenant=TA, role="cp_worker"), "worker nao le o texto da conversa")
    expect(error("select provider_name from cp.move_requests", "42501", tenant=TA, role="cp_worker"), "worker nao le o prestador da mudanca")
    expect(value(f"select count(*) from cp.service_requests where id='{Q}'", tenant=TB, role="cp_worker") == "0", "worker de outro condominio nao ve o pedido")
    query(f"DELETE FROM cp.notifications WHERE outbox_id in ({','.join(repr(e) for e in E)}); DELETE FROM cp.outbox WHERE id in ({','.join(repr(e) for e in E)}); "
          f"SET session_replication_role=replica; DELETE FROM cp.request_messages WHERE request_id='{Q}'; DELETE FROM cp.service_requests WHERE id='{Q}'; "
          f"DELETE FROM cp.move_requests WHERE id='{MV}'; DELETE FROM cp.memberships WHERE account_id in ('{RX}','{DX}','{RO}'); "
          f"DELETE FROM cp.units WHERE id='{UO}'; DELETE FROM platform.accounts WHERE id in ('{RX}','{DX}','{RO}')")

def outbox_replay_checks():
    """V032: operacao da plataforma ve a fila de tarefas de todos os condominios sem o conteudo e devolve para a fila so
    tarefa FAILED ou SKIPPED, zerando tentativa e erro; condominio e worker nao reprocessam."""
    OP = "10000000-0000-4000-8000-0000000006a1"
    F1, S1, D1, P1 = ("88000000-0000-4000-8000-000000000001", "88000000-0000-4000-8000-000000000002",
                      "88000000-0000-4000-8000-000000000003", "88000000-0000-4000-8000-000000000004")
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{OP}','ACTIVE');
        SET session_replication_role=replica;
        INSERT INTO cp.outbox(id,tenant_id,event_type,aggregate_id,aggregate_version,payload,state,attempts,last_error,processed_at) VALUES
          ('{F1}','{TA}','PARCEL_RECEIVED','{PA}',90,jsonb_build_object('unitId','{UA}'),'FAILED',6,'Falha saneada',clock_timestamp()),
          ('{S1}','{TB}','REQUEST_UPDATED','{RQ}',91,jsonb_build_object('unitId','{UB}'),'SKIPPED',1,null,clock_timestamp()),
          ('{D1}','{TA}','PARCEL_RECEIVED','{PA}',92,jsonb_build_object('unitId','{UA}'),'DELIVERED',1,null,clock_timestamp());
        INSERT INTO cp.outbox(id,tenant_id,event_type,aggregate_id,aggregate_version,payload,available_at)
          VALUES('{P1}','{TA}','PARCEL_RECEIVED','{PA}',93,jsonb_build_object('unitId','{UA}'),clock_timestamp()-interval '5 minutes');
    """)
    op = "SET LOCAL app.platform_capabilities='outbox.operate'; "
    expect(value(f"{op}select count(*) from cp.outbox where id in ('{F1}','{S1}','{D1}','{P1}')", actor=OP, role="cp_api") == "4",
           "operacao da plataforma ve as tarefas de todos os condominios")
    expect(error(f"{op}select payload from cp.outbox where id='{F1}'", "42501", actor=OP, role="cp_api"),
           "operacao nao le o conteudo da tarefa")
    expect(value(f"select count(*) from cp.outbox where id in ('{F1}','{P1}')", tenant=TA, role="cp_api") == "0",
           "sessao do condominio nao le a fila de tarefas")
    expect(value(f"SET LOCAL app.platform_capabilities='tenants.manage'; select count(*) from cp.outbox", actor=OP, role="cp_api") == "0",
           "outra capacidade da plataforma nao ve a fila")
    replay = "UPDATE cp.outbox SET state='PENDING' WHERE id='{}'"
    expect(error(op + replay.format(D1), "23514", actor=OP, role="cp_api"), "tarefa entregue nao volta para a fila")
    expect(error(op + replay.format(P1), "23514", actor=OP, role="cp_api"), "tarefa pendente nao e reprocessada de novo")
    expect(error(op + f"UPDATE cp.outbox SET state='DELIVERED' WHERE id='{F1}'", "23514", actor=OP, role="cp_api"),
           "operacao nao conclui tarefa")
    expect(error(op + f"UPDATE cp.outbox SET payload='{{}}' WHERE id='{F1}'", "42501", actor=OP, role="cp_api"), "operacao nao muda o conteudo")
    expect(value(f"WITH u AS ({replay.format(F1)} RETURNING 1) SELECT count(*) FROM u", tenant=TA, role="cp_api") == "0",
           "sessao do condominio nao reprocessa")
    expect(error(replay.format(F1), "23514", tenant=TA, role="cp_worker"), "worker nao reprocessa falha definitiva")
    expect(error(op + replay.format(F1), "23514", tenant=TA, role="cp_worker"), "worker nao reprocessa nem declarando a capacidade da plataforma")
    # Devolução e auditoria na mesma transação, como o serviço faz.
    audit = (f"INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) "
             f"VALUES('{OP}','OUTBOX_TASK_REPLAYED','{TA}','Consumidor corrigido',jsonb_build_object('taskId','{F1}'))")
    expect(error("SET LOCAL app.platform_capabilities='tenants.read'; " + audit, "42501", actor=OP, role="cp_api"),
           "auditoria da devolucao exige outbox.operate")
    query(op + replay.format(F1) + "; " + audit, actor=OP, role="cp_api")
    expect(value(f"select count(*) from platform.platform_audit_log where actor_id='{OP}' and action='OUTBOX_TASK_REPLAYED'") == "1",
           "devolucao grava a trilha da plataforma")
    expect(error(op + f"INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason) VALUES('{OP}','TENANT_SUSPENDED','{TA}','x')",
                 "42501", actor=OP, role="cp_api"), "operacao da fila nao grava acao de outra area")
    expect(value(f"select state||':'||attempts||':'||(last_error is null)||':'||(processed_at is null)||':'||(available_at<=clock_timestamp()) "
                 f"from cp.outbox where id='{F1}'") == "PENDING:6:true:true:true", "falha volta para a fila sem erro, disponivel ja")
    expect(value(f"select attempts-attempt_base from cp.outbox where id='{F1}'") == "0",
           "devolucao renova as tentativas sem reutilizar o numero da tentativa (cerca do arrendamento antigo)")
    query(op + replay.format(S1), actor=OP, role="cp_api")
    expect(value(f"select state from cp.outbox where id='{S1}'") == "PENDING", "tarefa ignorada volta para a fila (consumidor novo)")
    expect(error(f"UPDATE cp.outbox SET state='LEASED',attempts=1,lease_until=clock_timestamp()+interval '60 seconds' WHERE id='{F1}'", "23514",
                 tenant=TA, role="cp_worker"), "tentativa nao volta ao comeco depois da devolucao")
    query(f"UPDATE cp.outbox SET state='LEASED',attempts=7,lease_until=clock_timestamp()+interval '60 seconds' WHERE id='{F1}'", tenant=TA, role="cp_worker")
    expect(value(f"select state||':'||attempts from cp.outbox where id='{F1}'") == "LEASED:7", "worker arrenda a tarefa devolvida com tentativa nova")
    expect(value(f"WITH u AS (UPDATE cp.outbox SET state='DELIVERED' WHERE id='{F1}' AND state='LEASED' AND attempts=6 RETURNING 1) SELECT count(*) FROM u",
                 tenant=TA, role="cp_worker") == "0", "worker atrasado da tentativa antiga nao conclui o arrendamento novo")
    expect(error(f"UPDATE cp.outbox SET attempt_base=0 WHERE id='{F1}'", "42501", tenant=TA, role="cp_worker"), "worker nao muda a base das tentativas")
    query(f"SET session_replication_role=replica; DELETE FROM cp.outbox WHERE id in ('{F1}','{S1}','{D1}','{P1}'); DELETE FROM platform.platform_audit_log WHERE actor_id='{OP}'; DELETE FROM platform.accounts WHERE id='{OP}'")

def occurrence_checks():
    """V033: ocorrencias da portaria. Portaria (concierge.operate) e gestao (requests.manage) registram e encerram com
    resolucao; autoria, nomes e horarios vem do banco; nada e apagado; unidade e moradores nao veem."""
    CO, MG, RO = "10000000-0000-4000-8000-0000000007a1", "10000000-0000-4000-8000-0000000007a2", "10000000-0000-4000-8000-0000000007a3"
    O1, O2 = "89000000-0000-4000-8000-000000000001", "89000000-0000-4000-8000-000000000002"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{CO}','ACTIVE'),('{MG}','ACTIVE'),('{RO}','ACTIVE');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{CO}',NULL,'PORTARIA OCORRENCIA','CONCIERGE','ACTIVE'),('{TA}','{MG}',NULL,'GESTAO OCORRENCIA','MANAGER','ACTIVE'),
                ('{TA}','{RO}','{UA}','MORADOR OCORRENCIA','RESIDENT','ACTIVE');
    """)
    concierge = "SET LOCAL app.capabilities='concierge.operate'; "
    manager = "SET LOCAL app.capabilities='requests.manage'; "
    def create(identifier, extra_cols="", extra_vals=""):
        return (f"INSERT INTO cp.concierge_occurrences(id,tenant_id,category,title,description,unit_id{extra_cols}) "
                f"VALUES('{identifier}','{TA}','EQUIPMENT','Portao da garagem','Nao fecha sozinho','{UA}'{extra_vals})")
    expect(error(create(O1), "42501", tenant=TA, actor=RO, role="cp_api"), "morador nao registra ocorrencia")
    expect(error(create(O1, ",created_by", f",'{MG}'"), "42501", tenant=TA, actor=CO, role="cp_api"), "autoria vem do banco")
    expect(error(create(O1, ",state", ",'RESOLVED'"), "42501", tenant=TA, actor=CO, role="cp_api"), "ocorrencia nasce aberta")
    query(concierge + create(O1), tenant=TA, actor=CO, role="cp_api")
    expect(value(f"select state||':'||created_by||':'||created_by_name||':'||version from cp.concierge_occurrences where id='{O1}'")
           == f"OPEN:{CO}:PORTARIA OCORRENCIA:0", "portaria registra com autoria e nome do proprio vinculo")
    expect(error(concierge + create(O2).replace("'Portao da garagem'", "'ab'"), "23514", tenant=TA, actor=CO, role="cp_api"), "titulo de 3 a 120")
    expect(value(f"select count(*) from cp.concierge_occurrences where id='{O1}'", tenant=TA, actor=RO, role="cp_api") == "0",
           "morador nao ve ocorrencias da portaria")
    expect(value(f"{manager}select count(*) from cp.concierge_occurrences where id='{O1}'", tenant=TA, actor=MG, role="cp_api") == "1",
           "gestao ve as ocorrencias")
    expect(value(f"{concierge}select count(*) from cp.concierge_occurrences where id='{O1}'", tenant=TB, actor=CO, role="cp_api", check=False) in ("0", ""),
           "outro condominio nao ve")
    expect(error(concierge + f"UPDATE cp.concierge_occurrences SET title='Outro' WHERE id='{O1}'", "42501", tenant=TA, actor=CO, role="cp_api"),
           "conteudo da ocorrencia nao muda")
    expect(error(concierge + f"UPDATE cp.concierge_occurrences SET state='RESOLVED',version=1 WHERE id='{O1}'", "23514", tenant=TA, actor=CO, role="cp_api"),
           "encerrar exige resolucao")
    expect(error(concierge + f"UPDATE cp.concierge_occurrences SET state='RESOLVED',resolution='Ok',version=5 WHERE id='{O1}'", "23514",
                 tenant=TA, actor=CO, role="cp_api"), "encerrar pela versao lida")
    query(manager + f"UPDATE cp.concierge_occurrences SET state='RESOLVED',resolution='Motor ajustado',version=1 WHERE id='{O1}'", tenant=TA, actor=MG, role="cp_api")
    expect(value(f"select state||':'||resolved_by||':'||resolved_by_name||':'||(resolved_at is not null) from cp.concierge_occurrences where id='{O1}'")
           == f"RESOLVED:{MG}:GESTAO OCORRENCIA:true", "gestao encerra com resolucao; autoria e horario do banco")
    expect(error(concierge + f"UPDATE cp.concierge_occurrences SET state='OPEN',resolution=NULL,version=2 WHERE id='{O1}'", "23514",
                 tenant=TA, actor=CO, role="cp_api"), "ocorrencia encerrada e final")
    expect(error(concierge + f"DELETE FROM cp.concierge_occurrences WHERE id='{O1}'", "42501", tenant=TA, actor=CO, role="cp_api"), "ocorrencia nao e apagada")
    expect(error("select count(*) from cp.concierge_occurrences", "42501", tenant=TA, role="cp_worker"), "worker nao le ocorrencias")
    query(f"SET session_replication_role=replica; DELETE FROM cp.concierge_occurrences WHERE id in ('{O1}','{O2}'); "
          f"DELETE FROM cp.memberships WHERE account_id in ('{CO}','{MG}','{RO}'); DELETE FROM platform.accounts WHERE id in ('{CO}','{MG}','{RO}')")

def finance_checks():
    """V036: financeiro condominial (ADR-0008). Pagador, taxas e cobrancas so pela gestao (finance.manage); unidade ve
    somente cobrancas emitidas da propria unidade; proprietario de unidade alugada nao ve; webhook sem ator so sincroniza a
    situacao da cobranca identificada; nada e apagado; worker sem acesso."""
    MGF, OWF, REF, OTHER = (f"10000000-0000-4000-8000-0000000009a{i}" for i in range(1, 5))
    UF = "20000000-0000-4000-8000-0000000009a1"
    MOW, MRE, MOT = (f"51000000-0000-4000-8000-0000000009a{i}" for i in range(1, 4))
    PY1, PY2, FEE = "53000000-0000-4000-8000-0000000009a1", "53000000-0000-4000-8000-0000000009a2", "54000000-0000-4000-8000-0000000009a1"
    C1, C2, C3, C4 = (f"55000000-0000-4000-8000-0000000009a{i}" for i in range(1, 5))
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MGF}','ACTIVE'),('{OWF}','ACTIVE'),('{REF}','ACTIVE'),('{OTHER}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UF}','{TA}','FINANCEIRO 301');
        INSERT INTO cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) VALUES
          ('{MOW}','{TA}','{OWF}','{UF}','PROPRIETARIO FINANCEIRO','OWNER','ACTIVE'),
          ('{MRE}','{TA}','{REF}','{UF}','RESPONSAVEL FINANCEIRO','RESPONSIBLE','ACTIVE'),
          ('{MOT}','{TA}','{OTHER}','{UA}','MORADOR OUTRA UNIDADE','RESIDENT','ACTIVE'),
          (gen_random_uuid(),'{TA}','{MGF}',NULL,'GESTAO FINANCEIRO','MANAGER','ACTIVE');
    """)
    fin = "SET LOCAL app.capabilities='finance.manage,memberships.manage'; "
    as_manager = dict(tenant=TA, actor=MGF, role="cp_api")
    as_resp = dict(tenant=TA, actor=REF, role="cp_api")
    as_owner = dict(tenant=TA, actor=OWF, role="cp_api")
    as_other = dict(tenant=TA, actor=OTHER, role="cp_api")
    def payer(identifier, membership=MOW, unit=UF):
        return (f"INSERT INTO cp.finance_payers(id,tenant_id,unit_id,membership_id,legal_name,document,email) "
                f"VALUES('{identifier}','{TA}','{unit}','{membership}','PAGADOR FICTICIO','12345678909','pagador@example.invalid')")
    denied = query(payer(PY1), check=False, **as_manager)
    expect(denied.returncode != 0 and ("42501" in denied.stderr or "23514" in denied.stderr),
           "pagador exige finance.manage (sessao sem AAL2 nao publica capacidade)")
    expect(error(fin + payer(PY1, membership=MOT), "23514", **as_manager), "pagador precisa ser proprietario ou responsavel da unidade")
    expect(error(fin + payer(PY1).replace("'12345678909'", "'123'"), "23514", **as_manager), "documento so com 11 ou 14 digitos")
    query(fin + payer(PY1), **as_manager)
    expect(value(f"select state||':'||created_by||':'||version||':'||(asaas_customer_id is null) from cp.finance_payers where id='{PY1}'")
           == f"ACTIVE:{MGF}:0:true", "autoria, situacao e versao do pagador vem do banco")
    expect(error(fin + payer(PY2, membership=MRE), "23505", **as_manager), "um pagador ativo por unidade")
    expect(error(fin + f"UPDATE cp.finance_payers SET document='98765432100',version=1 WHERE id='{PY1}'", "42501", **as_manager),
           "documento do pagador nao muda (sem grant)")
    query(fin + f"UPDATE cp.finance_payers SET asaas_customer_id='cus_000000000001',version=1 WHERE id='{PY1}'", **as_manager)
    expect(error(fin + f"UPDATE cp.finance_payers SET asaas_customer_id='cus_000000000002',version=2 WHERE id='{PY1}'", "23514", **as_manager),
           "cliente do Asaas gravado uma unica vez")
    expect(value("select count(*) from cp.finance_payers", **as_resp) == "0", "unidade nao le o cadastro do pagador (CPF)")
    def charge(identifier, amount=15000, extra_cols="", extra_vals="", kind="CONDO_FEE"):
        return (f"INSERT INTO cp.finance_charges(id,tenant_id,unit_id,payer_id,kind,description,amount_cents,due_date,billing_type{extra_cols}) "
                f"VALUES('{identifier}','{TA}','{UF}','{PY1}','{kind}','Taxa ficticia',{amount},current_date+10,'BOLETO'{extra_vals})")
    refused = query(charge(C1), check=False, **as_resp)
    expect(refused.returncode != 0 and ("42501" in refused.stderr or "23514" in refused.stderr), "unidade nao cria cobranca")
    expect(error(fin + charge(C1, amount=100), "23514", **as_manager), "valor minimo de R$ 5,00")
    expect(error(fin + charge(C1, extra_cols=",state", extra_vals=",'PAID'"), "42501", **as_manager), "cobranca nasce pendente de emissao")
    query(fin + charge(C1), **as_manager)
    expect(value(f"select state||':'||created_by||':'||version from cp.finance_charges where id='{C1}'") == f"PENDING_ISSUE:{MGF}:0",
           "cobranca nasce PENDING_ISSUE com autoria do banco")
    expect(value(f"select count(*) from cp.finance_charges where id='{C1}'", **as_resp) == "0", "cobranca ainda nao emitida nao aparece a unidade")
    expect(error(fin + f"UPDATE cp.finance_charges SET state='OPEN',version=1 WHERE id='{C1}'", "23514", **as_manager),
           "abrir exige o pagamento do Asaas")
    query(fin + f"UPDATE cp.finance_charges SET state='OPEN',asaas_payment_id='pay_000000000001',asaas_status='PENDING',"
                f"invoice_url='https://sandbox.asaas.com/i/000000000001',bank_slip_url='https://sandbox.asaas.com/b/pdf/000000000001',version=1 WHERE id='{C1}'",
          **as_manager)
    expect(value(f"select count(*) from cp.finance_charges where id='{C1}'", **as_resp) == "1", "responsavel ve a cobranca emitida da unidade")
    expect(value(f"select count(*) from cp.finance_charges where id='{C1}'", **as_owner) == "1", "proprietario (sem locacao) ve a cobranca")
    expect(value(f"select count(*) from cp.finance_charges where id='{C1}'", **as_other) == "0", "morador de outra unidade nao ve")
    expect(value(f"select count(*) from cp.finance_charges where id='{C1}'", tenant=TB, actor=AB, role="cp_api") == "0", "outro condominio nao ve")
    expect(value(f"WITH u AS (UPDATE cp.finance_charges SET state='PAID',paid_at=current_date,version=version+1 WHERE id='{C1}' RETURNING id) "
                 f"SELECT count(*) FROM u", **as_resp) == "0", "unidade nao altera cobranca")
    expect(error(fin + f"UPDATE cp.finance_charges SET amount_cents=1,version=2 WHERE id='{C1}'", "42501", **as_manager), "valor nao muda (sem grant)")
    expect(error(fin + f"UPDATE cp.finance_charges SET state='CANCELLED',version=2 WHERE id='{C1}'", "23514", **as_manager),
           "cancelamento exige autoria e motivo")
    # Webhook: sem ator e sem capacidade, so a cobranca identificada.
    hook = f"SET LOCAL app.finance_webhook_charge='{C1}'; "
    expect(value(hook + f"select tenant_id from cp.finance_charges where asaas_payment_id='pay_000000000001'", actor=None, role="cp_api") == TA,
           "webhook localiza o condominio pela cobranca identificada")
    query(fin + charge(C2), **as_manager)
    expect(value(hook + f"select count(*) from cp.finance_charges where id='{C2}'", actor=None, role="cp_api") == "0", "webhook nao ve outra cobranca")
    expect(value(hook + f"select count(*) from cp.finance_charges where id='{C1}'", actor=AA, role="cp_api") == "0",
           "contexto de webhook nao vale com ator")
    expect(error(hook + f"UPDATE cp.finance_charges SET invoice_url='https://sandbox.asaas.com/i/x',version=2 WHERE id='{C1}'", "42501",
                 tenant=TA, actor=None, role="cp_api"), "webhook nao altera dados de emissao")
    event = (f"INSERT INTO cp.finance_webhook_events(tenant_id,event_id,event_type,charge_id,asaas_status,resulting_state) "
             f"VALUES('{TA}','{{event}}','PAYMENT_RECEIVED','{{charge}}','RECEIVED','PAID') ON CONFLICT (event_id) DO NOTHING RETURNING id")
    expect(error(hook + event.format(event="evt_ficticio_0001", charge=C2), "42501", tenant=TA, actor=None, role="cp_api"),
           "evento so da cobranca identificada")
    first = query(hook + event.format(event="evt_ficticio_0001", charge=C1) + "; " +
                  f"UPDATE cp.finance_charges SET state='PAID',asaas_status='RECEIVED',paid_at=current_date,paid_cents=15000,version=2 WHERE id='{C1}'",
                  tenant=TA, actor=None, role="cp_api")
    expect(first.returncode == 0, "webhook grava o evento e sincroniza a situacao na mesma transacao")
    expect(query(hook + event.format(event="evt_ficticio_0001", charge=C1), tenant=TA, actor=None, role="cp_api").stdout.strip() == "",
           "evento repetido e ignorado (idempotente)")
    expect(value(f"select state||':'||paid_cents||':'||version from cp.finance_charges where id='{C1}'") == "PAID:15000:2", "cobranca paga")
    expect(error(fin + f"UPDATE cp.finance_charges SET state='CANCELLED',cancelled_by='{MGF}',cancel_reason='Erro',version=3 WHERE id='{C1}'",
                 "23514", **as_manager), "cobranca paga nao e cancelada")
    query(hook + f"UPDATE cp.finance_charges SET state='REFUNDED',asaas_status='REFUNDED',version=3 WHERE id='{C1}'", tenant=TA, actor=None, role="cp_api")
    expect(error(hook + f"UPDATE cp.finance_charges SET state='OPEN',version=4 WHERE id='{C1}'", "23514", tenant=TA, actor=None, role="cp_api"),
           "estorno e final")
    # Evento tardio de cobranca final: o evento e gravado (idempotente) e a cobranca nao muda; o servico nao tenta o UPDATE.
    late = query(hook + event.format(event="evt_ficticio_0002", charge=C1).replace("'PAYMENT_RECEIVED'", "'PAYMENT_DELETED'")
                 .replace("'RECEIVED','PAID'", "NULL,'REFUNDED'"), tenant=TA, actor=None, role="cp_api")
    expect(late.returncode == 0 and late.stdout.strip() != "", "evento tardio de cobranca final e registrado")
    expect(error(hook + f"UPDATE cp.finance_charges SET asaas_status='DELETED',version=4 WHERE id='{C1}'", "23514", tenant=TA, actor=None, role="cp_api"),
           "cobranca final nao aceita nem mudanca de status bruto (por isso o webhook so registra o evento)")
    # Aberta direto para estornada (recebimento e estorno ja no Asaas): exige os dados do pagamento.
    C5 = "55000000-0000-4000-8000-0000000009a5"
    query(fin + charge(C5), **as_manager)
    # Adocao (emissao) de pagamento ja estornado no Asaas: PENDING_ISSUE vai direto a REFUNDED com os dados do pagamento.
    C6 = "55000000-0000-4000-8000-0000000009a6"
    query(fin + charge(C6), **as_manager)
    query(fin + f"UPDATE cp.finance_charges SET state='REFUNDED',asaas_payment_id='pay_000000000006',asaas_status='REFUNDED',paid_at=current_date,"
                f"paid_cents=15000,version=1 WHERE id='{C6}'", **as_manager)
    expect(value(f"select state from cp.finance_charges where id='{C6}'") == "REFUNDED", "adocao de pagamento estornado vai direto a REFUNDED")
    query(fin + f"UPDATE cp.finance_charges SET state='OPEN',asaas_payment_id='pay_000000000005',version=1 WHERE id='{C5}'", **as_manager)
    expect(error(hook.replace(C1, C5) + f"UPDATE cp.finance_charges SET state='REFUNDED',version=2 WHERE id='{C5}'", "23514", tenant=TA, actor=None, role="cp_api"),
           "estornada sem data de pagamento e recusada")
    query(hook.replace(C1, C5) + f"UPDATE cp.finance_charges SET state='REFUNDED',asaas_status='REFUNDED',paid_at=current_date,paid_cents=15000,version=2 "
          f"WHERE id='{C5}'", tenant=TA, actor=None, role="cp_api")
    expect(value(f"select state||':'||paid_cents from cp.finance_charges where id='{C5}'") == "REFUNDED:15000", "aberta vai direto a estornada com o pagamento")
    query(fin + f"UPDATE cp.finance_charges SET state='CANCELLED',cancelled_by='{MGF}',cancel_reason='Lancada por engano',version=1 WHERE id='{C2}'",
          **as_manager)
    expect(value(f"select (cancelled_at is not null)::text from cp.finance_charges where id='{C2}'") == "true", "horario do cancelamento vem do banco")
    # Recorrente: uma cobranca por unidade e competencia, igual a taxa ativa.
    query(fin + f"INSERT INTO cp.finance_recurring_fees(id,tenant_id,unit_id,description,amount_cents,due_day,billing_type) "
                f"VALUES('{FEE}','{TA}',NULL,'Taxa condominial ficticia',20000,10,'PIX')", **as_manager)
    rec = ",recurring_fee_id,competence"
    recurring = lambda ident, amount=20000: charge(ident, amount=amount, extra_cols=rec, extra_vals=f",'{FEE}',date_trunc('month',current_date)::date").replace("'BOLETO'", "'PIX'")
    expect(error(fin + recurring(C3, amount=19999), "23514", **as_manager), "cobranca recorrente igual a taxa")
    query(fin + recurring(C3), **as_manager)
    expect(error(fin + recurring(C4), "23505", **as_manager), "uma cobranca por taxa, unidade e competencia")
    query(fin + f"UPDATE cp.finance_charges SET state='OPEN',asaas_payment_id='pay_000000000003',version=1 WHERE id='{C3}'", **as_manager)
    # Conciliacao da gestao: cobranca excluida no Asaas so vira CANCELLED com autoria de quem sincroniza e motivo.
    expect(error(fin + f"UPDATE cp.finance_charges SET state='CANCELLED',asaas_status='DELETED',version=2 WHERE id='{C3}'", "23514", **as_manager),
           "conciliacao sem autoria e motivo e recusada")
    query(fin + f"UPDATE cp.finance_charges SET state='CANCELLED',asaas_status='DELETED',cancelled_by='{MGF}',"
                f"cancel_reason='Excluída no Asaas (conciliação)',version=2 WHERE id='{C3}'", **as_manager)
    expect(value(f"select state||':'||cancelled_by||':'||(cancelled_at is not null) from cp.finance_charges where id='{C3}'") == f"CANCELLED:{MGF}:true",
           "conciliacao cancela com autoria, motivo e horario do banco")
    # Lote de emissao: 20 falhas permanentes nao bloqueiam as 5 seguintes (recuo exponencial por tentativa).
    batch = [f"55000000-0000-4000-8000-0000000{i:05d}" for i in range(1, 26)]
    for identifier in batch:
        query(fin + charge(identifier), **as_manager)
    fail = (f"UPDATE cp.finance_charges SET issue_error='ASAAS_REJECTED',issue_lease_until=null,issue_attempts=issue_attempts+1,"
            f"next_issue_at=clock_timestamp()+least(interval '1 minute'*power(2,least(issue_attempts,9)),interval '6 hours'),version=version+1 "
            f"WHERE id in ({','.join(repr(x) for x in batch[:20])})")
    query(fin + fail, **as_manager)
    queue = ("select string_agg(id::text,',' order by next_issue_at nulls first,created_at,id) from (select id,next_issue_at,created_at from cp.finance_charges "
             "where state='PENDING_ISSUE' and (issue_lease_until is null or issue_lease_until<=clock_timestamp()) "
             "and (next_issue_at is null or next_issue_at<=clock_timestamp()) order by next_issue_at nulls first,created_at,id limit 20) q")
    expect(value(fin + queue, **as_manager) == ",".join(batch[20:]), "lote de emissao alcanca as 5 validas depois de 20 falhas")
    expect(value(f"select min(issue_attempts)||':'||bool_and(next_issue_at>clock_timestamp()) from cp.finance_charges where id in ({','.join(repr(x) for x in batch[:20])})")
           == "1:true", "falha registra tentativa e proxima tentativa futura")
    expect(error(fin + f"UPDATE cp.finance_charges SET issue_attempts=issue_attempts+5,version=version+1 WHERE id='{batch[0]}'", "23514", **as_manager),
           "tentativas so avancam uma a uma")
    expect(error(fin + f"UPDATE cp.finance_charges SET next_issue_at=clock_timestamp()+interval '2 days',version=version+1 WHERE id='{batch[0]}'", "23514", **as_manager),
           "espera maxima de 6 horas")
    # Locacao: proprietario de unidade alugada perde a visao do financeiro da unidade.
    query(f"SET LOCAL app.capabilities='memberships.manage'; INSERT INTO cp.unit_rentals(tenant_id,unit_id,owner_membership_id,owner_account_id,started_by) "
          f"VALUES('{TA}','{UF}','{MOW}','{OWF}','{MGF}')", **as_manager)
    expect(value(f"select count(*) from cp.finance_charges where unit_id='{UF}'", **as_owner) == "0",
           "proprietario de unidade alugada nao ve cobrancas da unidade")
    expect(value(f"select count(*) from cp.finance_charges where id='{C3}'", **as_resp) == "1", "inquilino responsavel continua vendo")
    # Trilha, eventos e ausencia de DELETE.
    audit = (f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id,reason) "
             f"VALUES('{TA}','{{actor}}','FINANCE_CHARGE_CREATED','FINANCE_CHARGE','{C3}',gen_random_uuid(),'Motivo')")
    expect(error(audit.format(actor=REF), "42501", **as_resp), "trilha FINANCE_ so pela gestao")
    query(fin + audit.format(actor=MGF), **as_manager)
    expect(error(f"INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES('{TA}','FINANCE_CHARGE_PAID','{C2}',9,'{{}}')",
                 "42501", **as_other), "evento financeiro so sobre cobranca visivel")
    for table in ["finance_payers", "finance_recurring_fees", "finance_charges", "finance_webhook_events"]:
        expect(error(fin + f"DELETE FROM cp.{table}", "42501", **as_manager), f"{table} nao e apagado")
        expect(error(f"select count(*) from cp.{table}", "42501", tenant=TA, role="cp_worker"), f"worker nao le {table}")
    query(f"SET session_replication_role=replica; DELETE FROM cp.audit_log WHERE actor_id='{MGF}'; DELETE FROM cp.unit_rentals WHERE unit_id='{UF}'; "
          f"DELETE FROM cp.finance_webhook_events WHERE tenant_id='{TA}'; DELETE FROM cp.finance_charges WHERE tenant_id='{TA}'; "
          f"DELETE FROM cp.finance_recurring_fees WHERE tenant_id='{TA}'; DELETE FROM cp.finance_payers WHERE tenant_id='{TA}'; "
          f"DELETE FROM cp.memberships WHERE account_id in ('{MGF}','{OWF}','{REF}','{OTHER}'); DELETE FROM cp.units WHERE id='{UF}'; "
          f"DELETE FROM platform.accounts WHERE id in ('{MGF}','{OWF}','{REF}','{OTHER}')")

def rental_checks():
    """V034: proprietario x inquilino. Proprietario (OWNER) de unidade alugada perde a visao de todas as tabelas da unidade
    (mesmo que tivesse vinculo de ocupante), so ve/revoga ocupantes e so convida/revoga inquilino da propria unidade
    alugada; inquilino nao administra o proprietario; so a gestao (memberships.manage) inicia e encerra a locacao."""
    OWN, OWN2, OWN3, TEN, DEP, MGR = (f"10000000-0000-4000-8000-0000000008a{i}" for i in range(1, 7))
    MOWN, MOWN2, MOWN3, MTEN, MDEP, MBYP = (f"51000000-0000-4000-8000-0000000008a{i}" for i in range(1, 7))
    UO, UX = "20000000-0000-4000-8000-0000000008a1", "20000000-0000-4000-8000-0000000008a2"
    MOO, MOX, MXR = "51000000-0000-4000-8000-0000000008b1", "51000000-0000-4000-8000-0000000008b2", "51000000-0000-4000-8000-0000000008b3"
    CRED, K1 = "45000000-0000-4000-8000-0000000008a1", "43000000-0000-4000-8000-0000000002c1"
    I1, I2, I3 = (f"52000000-0000-4000-8000-0000000008a{i}" for i in range(1, 4))
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{OWN}','ACTIVE'),('{OWN2}','ACTIVE'),('{OWN3}','ACTIVE'),('{TEN}','ACTIVE'),
          ('{DEP}','ACTIVE'),('{MGR}','ACTIVE');
        INSERT INTO cp.units(id,tenant_id,label) VALUES('{UO}','{TA}','ALUGUEL PROPRIA'),('{UX}','{TA}','ALUGUEL OUTRA');
        INSERT INTO cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) VALUES
          ('{MOWN}','{TA}','{OWN}','{UA}','PROPRIETARIO FICTICIO','OWNER','ACTIVE'),
          ('{MOO}','{TA}','{OWN}','{UO}','PROPRIETARIO FICTICIO','OWNER','ACTIVE'),
          ('{MOX}','{TA}','{OWN2}','{UX}','OUTRO PROPRIETARIO','OWNER','ACTIVE'),
          ('{MXR}','{TA}','{OWN2}','{UX}','OUTRO PROPRIETARIO MORA','RESIDENT','ACTIVE'),
          ('{MOWN3}','{TA}','{OWN3}','{UA}','COPROPRIETARIO FICTICIO','OWNER','PENDING'),
          ('{MTEN}','{TA}','{TEN}','{UA}','INQUILINO FICTICIO','RESPONSIBLE','ACTIVE'),
          ('{MDEP}','{TA}','{DEP}','{UA}','DEPENDENTE DO INQUILINO','DEPENDENT','ACTIVE'),
          (gen_random_uuid(),'{TA}','{MGR}',NULL,'GESTAO LOCACAO','MANAGER','ACTIVE');
        INSERT INTO cp.access_credentials(id,tenant_id,connector_id,membership_id,unit_id,kind,label,created_by)
          VALUES('{CRED}','{TA}','{K1}','{MTEN}','{UA}','CARD','Cartao do inquilino','{MGR}');
    """)
    manage = "SET LOCAL app.capabilities='memberships.manage'; "
    as_owner = dict(tenant=TA, actor=OWN, role="cp_api")
    as_tenant = dict(tenant=TA, actor=TEN, role="cp_api")
    as_manager = dict(tenant=TA, actor=MGR, role="cp_api")
    unit_tables = ["access_credentials", "access_events", "arrival_requests", "concierge_occurrences", "device_events", "import_rows",
                   "move_requests", "notifications", "parcel_delegations", "parcels", "permit_links", "reservation_waitlist", "reservations",
                   "service_requests", "visit_permits", "visit_rules"]
    expect(value("select coalesce((select string_agg(c.relname,',' order by c.relname) from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                 "where n.nspname='cp' and c.relkind='r' and exists(select 1 from pg_attribute a where a.attrelid=c.oid and a.attname='unit_id' "
                 "and not a.attisdropped) and c.relname not in ('memberships','membership_invitations','unit_rentals') "
                 "and not exists(select 1 from pg_policy p where p.polrelid=c.oid and p.polname='rental_owner_hidden' and not p.polpermissive "
                 "and p.polcmd='*')),'NENHUMA')") == "NENHUMA", "toda tabela com unit_id (exceto vinculos/convites) tem a barreira de locacao")
    start = (f"INSERT INTO cp.unit_rentals(tenant_id,unit_id,owner_membership_id,owner_account_id,started_by) "
             f"VALUES('{TA}','{{unit}}','{{membership}}','{TEN}','{{actor}}')")
    expect(error(start.format(unit=UA, membership=MOWN, actor=OWN), "42501", **as_owner), "proprietario nao marca a propria unidade como alugada")
    tenant_start = query(start.format(unit=UA, membership=MOWN, actor=TEN), check=False, **as_tenant)
    expect(tenant_start.returncode != 0 and ("42501" in tenant_start.stderr or "23514" in tenant_start.stderr),
           "inquilino nao marca locacao (nem enxerga o vinculo do proprietario)")
    expect(error(manage + start.format(unit=UA, membership=MOWN, actor=OWN), "42501", **as_manager), "gestao inicia em nome da propria conta")
    expect(error(manage + start.format(unit=UA, membership=MTEN, actor=MGR), "23514", **as_manager), "locacao exige vinculo OWNER da unidade")
    expect(error(manage + start.format(unit=UO, membership=MOWN, actor=MGR), "23514", **as_manager), "vinculo OWNER de outra unidade e recusado")
    expect(error(manage + start.format(unit=UX, membership=MOX, actor=MGR), "23514", **as_manager),
           "proprietario que mora na unidade nao a aluga sem encerrar o vinculo de ocupante")
    # Antes da locacao o proprietario (so OWNER) e publico dos avisos e nao ve ocupantes.
    audience = f"select cp.announcement_audience_member('{TA}','UNITS',ARRAY['{UA}']::uuid[])"
    expect(value(audience, **as_owner) == "t", "antes da locacao o proprietario e publico de avisos")
    occupants = f"select count(*) from cp.memberships where unit_id='{UA}' and role in ('RESIDENT','RESPONSIBLE','DEPENDENT')"
    expect(value(occupants, **as_owner) == "0", "sem locacao o proprietario nao ve os ocupantes")
    query(manage + start.format(unit=UA, membership=MOWN, actor=MGR), **as_manager)
    expect(value(f"select owner_account_id||':'||(ended_at is null) from cp.unit_rentals where owner_membership_id='{MOWN}'") == f"{OWN}:true",
           "conta do proprietario vem do vinculo (nao do cliente)")
    expect(error(manage + start.format(unit=UA, membership=MOWN, actor=MGR), "23505", **as_manager), "uma locacao em vigor por proprietario")
    expect(value("select count(*) from cp.unit_rentals", **as_owner) == "1", "proprietario ve a propria locacao")
    expect(value("select count(*) from cp.unit_rentals", **as_tenant) == "0", "inquilino nao ve a locacao do proprietario")
    expect(value("select count(*) from cp.unit_rentals", tenant=TA, actor=AA, role="cp_api") == "0", "morador comum nao ve locacoes")
    expect(value("select count(*) from cp.unit_rentals", tenant=TB, actor=OWN, role="cp_api") == "0", "locacao nao atravessa condominio")
    # Defesa em profundidade: mesmo com vinculo de ocupante gravado fora das regras, nada da unidade aparece ao proprietario.
    expect(error(f"INSERT INTO cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) "
                 f"VALUES('{MBYP}','{TA}','{OWN}','{UA}','PROPRIETARIO MORADOR','RESIDENT','PENDING')", "23514"),
           "proprietario de unidade alugada nao recebe vinculo de ocupante nela (nem pendente)")
    query(f"SET session_replication_role=replica; INSERT INTO cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) "
          f"VALUES('{MBYP}','{TA}','{OWN}','{UA}','PROPRIETARIO MORADOR','RESIDENT','ACTIVE')")
    expect(error(manage + f"UPDATE cp.memberships SET status='SUSPENDED' WHERE id='{MBYP}'", "23514", **as_manager),
           "gestao nao reativa nem altera vinculo de ocupante do proprietario de unidade alugada")
    hidden = {t: value(f"select count(*) from cp.{t} where unit_id='{UA}'", **as_owner) for t in unit_tables}
    expect(all(count == "0" for count in hidden.values()), f"proprietario de unidade alugada nao le nenhuma tabela da unidade: {hidden}")
    expect(int(value(f"select count(*) from cp.reservations where unit_id<>'{UA}'", **as_owner)) > 0,
           "o resto do condominio continua com a regra existente (reservas de outras unidades)")
    expect(value(f"select count(*) from cp.parcel_handoffs h join cp.parcels p on p.tenant_id=h.tenant_id and p.id=h.parcel_id "
                 f"where p.unit_id='{UA}'", **as_owner) == "0", "entrega de encomenda da unidade (via linha-mae) tambem some")
    expect(error(reservation(unit=UA, start="20:00", end="21:00", actor=OWN), "42501", **as_owner), "proprietario nao grava reserva da unidade alugada")
    scan = "SET LOCAL app.occupancy_scan='on'; "
    blocking = f"select count(*) from cp.reservations where unit_id='{UA}' and status in ('HELD','PENDING_APPROVAL','CONFIRMED')"
    expect(int(value(blocking)) > 0 and value(scan + blocking, **as_owner) == value(blocking),
           "consulta anonima de ocupacao continua vendo os horarios ocupados da unidade alugada")
    expect(value(scan + f"select count(*) from cp.reservations where unit_id='{UA}' and status not in ('HELD','PENDING_APPROVAL','CONFIRMED')",
                 **as_owner) == "0", "a leitura de ocupacao nao revela reservas encerradas da unidade alugada")
    expect(error(scan + f"UPDATE cp.reservations SET version=version+1 WHERE unit_id='{UA}' AND status='CONFIRMED'", "42501", **as_owner),
           "a leitura de ocupacao nao libera escrita na unidade alugada")
    expect(value(f"WITH u AS (UPDATE cp.parcels SET version=version+1 WHERE unit_id='{UA}' RETURNING id) SELECT count(*) FROM u", **as_owner) == "0",
           "proprietario nao altera encomenda da unidade alugada")
    expect(value(audience, **as_owner) == "f", "vinculo OWNER de unidade alugada deixa de ser publico de avisos")
    expect(int(value(manage + f"select count(*) from cp.reservations where unit_id='{UA}'", **as_owner)) > 0,
           "contexto de equipe (capacidade AAL2) nao e escondido")
    # Unica capacidade: ocupantes da propria unidade alugada.
    expect(value(occupants, **as_owner) == value(occupants) and int(value(occupants)) >= 3, "proprietario ve os ocupantes da unidade alugada (inclusive o vinculo indevido)")
    expect(value(f"select count(*) from cp.memberships where unit_id<>'{UA}' and account_id<>'{OWN}'", **as_owner) == "0",
           "proprietario nao ve vinculos de outras unidades")
    expect(value(f"select count(*) from cp.memberships where id='{MOWN3}'", **as_owner) == "0", "proprietario nao ve outros proprietarios")
    expect(error(f"UPDATE cp.memberships SET status='ACTIVE',version=version+1 WHERE id='{MTEN}'", "42501", **as_owner),
           "proprietario so revoga: nao reativa nem suspende ocupante")
    expect(value(f"WITH u AS (UPDATE cp.memberships SET status='REVOKED' WHERE id in ('{MXR}','{MOX}','{MOWN3}') RETURNING id) "
                 f"SELECT count(*) FROM u", **as_owner) == "0", "proprietario nao revoga vinculos de outras unidades nem proprietarios")
    expect(value(f"select count(*) from cp.access_credentials where id='{CRED}'", **as_owner) == "0",
           "credencial de ocupante vigente fica invisivel ao proprietario")
    expect(value(f"WITH u AS (UPDATE cp.access_credentials SET desired_state='REVOKED',desired_version=desired_version+1,version=version+1,"
                 f"revoked_by='{OWN}',revoke_reason='x' WHERE id='{CRED}' RETURNING id) SELECT count(*) FROM u", **as_owner) == "0",
           "proprietario nao revoga credencial de ocupante vigente")
    query(f"UPDATE cp.memberships SET status='REVOKED',updated_at=clock_timestamp(),version=version+1 WHERE id='{MTEN}'", **as_owner)
    expect(value(f"select status from cp.memberships where id='{MTEN}'") == "REVOKED", "proprietario remove o inquilino da propria unidade alugada")
    query(f"UPDATE cp.access_credentials SET desired_state='REVOKED',desired_version=desired_version+1,version=version+1,"
          f"revoked_by='{OWN}',revoke_reason='Inquilino removido pelo proprietario' WHERE id='{CRED}'", **as_owner)
    expect(value(f"select desired_state from cp.access_credentials where id='{CRED}'") == "REVOKED",
           "credencial do inquilino removido e revogada na mesma operacao")
    audit = (f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) "
             f"VALUES('{TA}','{{actor}}','{{action}}','{{kind}}','{{resource}}',gen_random_uuid())")
    query(audit.format(actor=OWN, action="CREDENTIAL_REVOKED", kind="ACCESS_CREDENTIAL", resource=CRED), **as_owner)
    expect(error(audit.format(actor=OWN, action="CREDENTIAL_CREATED", kind="ACCESS_CREDENTIAL", resource=CRED), "42501", **as_owner),
           "proprietario so registra a revogacao da credencial")
    expect(error(audit.format(actor=OWN, action="UNIT_RENTAL_ENDED", kind="UNIT", resource=UA), "42501", **as_owner),
           "trilha de locacao e exclusiva da gestao")
    # Convites: inquilino (RESIDENT/RESPONSIBLE) somente para a propria unidade alugada.
    def invite(identifier, unit=UA, role_name="RESPONSIBLE", creator=OWN):
        return (f"INSERT INTO cp.membership_invitations(id,tenant_id,unit_id,role,display_name,email_hash,token_hash,expires_at,created_by) "
                f"VALUES('{identifier}','{TA}','{unit}','{role_name}','NOVO INQUILINO',repeat('a',64),md5('{identifier}')||md5('{identifier}x'),"
                f"now()+interval '7 days','{creator}')")
    query(invite(I1), **as_owner)
    expect(value(f"select count(*) from cp.membership_invitations where id='{I1}'", **as_owner) == "1", "proprietario ve o convite do inquilino")
    for label, statement in [("papel DEPENDENT", invite(I2, role_name="DEPENDENT")), ("papel OWNER", invite(I2, role_name="OWNER")),
                             ("unidade nao alugada", invite(I2, unit=UO)), ("unidade de outro", invite(I2, unit=UX)),
                             ("autoria de outra conta", invite(I2, creator=MGR))]:
        expect(error(statement, "42501", **as_owner), f"proprietario nao convida: {label}")
    query(manage + invite(I3, unit=UX, role_name="RESIDENT", creator=MGR), **as_manager)
    expect(value(f"WITH u AS (UPDATE cp.membership_invitations SET status='REVOKED' WHERE id='{I3}' RETURNING id) SELECT count(*) FROM u", **as_owner) == "0",
           "proprietario nao revoga convite de outra unidade")
    query(f"UPDATE cp.membership_invitations SET status='REVOKED',updated_at=clock_timestamp(),version=version+1 WHERE id='{I1}'", **as_owner)
    expect(value(f"select status from cp.membership_invitations where id='{I1}'") == "REVOKED", "proprietario revoga convite pendente do inquilino")
    # Inquilino nao administra o proprietario.
    query(f"UPDATE cp.memberships SET status='ACTIVE' WHERE id='{MTEN}'".replace("UPDATE", "SET session_replication_role=replica; UPDATE"))
    expect(value(f"select count(*) from cp.memberships where id in ('{MOWN}','{MOWN3}')", **as_tenant) == "0", "inquilino nao ve o proprietario")
    expect(value(f"WITH u AS (UPDATE cp.memberships SET status='REVOKED' WHERE id='{MOWN}' RETURNING id) SELECT count(*) FROM u", **as_tenant) == "0",
           "inquilino nao revoga o proprietario")
    expect(error(invite(I2, role_name="OWNER", creator=TEN), "42501", **as_tenant), "inquilino nao convida proprietario")
    expect(value(f"WITH u AS (UPDATE cp.unit_rentals SET ended_by='{TEN}' RETURNING id) SELECT count(*) FROM u", **as_tenant) == "0",
           "inquilino nao encerra a locacao")
    expect(int(value(f"select count(*) from cp.reservations where unit_id='{UA}'", **as_tenant)) > 0, "inquilino tem a visao de morador da unidade")
    # Coproprietario aprovado durante a locacao tambem perde a visao; revogado encerra a propria linha.
    query(manage + f"UPDATE cp.memberships SET status='ACTIVE',version=version+1 WHERE id='{MOWN3}'", **as_manager)
    expect(value(f"select count(*) from cp.unit_rentals where owner_membership_id='{MOWN3}' and ended_at is null and started_by='{MGR}'") == "1",
           "coproprietario aprovado entra na locacao em vigor")
    query(manage + f"UPDATE cp.memberships SET status='REVOKED',version=version+1 WHERE id='{MOWN3}'", **as_manager)
    expect(value(f"select count(*) from cp.unit_rentals where owner_membership_id='{MOWN3}' and ended_at is not null and ended_by='{MGR}'") == "1",
           "proprietario revogado encerra a propria locacao")
    # Encerramento pela gestao: uma vez; o proprietario volta a ver o que o vinculo dele permite.
    end = f"UPDATE cp.unit_rentals SET ended_by='{{actor}}' WHERE owner_membership_id='{MOWN}' AND ended_at IS NULL"
    expect(value(f"WITH u AS ({end.format(actor=OWN)} RETURNING id) SELECT count(*) FROM u", **as_owner) == "0", "proprietario nao encerra a locacao")
    expect(error(manage + end.format(actor=OWN), "42501", **as_manager), "gestao encerra em nome da propria conta")
    query(manage + end.format(actor=MGR), **as_manager)
    expect(error(manage + f"UPDATE cp.unit_rentals SET ended_by='{MGR}' WHERE owner_membership_id='{MOWN}'", "23514", **as_manager),
           "locacao encerrada nao reabre nem e encerrada de novo")
    query(manage + audit.format(actor=MGR, action="UNIT_RENTAL_ENDED", kind="UNIT", resource=UA), **as_manager)
    visible = {t: value(f"select count(*) from cp.{t} where unit_id='{UA}'", **as_owner) for t in unit_tables}
    expect(sum(count != "0" for count in visible.values()) >= 5,
           f"controle: sem locacao o mesmo vinculo indevido volta a ver a unidade, provando que a barreira e a locacao: {visible}")
    expect(value(occupants, **as_owner) == "1", "sem locacao o proprietario volta a nao administrar ocupantes (so o proprio vinculo)")
    expect(value(audience, **as_owner) == "t", "sem locacao o proprietario volta a ser publico de avisos")
    expect(error("select count(*) from cp.unit_rentals", "42501", tenant=TA, role="cp_worker"), "worker nao le locacoes")
    expect(error(manage + f"DELETE FROM cp.unit_rentals WHERE owner_membership_id='{MOWN}'", "42501", **as_manager), "locacao nao e apagada")
    query(f"SET session_replication_role=replica; DELETE FROM cp.audit_log WHERE actor_id in ('{OWN}','{MGR}'); "
          f"DELETE FROM cp.access_credentials WHERE id='{CRED}'; DELETE FROM cp.unit_rentals WHERE tenant_id='{TA}'; "
          f"DELETE FROM cp.membership_invitations WHERE id in ('{I1}','{I2}','{I3}'); "
          f"DELETE FROM cp.memberships WHERE account_id in ('{OWN}','{OWN2}','{OWN3}','{TEN}','{DEP}','{MGR}'); "
          f"DELETE FROM cp.units WHERE id in ('{UO}','{UX}'); "
          f"DELETE FROM platform.accounts WHERE id in ('{OWN}','{OWN2}','{OWN3}','{TEN}','{DEP}','{MGR}')")

def technical_grant_checks():
    """V037 (AC-003): concessao tecnica em tres niveis. MASTER plataforma (technical_grants.manage) emite e revoga com
    trilha da plataforma; MASTER de implantacao so tem escopo no condominio concedido, enquanto vigente; gestor so le."""
    PL, MI, MG = "10000000-0000-4000-8000-0000000037a1", "10000000-0000-4000-8000-0000000037a2", "10000000-0000-4000-8000-0000000037a3"
    G1, G2, G3 = "37000000-0000-4000-8000-000000000001", "37000000-0000-4000-8000-000000000002", "37000000-0000-4000-8000-000000000003"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{PL}','ACTIVE'),('{MI}','ACTIVE'),('{MG}','ACTIVE');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status)
          VALUES('{TA}','{MG}',NULL,'GESTAO TECNICA','MANAGER','ACTIVE');
    """)
    plat = "SET LOCAL app.platform_capabilities='technical_grants.manage,tenants.read'; "
    scopes = "ARRAY['installation.manage','installation.bind_channel']"
    def issue(identifier, account=MI, tenant=TA, extra_scopes=scopes, days=30, by=PL, audit=True):
        audit_sql = (f"INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) VALUES('{by}',"
                     f"'TECHNICAL_GRANT_ISSUED','{tenant}','Implantacao piloto ficticia','{{\"grantId\":\"{identifier}\"}}'); ") if audit else ""
        return (plat + audit_sql + f"INSERT INTO cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,"
                f"granted_via,granted_by_account_id,reason) VALUES('{identifier}','{tenant}','{account}','MASTER_IMPLANTACAO',{extra_scopes},"
                f"now(),now()+interval '{days} days','PLATFORM_API','{by}','Implantacao piloto ficticia')")
    expect(error(issue(G1).replace(plat, "SET LOCAL app.platform_capabilities='tenants.read'; "), "42501", actor=PL, role="cp_api"),
           "HAC-010: sem technical_grants.manage nao emite concessao tecnica")
    expect(error(issue(G1, account=PL), "42501", actor=PL, role="cp_api"), "HAC-010: MASTER plataforma nao concede a si proprio")
    expect(error(issue(G1, extra_scopes="ARRAY['*']"), "23514", actor=PL, role="cp_api"), "HAC-010: escopo fora da lista fechada e recusado")
    expect(error(issue(G1, extra_scopes="ARRAY['installation.manage','installation.manage']"), "23514", actor=PL, role="cp_api"),
           "escopo repetido e recusado")
    expect(error(issue(G1, days=91), "23514", actor=PL, role="cp_api"), "validade acima de 90 dias e recusada")
    expect(error(issue(G1, audit=False), "23514", actor=PL, role="cp_api"), "HAC-018: emissao sem trilha da plataforma nao conclui")
    expect(error(issue(G1).replace("'PLATFORM_API'", "'OPERATIONS'"), "42501", actor=PL, role="cp_api"),
           "role runtime nao usa o caminho operacional")
    query(issue(G1), actor=PL, role="cp_api")
    expect(value(f"select status||':'||version||':'||(granted_by_db_user<>'') from cp.technical_grants where id='{G1}'") == "ACTIVE:0:true",
           "concessao nasce ativa na versao 0 com identidade do banco")
    tech = "SET LOCAL app.technical_scopes='installation.manage,installation.bind_channel,connector.rotate_secret'; "
    expect(value(tech + "select platform.has_technical_scope('installation.bind_channel')", tenant=TA, actor=MI, role="cp_api") == "t",
           "HAC-010: MASTER de implantacao tem o escopo concedido no condominio")
    expect(value("SET LOCAL app.technical_scopes='installation.manage,access.request_open'; select platform.has_technical_scope('access.request_open')",
                 tenant=TA, actor=MI, role="cp_api") == "f", "HAC-011: concessao administrativa nao da abertura fisica sem escopo explicito")
    expect(value(tech + "select platform.has_technical_scope('connector.rotate_secret')", tenant=TA, actor=MI, role="cp_api") == "f",
           "HAC-010: escopo publicado mas nao concedido no banco nao vale")
    expect(value(tech + "select platform.has_technical_scope('installation.bind_channel')", tenant=TB, actor=MI, role="cp_api") == "f",
           "HAC-007: concessao nao atravessa condominio")
    expect(value("select platform.has_technical_scope('installation.bind_channel')", tenant=TA, actor=MI, role="cp_api") == "f",
           "sem escopo publicado pela API (AAL2) nao vale")
    expect(value(tech + "select platform.has_technical_scope('installation.bind_channel')", tenant=TA, actor=MG, role="cp_api") == "f",
           "HAC-009: gestor nao herda escopo tecnico mesmo publicando o contexto")
    expect(value(f"select count(*) from platform.tenants where id='{TA}'", actor=MI, role="cp_api") == "1",
           "MASTER de implantacao enxerga o condominio da concessao")
    expect(value(f"select count(*) from platform.tenants where id='{TB}'", actor=MI, role="cp_api") == "0",
           "HAC-007: e nao enxerga outro condominio")
    expect(value("select count(*) from cp.technical_grants", tenant=TA, actor=MI, role="cp_api") == "1", "beneficiado ve a propria concessao")
    expect(value("SET LOCAL app.capabilities='memberships.manage'; select count(*) from cp.technical_grants", tenant=TA, actor=MG, role="cp_api") == "1",
           "gestor ve concessoes do proprio condominio")
    expect(value("SET LOCAL app.capabilities='memberships.manage'; select count(*) from cp.technical_grants", tenant=TB, actor=MG, role="cp_api") == "0",
           "HAC-007: gestor nao ve concessoes de outro condominio")
    expect(value("select count(*) from cp.technical_grants", tenant=TA, actor=AA, role="cp_api") == "0", "morador nao ve concessoes")
    expect(error("SET LOCAL app.capabilities='memberships.manage'; " + issue(G2, by=MG).replace(plat, ""), "42501", tenant=TA, actor=MG, role="cp_api"),
           "HAC-009: gestor nao emite concessao tecnica")
    expect(error(f"SET LOCAL app.capabilities='memberships.manage'; UPDATE cp.technical_grants SET status='REVOKED',revoke_reason='abc',"
                 f"revoked_by_account_id='{MG}',version=1 WHERE id='{G1}'", "42501", tenant=TA, actor=MG, role="cp_api") or
           value(f"select status from cp.technical_grants where id='{G1}'") == "ACTIVE", "HAC-009: gestor nao revoga concessao tecnica")
    expect(error(plat + f"UPDATE cp.technical_grants SET valid_until=now()+interval '60 days' WHERE id='{G1}'", "42501", actor=PL, role="cp_api"),
           "validade nao muda depois de emitida")
    expect(error(plat + f"UPDATE cp.technical_grants SET scopes=ARRAY['installation.read'],version=1 WHERE id='{G1}'", "42501", actor=PL, role="cp_api"),
           "escopos nao mudam depois de emitidos")
    expect(error(plat + f"DELETE FROM cp.technical_grants WHERE id='{G1}'", "42501", actor=PL, role="cp_api"), "concessao nao e apagada")
    revoke = (f"UPDATE cp.technical_grants SET status='REVOKED',revoke_reason='Fim do ensaio',revoked_by_account_id='{PL}',version=1 WHERE id='{G1}'")
    expect(error(plat + revoke, "23514", actor=PL, role="cp_api"), "HAC-018: revogacao sem trilha da plataforma nao conclui")
    query(plat + f"INSERT INTO platform.platform_audit_log(actor_id,action,tenant_id,reason,details) VALUES('{PL}','TECHNICAL_GRANT_REVOKED',"
          f"'{TA}','Fim do ensaio','{{\"grantId\":\"{G1}\"}}'); " + revoke, actor=PL, role="cp_api")
    expect(value(f"select status||':'||version||':'||(revoked_at is not null) from cp.technical_grants where id='{G1}'") == "REVOKED:1:true",
           "revogacao final com horario do banco")
    expect(value(tech + "select platform.has_technical_scope('installation.bind_channel')", tenant=TA, actor=MI, role="cp_api") == "f",
           "HAC-012: revogacao invalida o escopo na proxima instrucao, mesmo com contexto publicado")
    expect(value(f"select count(*) from platform.tenants where id='{TA}'", actor=MI, role="cp_api") == "0",
           "HAC-012: revogado deixa de enxergar o condominio")
    expect(error(plat + f"UPDATE cp.technical_grants SET status='ACTIVE',revoke_reason=NULL,revoked_by_account_id=NULL,version=2 WHERE id='{G1}'",
                 "42501", actor=PL, role="cp_api") or value(f"select status from cp.technical_grants where id='{G1}'") == "REVOKED",
           "concessao revogada nao volta a valer")
    # Vencida: mesma regra, sem job.
    query(issue(G3, days=90), actor=PL, role="cp_api")
    expect(value(f"select valid_until-valid_from from cp.technical_grants where id='{G3}'") == "90 days", "teto de 90 dias aceito exatamente")
    query(f"SET session_replication_role=replica; UPDATE cp.technical_grants SET valid_from=now()-interval '2 days',valid_until=now()-interval '1 day' WHERE id='{G3}'")
    expect(value(tech + "select platform.has_technical_scope('installation.manage')", tenant=TA, actor=MI, role="cp_api") == "f",
           "HAC-012: concessao vencida nao vale sem depender de job")
    expect(error(f"INSERT INTO cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,reason) "
                 f"VALUES('{G2}','{TA}','{MI}','TECHNICAL_SUPPORT',ARRAY['connector.rotate_secret'],now(),now()+interval '1 day','OPERATIONS',"
                 f"'Suporte ficticio sem escopo')", "23514"), "HAC-010: suporte tecnico nao recebe rotacao de segredo")
    expect(error(f"INSERT INTO cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,reason) "
                 f"VALUES('{G2}','{TA}','{MI}','MASTER_IMPLANTACAO',ARRAY['installation.read'],now()-interval '1 day',now()+interval '1 day','OPERATIONS',"
                 f"'Inicio retroativo ficticio')", "23514"), "inicio retroativo e recusado")
    expect(error("select count(*) from cp.technical_grants", "42501", tenant=TA, role="cp_worker"), "worker nao le concessoes tecnicas")
    expect(value("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='cp' and c.relname='technical_grants' "
                 "and c.relrowsecurity and c.relforcerowsecurity") == "1", "technical_grants com RLS FORCE")
    query(f"SET session_replication_role=replica; DELETE FROM platform.platform_audit_log WHERE actor_id='{PL}'; "
          f"DELETE FROM cp.technical_grants WHERE id in ('{G1}','{G2}','{G3}'); DELETE FROM cp.memberships WHERE account_id='{MG}'; "
          f"DELETE FROM platform.accounts WHERE id in ('{PL}','{MI}','{MG}')")

def subject_checks():
    """V038 (AC-005): sujeito por condominio e contextos explicitos. Casos HAC-029 (mesma pessoa em dois condominios) e
    HAC-030 (multiplos vinculos sem uniao permissiva), alem de bloqueio transversal x revogacao de um contexto."""
    MG, CO, RS, DP, X = ("10000000-0000-4000-8000-0000000038a1", "10000000-0000-4000-8000-0000000038a2", "10000000-0000-4000-8000-0000000038a3",
                         "10000000-0000-4000-8000-0000000038a4", "10000000-0000-4000-8000-0000000038a5")
    MRS, MDP, MXA, MXB, MMG = ("51000000-0000-4000-8000-0000000038a1", "51000000-0000-4000-8000-0000000038a2", "51000000-0000-4000-8000-0000000038a3",
                               "51000000-0000-4000-8000-0000000038a4", "51000000-0000-4000-8000-0000000038a5")
    SRS, SDP, SXA, SXB, SV = ("38000000-0000-4000-8000-000000000001", "38000000-0000-4000-8000-000000000002", "38000000-0000-4000-8000-000000000003",
                              "38000000-0000-4000-8000-000000000004", "38000000-0000-4000-8000-000000000005")
    CRS, CDP, CFA, CV = ("38100000-0000-4000-8000-000000000001", "38100000-0000-4000-8000-000000000002", "38100000-0000-4000-8000-000000000003",
                         "38100000-0000-4000-8000-000000000004")
    PV, VV, KN, CR = ("38200000-0000-4000-8000-000000000001", "38200000-0000-4000-8000-000000000002", "38200000-0000-4000-8000-000000000003",
                      "38200000-0000-4000-8000-000000000004")
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MG}','ACTIVE'),('{CO}','ACTIVE'),('{RS}','ACTIVE'),('{DP}','ACTIVE'),('{X}','ACTIVE');
        INSERT INTO cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) VALUES
          ('{MMG}','{TA}','{MG}',NULL,'GESTAO SUJEITO','MANAGER','ACTIVE'),(gen_random_uuid(),'{TA}','{CO}',NULL,'PORTARIA SUJEITO','CONCIERGE','ACTIVE'),
          ('{MRS}','{TA}','{RS}','{UA}','MORADOR SUJEITO','RESIDENT','ACTIVE'),('{MDP}','{TA}','{DP}','{UA}','DEPENDENTE SUJEITO','DEPENDENT','ACTIVE'),
          ('{MXA}','{TA}','{X}','{UA}','PESSOA X EM A','RESIDENT','ACTIVE'),('{MXB}','{TB}','{X}','{UB}','PESSOA X EM B','RESIDENT','ACTIVE');
        INSERT INTO cp.visitors(id,tenant_id,full_name,created_by) VALUES('{VV}','{TA}','VISITANTE SUJEITO','{RS}');
        INSERT INTO cp.visit_permits(id,tenant_id,visitor_id,unit_id,created_by,valid_from,valid_until)
          VALUES('{PV}','{TA}','{VV}','{UA}','{RS}',now(),now()+interval '1 day');
    """)
    mgr = "SET LOCAL app.capabilities='memberships.manage'; "
    con = "SET LOCAL app.capabilities='concierge.operate'; "
    as_mgr = dict(tenant=TA, actor=MG, role="cp_api")
    as_con = dict(tenant=TA, actor=CO, role="cp_api")
    def subject(identifier, account, tenant=TA):
        return f"INSERT INTO cp.access_subjects(id,tenant_id,origin_kind,account_id) VALUES('{identifier}','{tenant}','ACCOUNT','{account}')"
    def context(identifier, subj, membership, kind="RESIDENT", actor=MG, tenant=TA):
        return (f"INSERT INTO cp.subject_contexts(id,tenant_id,subject_id,context_kind,source_kind,membership_id,created_by) "
                f"VALUES('{identifier}','{tenant}','{subj}','{kind}','MEMBERSHIP','{membership}','{actor}')")
    def current(ctx):
        return value(mgr + f"select cp.subject_context_current('{TA}','{ctx}')", **as_mgr)
    expect(error(subject(SRS, RS), "42501", tenant=TA, actor=RS, role="cp_api"), "morador nao cria sujeito")
    query(mgr + subject(SRS, RS), **as_mgr)
    expect(value(f"select status||':'||restriction_epoch||':'||version||':'||(external_ref ~ '^cps_') from cp.access_subjects where id='{SRS}'")
           == "ACTIVE:0:0:true", "sujeito nasce ativo na epoca 0 com referencia opaca")
    expect(error(mgr + subject(SDP, RS), "23505", **as_mgr), "uma conta tem um sujeito por condominio")
    query(mgr + subject(SDP, DP), **as_mgr)
    # Tipo vem do vinculo, nao do cliente.
    query(mgr + context(CRS, SRS, MRS, kind="EMPLOYEE"), **as_mgr)
    expect(value(f"select context_kind||':'||unit_id||':'||source_version from cp.subject_contexts where id='{CRS}'") == f"RESIDENT:{UA}:0",
           "HAC-030: tipo, unidade e versao do contexto vem do vinculo")
    expect(error(mgr + context(CDP, SRS, MDP), "23514", **as_mgr), "vinculo de outra pessoa nao vira contexto do sujeito")
    expect(error(mgr + subject("38000000-0000-4000-8000-0000000000aa", MG) + "; " + context(CDP, "38000000-0000-4000-8000-0000000000aa", MMG), "23514", **as_mgr),
           "HAC-011: vinculo de gestao nao gera contexto de acesso fisico")
    expect(error(mgr + context(CDP, SRS, MRS), "23505", **as_mgr), "mesma origem nao duplica contexto ativo")
    query(mgr + context(CDP, SDP, MDP), **as_mgr)
    expect(value(f"select context_kind from cp.subject_contexts where id='{CDP}'") == "DEPENDENT", "dependente vira contexto DEPENDENT")
    # Mesma pessoa em dois condominios: dois sujeitos, nada atravessa.
    query(mgr + subject(SXA, X), **as_mgr)
    query(subject(SXB, X, tenant=TB))
    expect(value(f"select count(distinct external_ref) from cp.access_subjects where id in ('{SXA}','{SXB}')") == "2",
           "HAC-029: mesma conta em dois condominios gera dois sujeitos distintos")
    expect(error(mgr + context(CFA, SXA, MXB), "23514", **as_mgr), "HAC-029: vinculo de outro condominio nao completa associacao")
    expect(value(f"select count(*) from cp.access_subjects where id='{SXB}'", **as_mgr).strip() in ("0",""),
           "HAC-029: gestao de A nao ve sujeito de B")
    expect(value("select count(*) from cp.access_subjects", tenant=TA, actor=RS, role="cp_api") == "1", "morador ve so o proprio sujeito")
    # Autorizacao funcional: aprovador e fim obrigatorios; revogar um contexto nao bloqueia o sujeito.
    func = (f"INSERT INTO cp.subject_contexts(id,tenant_id,subject_id,context_kind,source_kind,functional_authorization_ref,"
            f"approved_by_account_id,valid_until,created_by) VALUES('{CFA}','{TA}','{SRS}','SERVICE_PROVIDER','FUNCTIONAL_AUTHORIZATION',"
            f"'os:manutencao-0001','{MG}',now()+interval '7 days','{MG}')")
    expect(error(mgr + func.replace("now()+interval '7 days'", "now()+interval '400 days'"), "23514", **as_mgr), "autorizacao funcional tem prazo maximo")
    expect(error(mgr + func.replace(f"'{MG}',now()", f"'{RS}',now()"), "42501", **as_mgr), "aprovador da autorizacao e o proprio gestor")
    query(mgr + func, **as_mgr)
    expect(current(CRS) == "t" and current(CFA) == "t", "contextos residencial e funcional vigentes")
    query(mgr + f"UPDATE cp.subject_contexts SET status='REVOKED',status_reason='Fim do servico',version=1 WHERE id='{CFA}'", **as_mgr)
    expect(current(CFA) == "f" and current(CRS) == "t", "HAC-030: revogar um contexto nao afeta a residencia do mesmo sujeito")
    expect(error(mgr + f"UPDATE cp.subject_contexts SET status='ACTIVE',status_reason=NULL,version=2 WHERE id='{CFA}'", "23514", **as_mgr),
           "contexto encerrado nao volta")
    expect(error(mgr + f"UPDATE cp.subject_contexts SET valid_until=now()+interval '9 days',version=1 WHERE id='{CRS}'", "42501", **as_mgr),
           "vigencia do contexto nao e editada")
    # Bloqueio transversal: eleva a epoca e derruba todos os contextos; desbloqueio eleva de novo.
    expect(error(mgr + f"UPDATE cp.access_subjects SET status='BLOCKED',blocked_reason='Ordem do sindico',blocked_by='{MG}',version=1 WHERE id='{SRS}'",
                 "23514", **as_mgr), "bloqueio sem elevar a epoca e recusado")
    query(mgr + f"UPDATE cp.access_subjects SET status='BLOCKED',blocked_reason='Ordem do sindico',blocked_by='{MG}',restriction_epoch=1,version=1 WHERE id='{SRS}'", **as_mgr)
    expect(current(CRS) == "f", "bloqueio do sujeito vale para todos os contextos")
    expect(error(mgr + f"UPDATE cp.access_subjects SET status='ACTIVE',blocked_reason=NULL,blocked_by=NULL,restriction_epoch=0,version=2 WHERE id='{SRS}'",
                 "23514", **as_mgr), "epoca nunca desce")
    query(mgr + f"UPDATE cp.access_subjects SET status='ACTIVE',blocked_reason=NULL,blocked_by=NULL,restriction_epoch=2,version=2 WHERE id='{SRS}'", **as_mgr)
    expect(current(CRS) == "t", "desbloqueio devolve o contexto ainda valido na origem")
    expect(error(f"UPDATE cp.access_subjects SET status='BLOCKED',blocked_reason='x',blocked_by='{RS}',restriction_epoch=3,version=3 WHERE id='{SRS}'",
                 "42501", tenant=TA, actor=RS, role="cp_api") or value(f"select status from cp.access_subjects where id='{SRS}'") == "ACTIVE",
           "morador nao bloqueia sujeito")
    # Origem mudou: projecao desatualizada deixa de valer sem job.
    query(f"UPDATE cp.memberships SET status='REVOKED',version=version+1 WHERE id='{MDP}'")
    expect(current(CDP) == "f", "vinculo revogado derruba o contexto materializado")
    # Visitante: so a portaria materializa; contexto do convite e independente da residencia.
    expect(error(mgr + f"INSERT INTO cp.access_subjects(id,tenant_id,origin_kind,visitor_id) VALUES('{SV}','{TA}','VISITOR','{VV}')", "42501", **as_mgr),
           "gestao nao cria sujeito visitante")
    query(con + f"INSERT INTO cp.access_subjects(id,tenant_id,origin_kind,visitor_id) VALUES('{SV}','{TA}','VISITOR','{VV}')", **as_con)
    query(con + f"INSERT INTO cp.subject_contexts(id,tenant_id,subject_id,context_kind,source_kind,permit_id,valid_until,created_by) "
          f"VALUES('{CV}','{TA}','{SV}','RESIDENT','VISIT_PERMIT','{PV}',now()+interval '30 days','{CO}')", **as_con)
    expect(value(f"select context_kind||':'||(valid_until<now()+interval '2 days') from cp.subject_contexts where id='{CV}'") == "VISITOR:true",
           "HAC-036: convite vira contexto VISITOR com a vigencia do convite")
    expect(value(mgr + f"select cp.subject_context_current('{TA}','{CV}')", **as_mgr) == "f",
           "sem a conferencia ligada a gestao nao le o convite")
    expect(value(mgr + f"SET LOCAL app.subject_context_scan='on'; select cp.subject_context_current('{TA}','{CV}')", **as_mgr) == "t",
           "com a conferencia da listagem a gestao ve a vigencia do visitante (so o booleano)")
    expect(value(f"SET LOCAL app.subject_context_scan='on'; select count(*) from cp.visit_permits where id='{PV}'", tenant=TA, actor=RS, role="cp_api") == "1"
           and value(f"SET LOCAL app.subject_context_scan='on'; select count(*) from cp.visit_permits", tenant=TA, actor=DP, role="cp_api") == "0",
           "a conferencia nao abre convites para quem nao e da gestao ou portaria")
    query(f"UPDATE cp.visit_permits SET state='REVOKED',revoked_by='{RS}',revoked_at=now(),revoke_reason='Cancelado',version=version+1 WHERE id='{PV}'")
    expect(value(con + f"select cp.subject_context_current('{TA}','{CV}')", **as_con) == "f" and current(CRS) == "t",
           "HAC-039: convite revogado derruba o contexto de visita e nao a residencia")
    # Revogacao de contexto vale ate reautorizacao explicita da gestao; convite revogado nunca volta.
    CXA, CXR = "38100000-0000-4000-8000-000000000005", "38100000-0000-4000-8000-000000000006"
    query(mgr + context(CXA, SXA, MXA), **as_mgr)
    query(mgr + f"UPDATE cp.subject_contexts SET status='REVOKED',status_reason='Ordem da gestao',version=1 WHERE id='{CXA}'", **as_mgr)
    expect(error(mgr + context(CXR, SXA, MXA), "23514", **as_mgr), "origem com contexto revogado nao e recriada sem reautorizacao")
    reauth = (f"INSERT INTO cp.subject_contexts(id,tenant_id,subject_id,context_kind,source_kind,membership_id,reauthorizes_context_id,created_by) "
              f"VALUES('{CXR}','{TA}','{SXA}','RESIDENT','MEMBERSHIP','{MXA}','{CXA}',")
    expect(error(con + reauth + f"'{CO}')", "42501", **as_con), "portaria nao reautoriza")
    query(mgr + reauth + f"'{MG}')", **as_mgr)
    expect(current(CXR) == "t", "reautorizacao explicita da gestao devolve o contexto")
    CXS = "38100000-0000-4000-8000-000000000007"
    query(f"UPDATE cp.memberships SET version=version+1 WHERE id='{MXA}'")
    query(mgr + f"UPDATE cp.subject_contexts SET status='SUPERSEDED',status_reason='Origem alterada',version=1 WHERE id='{CXR}'; " + context(CXS, SXA, MXA), **as_mgr)
    expect(current(CXS) == "t", "cadeia reautorizada segue: sucessor por mudanca na origem sem nova reautorizacao")
    expect(error(mgr + f"UPDATE cp.subject_contexts SET status='REVOKED',status_reason='Outra ordem',version=1 WHERE id='{CXS}'; "
                 + reauth.replace(CXR, "38100000-0000-4000-8000-000000000008") + f"'{MG}')", "23514", **as_mgr),
           "reautorizacao precisa citar a revogacao pendente mais recente")
    query(mgr + f"UPDATE cp.subject_contexts SET status='REVOKED',status_reason='Ordem da gestao',version=1 WHERE id='{CV}'", **as_mgr)
    expect(error(con + f"INSERT INTO cp.subject_contexts(tenant_id,subject_id,context_kind,source_kind,permit_id,created_by) "
                 f"VALUES('{TA}','{SV}','VISITOR','VISIT_PERMIT','{PV}','{CO}')", "23514", **as_con),
           "portaria nao desfaz contexto de visitante revogado pela gestao")
    query(mgr + "INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) "
          f"VALUES('{TA}','{MG}','SUBJECT_MATERIALIZED','ACCESS_SUBJECT','{TA}',gen_random_uuid())", **as_mgr)
    expect(value(f"select count(*) from cp.audit_log where actor_id='{MG}' and action='SUBJECT_MATERIALIZED'") == "1", "trilha SUBJECT_* da gestao")
    expect(error(f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) "
                 f"VALUES('{TA}','{RS}','SUBJECT_BLOCKED','ACCESS_SUBJECT','{SRS}',gen_random_uuid())", "42501", tenant=TA, actor=RS, role="cp_api"),
           "morador nao grava trilha de sujeito")
    # Credencial (CP-046): sujeito preenchido pelo banco; nao troca de sujeito.
    query(f"INSERT INTO cp.access_connectors(id,tenant_id,provider,label) VALUES('{KN}','{TA}','SIMULATED','SUJEITO')")
    query(mgr + f"INSERT INTO cp.access_credentials(id,tenant_id,connector_id,membership_id,unit_id,kind,label,created_by) "
          f"VALUES('{CR}','{TA}','{KN}','{MRS}','{UA}','CARD','Cartao do morador','{MG}')", **as_mgr)
    expect(value(f"select subject_id from cp.access_credentials where id='{CR}'") == SRS, "credencial nova recebe o sujeito da conta")
    expect(error(mgr + f"UPDATE cp.access_credentials SET subject_id='{SDP}' WHERE id='{CR}'", "42501", **as_mgr), "aplicacao nao troca o sujeito da credencial")
    expect(error(f"UPDATE cp.access_credentials SET subject_id='{SDP}' WHERE id='{CR}'", "23514"), "nem o dono do schema troca o sujeito ligado")
    query(f"UPDATE cp.memberships SET status='ACTIVE',version=version+1 WHERE id='{MDP}'")
    query(f"SET session_replication_role=replica; DELETE FROM cp.subject_contexts WHERE subject_id='{SDP}'; DELETE FROM cp.access_subjects WHERE id='{SDP}'")
    query(mgr + f"INSERT INTO cp.access_credentials(id,tenant_id,connector_id,membership_id,unit_id,kind,label,created_by) "
          f"VALUES('38200000-0000-4000-8000-000000000005','{TA}','{KN}','{MDP}','{UA}','QR','QR do dependente','{MG}')", **as_mgr)
    expect(value(f"select s.account_id from cp.access_credentials c join cp.access_subjects s on s.tenant_id=c.tenant_id and s.id=c.subject_id "
                 f"where c.id='38200000-0000-4000-8000-000000000005'") == DP, "credencial sem sujeito previo cria o sujeito da conta")
    expect(error(mgr + f"DELETE FROM cp.access_subjects WHERE id='{SRS}'", "42501", **as_mgr), "sujeito nao e apagado")
    expect(error(mgr + f"DELETE FROM cp.subject_contexts WHERE id='{CRS}'", "42501", **as_mgr), "contexto nao e apagado")
    expect(error("select account_id from cp.access_subjects", "42501", tenant=TA, role="cp_worker"), "worker nao le a conta do sujeito")
    expect(value("select count(*) from cp.access_subjects", tenant=TA, role="cp_worker") == value(f"select count(*) from cp.access_subjects where tenant_id='{TA}'")
           and value(f"select count(*) from cp.access_subjects where id in ('{SXA}','{SXB}')", tenant=TB, role="cp_worker") == "1",
           "worker le so o proprio condominio")
    expect(value("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='cp' "
                 "and c.relname in ('access_subjects','subject_contexts') and c.relrowsecurity and c.relforcerowsecurity") == "2",
           "sujeitos e contextos com RLS FORCE")
    query(f"SET session_replication_role=replica; DELETE FROM cp.access_credentials WHERE connector_id='{KN}'; DELETE FROM cp.access_connectors WHERE id='{KN}'; "
          f"DELETE FROM cp.audit_log WHERE actor_id in ('{MG}','{CO}'); "
          f"DELETE FROM cp.subject_contexts WHERE tenant_id in ('{TA}','{TB}'); DELETE FROM cp.access_subjects WHERE tenant_id in ('{TA}','{TB}'); "
          f"DELETE FROM cp.visit_permits WHERE id='{PV}'; DELETE FROM cp.visitors WHERE id='{VV}'; "
          f"DELETE FROM cp.memberships WHERE account_id in ('{MG}','{CO}','{RS}','{DP}','{X}'); "
          f"DELETE FROM platform.accounts WHERE id in ('{MG}','{CO}','{RS}','{DP}','{X}')")

def age_checks():
    """V039 (AC-006): evidencia de idade minimizada e parametros por condominio. Casos HAC-033 (sem estimativa, sem
    presuncao), HAC-034 (correcao cadastral substitui) e HAC-085 (portaria e worker nao leem a evidencia)."""
    MG, CO, RS = "10000000-0000-4000-8000-0000000039a1", "10000000-0000-4000-8000-0000000039a2", "10000000-0000-4000-8000-0000000039a3"
    S1, S2 = "39000000-0000-4000-8000-000000000001", "39000000-0000-4000-8000-000000000002"
    E1, E2, E3, EB = ("39100000-0000-4000-8000-000000000001", "39100000-0000-4000-8000-000000000002", "39100000-0000-4000-8000-000000000003",
                      "39100000-0000-4000-8000-000000000004")
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MG}','ACTIVE'),('{CO}','ACTIVE'),('{RS}','ACTIVE');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) VALUES
          ('{TA}','{MG}',NULL,'GESTAO IDADE','MANAGER','ACTIVE'),('{TA}','{CO}',NULL,'PORTARIA IDADE','CONCIERGE','ACTIVE'),
          ('{TA}','{RS}','{UA}','MORADOR IDADE','RESIDENT','ACTIVE');
        INSERT INTO cp.access_subjects(id,tenant_id,origin_kind,account_id) VALUES('{S1}','{TA}','ACCOUNT','{RS}'),('{S2}','{TB}','ACCOUNT','{RS}');
    """)
    mgr = "SET LOCAL app.capabilities='memberships.manage'; "
    as_mgr = dict(tenant=TA, actor=MG, role="cp_api")
    def att(identifier, subject=S1, minimum=18, result="true", by=MG, extra_cols="", extra_vals="", tenant=TA):
        return (f"INSERT INTO cp.age_evidence(id,tenant_id,subject_id,method,minimum_age,predicate_result,verification_method,source_evidence_ref,"
                f"verified_by_account_id,valid_until{extra_cols}) VALUES('{identifier}','{tenant}','{subject}','AGE_PREDICATE_ATTESTATION',{minimum},"
                f"{result},'DOCUMENT_REVIEW','ref:conferencia-0001','{by}',now()+interval '365 days'{extra_vals})")
    expect(error(att(E1), "42501", tenant=TA, actor=CO, role="cp_api"), "HAC-085: portaria nao registra evidencia de idade")
    expect(error("SET LOCAL app.capabilities='concierge.operate'; " + att(E1, by=CO), "42501", tenant=TA, actor=CO, role="cp_api"),
           "portaria com capacidade de turno tambem nao registra")
    expect(error(mgr + att(E1, by=RS), "42501", **as_mgr), "verificador e a propria conta")
    expect(error(mgr + att(E1).replace("now()+interval '365 days'", "now()+interval '4000 days'"), "23514", **as_mgr), "validade longa demais")
    cipher_mixed = att(E1, extra_cols=",birth_date_ciphertext,encryption_key_ref,encryption_key_version",
                       extra_vals=",decode(repeat('ab',40),'hex'),'env:CP_AGE_EVIDENCE_KEY','v1'")
    expect(error(mgr + cipher_mixed, "23514", **as_mgr), "atestacao nao carrega data cifrada na mesma linha")
    plain = (f"INSERT INTO cp.age_evidence(id,tenant_id,subject_id,method,verification_method,source_evidence_ref,verified_by_account_id,valid_until) "
             f"VALUES('{E1}','{TA}','{S1}','ENCRYPTED_BIRTH_DATE','DOCUMENT_REVIEW','ref:conferencia-0001','{MG}',now()+interval '1 day')")
    expect(error(mgr + plain, "23514", **as_mgr), "data protegida exige envelope cifrado e chave")
    expect(value("select count(*) from information_schema.columns where table_schema='cp' and table_name='age_evidence' "
                 "and (column_name like '%birth%' and data_type<>'bytea')") == "0", "HAC-085: nenhuma coluna de data de nascimento em claro")
    query(mgr + att(E1), **as_mgr)
    expect(value(f"select status||':'||version||':'||(verified_at<=now()) from cp.age_evidence where id='{E1}'") == "VALID:0:true",
           "evidencia nasce valida na versao 0 com horario do banco")
    expect(error(mgr + att(E2), "23505", **as_mgr), "uma evidencia valida por sujeito")
    expect(error(mgr + f"UPDATE cp.age_evidence SET minimum_age=21,status='REVOKED',status_reason='Ajuste',status_changed_by='{MG}',version=1 WHERE id='{E1}'",
                 "42501", **as_mgr), "conteudo da evidencia nao muda")
    expect(error(mgr + att(E2, extra_cols=",supersedes_evidence_id", extra_vals=f",'{E1}'"), "23514", **as_mgr),
           "substituicao exige encerrar a anterior antes")
    # HAC-034: correcao cadastral substitui na mesma transacao.
    query(mgr + f"UPDATE cp.age_evidence SET status='SUPERSEDED',status_reason='Correcao cadastral',status_changed_by='{MG}',version=1 WHERE id='{E1}'; "
          + att(E2, minimum=18, result="false", extra_cols=",supersedes_evidence_id", extra_vals=f",'{E1}'"), **as_mgr)
    expect(value(f"select string_agg(status,',' order by created_at) from cp.age_evidence where subject_id='{S1}'") == "SUPERSEDED,VALID",
           "HAC-034: correcao substitui a evidencia anterior, que nao volta")
    expect(error(mgr + f"UPDATE cp.age_evidence SET status='VALID',status_reason=NULL,status_changed_by=NULL,version=2 WHERE id='{E1}'", "23514", **as_mgr),
           "evidencia substituida nao volta a valer")
    expect(error(mgr + att(EB, subject=S2, tenant=TB), "42501", **as_mgr), "HAC-029: evidencia de outro condominio e recusada pela fronteira")
    expect(error(mgr + att(EB, subject=S2), "23503", **as_mgr), "sujeito de outro condominio nao completa associacao")
    expect(value("select count(*) from cp.age_evidence", tenant=TA, actor=CO, role="cp_api") == "0", "portaria nao le evidencia de idade")
    expect(value("select count(*) from cp.age_evidence", tenant=TA, actor=RS, role="cp_api") == "0", "morador nao le a propria evidencia bruta")
    expect(error("select count(*) from cp.age_evidence", "42501", tenant=TA, role="cp_worker"), "HAC-085: worker nao le evidencia de idade")
    expect(error(mgr + f"DELETE FROM cp.age_evidence WHERE id='{E2}'", "42501", **as_mgr), "evidencia nao e apagada")
    # Parametros por condominio.
    settings = (f"INSERT INTO cp.facial_access_settings(tenant_id,leap_day_rule,minor_face_enrollment_enabled,age_evidence_validity_days,updated_by,version) "
                f"VALUES('{TA}','FEBRUARY_28',false,365,'{MG}',1)")
    expect(error(settings, "42501", tenant=TA, actor=CO, role="cp_api"), "portaria nao altera parametros")
    expect(error(mgr + settings.replace("'FEBRUARY_28'", "'JANUARY_1'"), "23514", **as_mgr), "criterio de 29/02 de lista fechada")
    query(mgr + settings, **as_mgr)
    expect(error(mgr + f"UPDATE cp.facial_access_settings SET minor_face_enrollment_enabled=true,updated_by='{MG}',version=5 WHERE tenant_id='{TA}'",
                 "23514", **as_mgr), "parametros mudam pela versao lida")
    query(mgr + f"UPDATE cp.facial_access_settings SET minor_face_enrollment_enabled=true,updated_by='{MG}',version=2 WHERE tenant_id='{TA}'", **as_mgr)
    expect(value(f"select leap_day_rule||':'||minor_face_enrollment_enabled||':'||version from cp.facial_access_settings where tenant_id='{TA}'")
           == "FEBRUARY_28:true:2", "parametros por condominio com versao")
    expect(value("select count(*) from cp.facial_access_settings", tenant=TB, actor=MG, role="cp_api") == "0", "parametros nao atravessam condominio")
    expect(value("select leap_day_rule from cp.facial_access_settings", tenant=TA, role="cp_worker") == "FEBRUARY_28", "worker le os parametros")
    expect(value("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='cp' "
                 "and c.relname in ('age_evidence','facial_access_settings') and c.relrowsecurity and c.relforcerowsecurity") == "2",
           "evidencia e parametros com RLS FORCE")
    query(f"SET session_replication_role=replica; DELETE FROM cp.age_evidence WHERE tenant_id in ('{TA}','{TB}'); "
          f"DELETE FROM cp.facial_access_settings WHERE tenant_id in ('{TA}','{TB}'); DELETE FROM cp.access_subjects WHERE id in ('{S1}','{S2}'); "
          f"DELETE FROM cp.memberships WHERE account_id in ('{MG}','{CO}','{RS}'); DELETE FROM platform.accounts WHERE id in ('{MG}','{CO}','{RS}')")

def homologation_checks():
    """V040 (AC-019, dados): catalogo global documental e homologacao por combinacao. Casos HAC-001 (combinacao exata),
    HAC-002 (declarado x observado x aprovado), HAC-092 (sem heranca entre marcas) e HAC-096 (nova revisao, sem promocao)."""
    PL, MG = "10000000-0000-4000-8000-0000000040a1", "10000000-0000-4000-8000-0000000040a2"
    H1, H2, H3 = "40000000-0000-4000-8000-000000000001", "40000000-0000-4000-8000-000000000002", "40000000-0000-4000-8000-000000000003"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{PL}','ACTIVE'),('{MG}','ACTIVE');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) VALUES('{TA}','{MG}',NULL,'GESTAO CATALOGO','MANAGER','ACTIVE');
    """)
    plat = "SET LOCAL app.platform_capabilities='access_catalog.manage,homologation.approve'; "
    names = ["doorContactEvents","eventBackfill","faceDeletion","faceEnrollment","localValidity","offlineRestriction",
             "onlineDecisionBeforeRelay","passageEvents","perDoorRights","safeRecovery","scopedRemoteOpen","secureTransport"]
    def caps(observed=(), approved=False, drop=None):
        body = ",".join(f'"{n}":{{"documented":true,"observed":{"true" if n in observed else "null"},"approved":{"true" if approved else "null"}}}'
                        for n in names if n != drop)
        return "'{" + body + "}'::jsonb"
    profile = value(f"select id from platform.access_model_profiles where catalog_code='CI-M01'")
    other = value(f"select id from platform.access_model_profiles where catalog_code='HK-M01'") if value(
        "select count(*) from platform.access_model_profiles where catalog_code='HK-M01'") == "1" else value(
        "select id from platform.access_model_profiles where manufacturer='HIKVISION' order by catalog_code limit 1")
    def create(identifier, prof=profile, firmware="1.2.3", topology="LOCAL_GATEWAY", c=None, audit=True):
        a = (f"INSERT INTO platform.platform_audit_log(actor_id,action,reason,details) VALUES('{PL}','HOMOLOGATION_CREATED','Dossie ficticio',"
             f"'{{\"homologationId\":\"{identifier}\"}}'); ") if audit else ""
        return (plat + a + "INSERT INTO platform.access_homologations(id,model_profile_id,firmware_exact,adapter_name,adapter_version,decision_mode,"
                f"topology,contract_schema_version,capabilities,limits,evidence_refs,reason,requested_by) VALUES('{identifier}','{prof}','{firmware}',"
                f"'control-id-rest','0.1.0','EDGE_ONLINE','{topology}','v1',{c or caps(observed=('faceEnrollment',))},'{{}}'::jsonb,"
                f"ARRAY['41000000-0000-4000-8000-000000000001']::uuid[],'Dossie de ensaio ficticio','{PL}')")
    def decide(identifier, decision, extra_cols="", extra_vals="", evidence="ARRAY['41000000-0000-4000-8000-000000000002']::uuid[]", audit=True):
        a = (f"INSERT INTO platform.platform_audit_log(actor_id,action,reason,details) VALUES('{PL}','HOMOLOGATION_{decision}','Decisao ficticia',"
             f"'{{\"homologationId\":\"{identifier}\"}}'); ") if audit else ""
        return (plat + a + f"INSERT INTO platform.access_homologation_decisions(homologation_id,decision,reason,evidence_refs,decided_by{extra_cols}) "
                f"VALUES('{identifier}','{decision}','Decisao de ensaio ficticia',{evidence},'{PL}'{extra_vals}); "
                f"UPDATE platform.access_homologations SET status=platform.homologation_next(status,'{decision}'),version=version+1 WHERE id='{identifier}'")
    expect(value("select count(*) from platform.access_model_profiles where status='CANDIDATE' and market_lifecycle='DOCUMENTED_CANDIDATE'") == "27",
           "HAC-002: catalogo semeado so como candidato documentado")
    expect(value("SET LOCAL app.capabilities='memberships.manage'; select count(*) from platform.access_model_profiles", tenant=TA, actor=MG, role="cp_api") == "27",
           "gestao consulta o catalogo")
    expect(value("select count(*) from platform.access_model_profiles", tenant=TA, actor=AA, role="cp_api") == "0", "morador nao consulta o catalogo")
    expect(value("select count(*) from platform.access_model_profiles", role="cp_worker", actor=None) == "27", "worker le o catalogo tecnico")
    expect(error(plat + f"UPDATE platform.access_model_profiles SET notes='x' WHERE id='{profile}'", "42501", actor=PL, role="cp_api"),
           "perfil do catalogo nao muda pela aplicacao")
    expect(error(create(H1, audit=False), "23514", actor=PL, role="cp_api"), "dossie sem trilha da plataforma nao conclui")
    expect(error(create(H1, c=caps(approved=True)), "23514", actor=PL, role="cp_api"), "HAC-002: aprovado nunca vem do cliente")
    expect(error(create(H1, c=caps(drop='safeRecovery')), "23514", actor=PL, role="cp_api"), "capacidades exigem as 12 chaves")
    expect(error(create(H1, firmware="1.*"), "23514", actor=PL, role="cp_api"), "HAC-001: firmware sem curinga")
    expect(error(create(H1, topology="ANY"), "23514", actor=PL, role="cp_api"), "topologia so LOCAL_GATEWAY ou CLOUD_SERVICE")
    expect(error("SET LOCAL app.capabilities='memberships.manage'; " + create(H1).replace(plat, ""), "42501", tenant=TA, actor=MG, role="cp_api"),
           "gestao nao cria dossie")
    query(create(H1), actor=PL, role="cp_api")
    query(create(H2), actor=PL, role="cp_api")
    query(create(H3, prof=other, topology="CLOUD_SERVICE"), actor=PL, role="cp_api")
    expect(value(f"select string_agg(homologation_revision::text,',' order by homologation_revision) from platform.access_homologations where id in ('{H1}','{H2}')") == "1,2",
           "HAC-096: mesma combinacao gera nova revisao")
    expect(value(f"select status||':'||version||':'||length(evidence_hash) from platform.access_homologations where id='{H1}'") == "CANDIDATO_DOCUMENTADO:0:64",
           "dossie nasce candidato com hash de evidencias do banco")
    expect(error(plat + f"UPDATE platform.access_homologations SET status='HOMOLOGADO',version=1 WHERE id='{H1}'", "23514", actor=PL, role="cp_api"),
           "estado so muda por decisao registrada")
    expect(error(decide(H1, "APPROVE_LAB"), "23514", actor=PL, role="cp_api"), "aprovar bancada exige ensaio iniciado")
    query(decide(H1, "START_TRIAL", evidence="'{}'::uuid[]"), actor=PL, role="cp_api")
    expect(error(decide(H1, "APPROVE_LAB", evidence="'{}'::uuid[]"), "23514", actor=PL, role="cp_api"), "aprovacao exige evidencia")
    expect(error(decide(H1, "APPROVE_LAB", ",approved_capabilities", ",ARRAY['perDoorRights']"), "23514", actor=PL, role="cp_api"),
           "HAC-002: so capacidade observada pode ser aprovada")
    expect(error(decide(H1, "APPROVE_LAB", audit=False), "23514", actor=PL, role="cp_api"), "decisao sem trilha nao conclui")
    query(decide(H1, "APPROVE_LAB", ",approved_capabilities", ",ARRAY['faceEnrollment']"), actor=PL, role="cp_api")
    expect(value(f"select status||':'||array_to_string(approved_capabilities,',')||':'||(approved_by='{PL}') from platform.access_homologations where id='{H1}'")
           == "APROVADO_BANCADA:faceEnrollment:true", "aprovacao de bancada grava capacidades e autoria pelo banco")
    expect(value(f"select status from platform.access_homologations where id='{H3}'") == "CANDIDATO_DOCUMENTADO",
           "HAC-092: aprovacao de uma marca nao se estende a outra")
    expect(error(decide(H1, "APPROVE_PRODUCTION"), "23514", actor=PL, role="cp_api"), "producao exige piloto aprovado antes")
    query(decide(H1, "SUSPEND", evidence="'{}'::uuid[]"), actor=PL, role="cp_api")
    expect(error(decide(H1, "APPROVE_PILOT"), "23514", actor=PL, role="cp_api"), "suspenso nao volta sem nova revisao")
    expect(error(plat + f"UPDATE platform.access_homologation_decisions SET reason='Outra decisao' WHERE homologation_id='{H1}'", "42501", actor=PL, role="cp_api"),
           "decisao nao muda")
    expect(error(plat + f"DELETE FROM platform.access_homologations WHERE id='{H2}'", "42501", actor=PL, role="cp_api"), "homologacao nao e apagada")
    expect(error(plat + f"UPDATE platform.access_homologations SET firmware_exact='9.9',version=version+1 WHERE id='{H2}'", "42501", actor=PL, role="cp_api"),
           "conteudo tecnico nao muda")
    expect(value("SET LOCAL app.capabilities='memberships.manage'; select count(*) from platform.access_homologation_decisions", tenant=TA, actor=MG, role="cp_api") == "0",
           "gestao ve o estado, nao a trilha de decisoes")
    expect(value("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='platform' "
                 "and c.relname in ('access_model_profiles','access_homologations','access_homologation_decisions') and c.relrowsecurity and c.relforcerowsecurity") == "3",
           "catalogo e homologacoes com RLS FORCE")
    query(f"SET session_replication_role=replica; DELETE FROM platform.access_homologation_decisions WHERE homologation_id in ('{H1}','{H2}','{H3}'); "
          f"DELETE FROM platform.access_homologations WHERE id in ('{H1}','{H2}','{H3}'); DELETE FROM platform.platform_audit_log WHERE actor_id='{PL}'; "
          f"DELETE FROM cp.memberships WHERE account_id='{MG}'; DELETE FROM platform.accounts WHERE id in ('{PL}','{MG}')")

def area_checks():
    """V041 (AC-004): areas e pontos por condominio. Casos HAC-009 (gestor sem acao tecnica), HAC-019 (ponto e sentido
    explicitos), HAC-027 (gestor nao altera mapeamento fisico) e HAC-007 (isolamento)."""
    MG, MI, CO = "10000000-0000-4000-8000-0000000041a1", "10000000-0000-4000-8000-0000000041a2", "10000000-0000-4000-8000-0000000041a3"
    A1, A2, A3, AB = ("41100000-0000-4000-8000-000000000001", "41100000-0000-4000-8000-000000000002", "41100000-0000-4000-8000-000000000003",
                      "41100000-0000-4000-8000-000000000004")
    P1, G1 = "41200000-0000-4000-8000-000000000001", "41300000-0000-4000-8000-000000000001"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MG}','ACTIVE'),('{MI}','ACTIVE'),('{CO}','ACTIVE');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) VALUES
          ('{TA}','{MG}',NULL,'GESTAO AREA','MANAGER','ACTIVE'),('{TA}','{CO}',NULL,'PORTARIA AREA','CONCIERGE','ACTIVE');
        INSERT INTO cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,reason)
          VALUES('{G1}','{TA}','{MI}','MASTER_IMPLANTACAO',ARRAY['installation.read','installation.manage'],now(),now()+interval '30 days','OPERATIONS','Implantacao ficticia');
    """)
    mgr = "SET LOCAL app.capabilities='area.manage'; "
    tech = "SET LOCAL app.technical_scopes='installation.manage,installation.read'; "
    as_mgr = dict(tenant=TA, actor=MG, role="cp_api")
    as_tech = dict(tenant=TA, actor=MI, role="cp_api")
    def area(identifier, code, kind="COMMON_AREA", by=MG, extra_cols="", extra_vals="", tz="America/Sao_Paulo", tenant=TA):
        return (f"INSERT INTO cp.access_areas(id,tenant_id,code,name,area_kind,timezone,created_by{extra_cols}) "
                f"VALUES('{identifier}','{tenant}','{code}','Area {code}','{kind}','{tz}','{by}'{extra_vals})")
    expect(error(area(A1, "EXTERIOR", "EXTERIOR"), "42501", tenant=TA, actor=AA, role="cp_api"), "morador nao cria area")
    expect(error("SET LOCAL app.capabilities='memberships.manage'; " + area(A1, "EXTERIOR", "EXTERIOR"), "42501", **as_mgr),
           "memberships.manage sozinho nao cria area")
    expect(error(mgr + area(A1, "EXTERIOR", "EXTERIOR", tz="Lua/Base"), "23514", **as_mgr), "fuso IANA conferido")
    expect(error(mgr + area(A1, "EXTERIOR", "EXTERIOR", extra_cols=",essential_route", extra_vals=",true"), "42501", **as_mgr),
           "HAC-027: gestor nao marca rota essencial")
    query(mgr + area(A1, "EXTERIOR", "EXTERIOR"), **as_mgr)
    query(mgr + area(A2, "HALL", "RESIDENTIAL_ROUTE", extra_cols=",parent_area_id", extra_vals=f",'{A1}'"), **as_mgr)
    query(mgr + area(A3, "ACADEMIA", extra_cols=",parent_area_id", extra_vals=f",'{A2}'"), **as_mgr)
    expect(error(mgr + area(A3, "ACADEMIA2"), "23505", **as_mgr), "id unico")
    expect(error(mgr + f"UPDATE cp.access_areas SET parent_area_id='{A3}',updated_by='{MG}',version=1 WHERE id='{A1}'", "23514", **as_mgr),
           "hierarquia sem ciclo")
    expect(error(mgr + f"UPDATE cp.access_areas SET essential_route=true,updated_by='{MG}',version=1 WHERE id='{A2}'", "42501", **as_mgr),
           "HAC-009: gestor nao altera campo fisico da area")
    query(tech + f"UPDATE cp.access_areas SET essential_route=true,physical_boundary_verified=true,updated_by='{MI}',version=1 WHERE id='{A2}'", **as_tech)
    expect(value(f"select essential_route||':'||physical_boundary_verified||':'||updated_by from cp.access_areas where id='{A2}'") == f"true:true:{MI}",
           "MASTER de implantacao marca rota essencial e separacao fisica")
    query(mgr + f"UPDATE cp.access_areas SET name='Hall social',status='ACTIVE',updated_by='{MG}',version=2 WHERE id='{A2}'", **as_mgr)
    expect(error(mgr + f"UPDATE cp.access_areas SET code='OUTRO',updated_by='{MG}',version=3 WHERE id='{A2}'", "42501", **as_mgr), "codigo da area fixo")
    expect(error(mgr + f"UPDATE cp.access_areas SET name='x',updated_by='{MG}',version=9 WHERE id='{A2}'", "23514", **as_mgr), "versao sequencial")
    expect(error(mgr + area(AB, "OUTRA", tenant=TB), "42501", **as_mgr), "HAC-007: area de outro condominio pela fronteira")
    point = (f"INSERT INTO cp.access_points(id,tenant_id,code,name,from_area_id,to_area_id,point_kind,flow_direction,egress_class,created_by) "
             f"VALUES('{P1}','{TA}','P-ACADEMIA','Porta da academia','{A2}','{A3}','DOOR','BOTH','FREE_EGRESS',")
    expect(error(mgr + point + f"'{MG}')", "42501", **as_mgr), "HAC-009: gestor nao cria ponto fisico")
    expect(error(tech + point.replace(f"'{A3}','DOOR'", f"'{A2}','DOOR'") + f"'{MI}')", "23514", **as_tech), "origem e destino distintos")
    expect(error(tech + point.replace("'FREE_EGRESS',", "'FREE_EGRESS','COMMISSIONED',").replace("egress_class,created_by", "egress_class,status,created_by")
                 + f"'{MI}')", "23514", **as_tech), "ponto nao nasce comissionado")
    query(tech + point + f"'{MI}')", **as_tech)
    expect(error(tech + f"UPDATE cp.access_points SET status='COMMISSIONED',updated_by='{MI}',version=1 WHERE id='{P1}'", "23514", **as_tech),
           "comissionado so pelo aceite tecnico")
    expect(error(mgr + f"UPDATE cp.access_points SET name='Outra',updated_by='{MG}',version=1 WHERE id='{P1}'", "42501", **as_mgr) or
           value(f"select name from cp.access_points where id='{P1}'") == "Porta da academia", "HAC-027: gestor nao altera ponto")
    expect(value(mgr + f"select name from cp.access_points where id='{P1}'", **as_mgr) == "Porta da academia", "gestao le o nome do local")
    expect(value("SET LOCAL app.capabilities='concierge.operate'; select count(*) from cp.access_points", tenant=TA, actor=CO, role="cp_api") == "1",
           "portaria le o nome do local")
    expect(value("select count(*) from cp.access_points", tenant=TA, actor=AA, role="cp_api") == "0", "morador nao le o mapa tecnico")
    expect(value(mgr + "select count(*) from cp.access_points", tenant=TB, actor=MG, role="cp_api") == "0", "HAC-007: mapa nao atravessa condominio")
    expect(error(tech + f"UPDATE cp.access_points SET status='RETIRED',updated_by='{MI}',version=1 WHERE id='{P1}'; "
                 f"UPDATE cp.access_points SET status='MAPPED',updated_by='{MI}',version=2 WHERE id='{P1}'", "23514", **as_tech), "retirada final")
    # Ponto ligado a area retirada ainda pode ser suspenso ou retirado; so nao pode ser religado a ela.
    P2 = "41200000-0000-4000-8000-000000000002"
    query(tech + point.replace(P1, P2).replace("'P-ACADEMIA'", "'P-SALA'") + f"'{MI}')", **as_tech)
    query(mgr + f"UPDATE cp.access_areas SET status='RETIRED',updated_by='{MG}',version=version+1 WHERE id='{A3}'", **as_mgr)
    query(tech + f"UPDATE cp.access_points SET status='SUSPENDED',updated_by='{MI}',version=1 WHERE id='{P2}'", **as_tech)
    query(tech + f"UPDATE cp.access_points SET status='RETIRED',updated_by='{MI}',version=2 WHERE id='{P2}'", **as_tech)
    expect(value(f"select status from cp.access_points where id='{P2}'") == "RETIRED", "ponto de area retirada pode ser suspenso e retirado")
    expect(error(tech + point.replace(P1, "41200000-0000-4000-8000-000000000003").replace("'P-ACADEMIA'", "'P-NOVO'") + f"'{MI}')", "23514", **as_tech),
           "nao se liga ponto novo a area retirada")
    # Write skew na hierarquia: duas trocas de pai cruzadas e concorrentes; so uma conclui.
    X1, X2 = "41100000-0000-4000-8000-000000000011", "41100000-0000-4000-8000-000000000012"
    query(mgr + area(X1, "X1") + "; " + area(X2, "X2"), **as_mgr)
    def swap(child, parent):
        return query(mgr + f"UPDATE cp.access_areas SET parent_area_id='{parent}',updated_by='{MG}',version=1 WHERE id='{child}'; SELECT pg_sleep(1)",
                     check=False, **as_mgr)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda pair: swap(*pair), [(X1, X2), (X2, X1)]))
    expect(sum(r.returncode == 0 for r in results) == 1 and value(f"select count(*) from cp.access_areas where id in ('{X1}','{X2}') and parent_area_id is not null") == "1",
           "trocas de pai concorrentes passam em serie e nao formam ciclo")
    expect(error(tech + f"DELETE FROM cp.access_points WHERE id='{P1}'", "42501", **as_tech), "ponto nao e apagado")
    expect(error(mgr + f"DELETE FROM cp.access_areas WHERE id='{A3}'", "42501", **as_mgr), "area nao e apagada")
    expect(error("select emergency_design_ref from cp.access_points", "42501", tenant=TA, role="cp_worker"), "worker nao le referencias de projeto")
    expect(value("select count(*) from cp.access_points", tenant=TA, role="cp_worker") == "2", "worker le o mapa do condominio")
    expect(error(mgr + f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) VALUES('{TA}','{MG}','POINT_CREATED','ACCESS_POINT','{P1}',gen_random_uuid())",
                 "42501", **as_mgr), "trilha POINT_* so do MASTER de implantacao")
    expect(value("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='cp' "
                 "and c.relname in ('access_areas','access_points') and c.relrowsecurity and c.relforcerowsecurity") == "2", "areas e pontos com RLS FORCE")
    query(f"SET session_replication_role=replica; DELETE FROM cp.audit_log WHERE actor_id in ('{MG}','{MI}'); DELETE FROM cp.access_points WHERE tenant_id in ('{TA}','{TB}'); "
          f"DELETE FROM cp.access_areas WHERE tenant_id in ('{TA}','{TB}'); DELETE FROM cp.technical_grants WHERE id='{G1}'; "
          f"DELETE FROM cp.memberships WHERE account_id in ('{MG}','{MI}','{CO}'); DELETE FROM platform.accounts WHERE id in ('{MG}','{MI}','{CO}')")

FACIAL_CAPS = ["doorContactEvents","eventBackfill","faceDeletion","faceEnrollment","localValidity","offlineRestriction",
               "onlineDecisionBeforeRelay","passageEvents","perDoorRights","safeRecovery","scopedRemoteOpen","secureTransport"]

def facial_homologation(platform_actor, identifier, approved, firmware, pilot=True):
    """Homologacao pelo fluxo real da plataforma (dossie, trilha e decisoes): candidata documentada ou aprovada para piloto
    com as capacidades observadas/aprovadas indicadas."""
    profile = value("select id from platform.access_model_profiles order by catalog_code limit 1")
    obs = "'{" + ",".join(f'"{c}":{{"documented":true,"observed":{"true" if c in approved else "null"}}}' for c in FACIAL_CAPS) + "}'::jsonb"
    plat = "SET LOCAL app.platform_capabilities='access_catalog.manage,homologation.approve'; "
    def audited(action):
        return (f"INSERT INTO platform.platform_audit_log(actor_id,action,reason,details) VALUES('{platform_actor}','{action}','Ensaio ficticio',"
                f"'{{\"homologationId\":\"{identifier}\"}}'); ")
    query(plat + audited("HOMOLOGATION_CREATED") + "INSERT INTO platform.access_homologations(id,model_profile_id,firmware_exact,adapter_name,"
          f"adapter_version,decision_mode,topology,contract_schema_version,capabilities,limits,evidence_refs,reason,requested_by) VALUES('{identifier}',"
          f"'{profile}','{firmware}','control-id-rest','0.1.0','EDGE_ONLINE','LOCAL_GATEWAY','v1',{obs},'{{}}'::jsonb,"
          f"ARRAY['41000000-0000-4000-8000-000000000001']::uuid[],'Dossie de ensaio ficticio','{platform_actor}')", actor=platform_actor, role="cp_api")
    if not pilot:
        return
    caps = "ARRAY[" + ",".join(f"'{c}'" for c in approved) + "]::text[]"
    for decision in ("START_TRIAL", "APPROVE_LAB", "APPROVE_PILOT"):
        extra = ("ARRAY['41000000-0000-4000-8000-000000000002']::uuid[]," + caps) if decision != "START_TRIAL" else "'{}'::uuid[],'{}'::text[]"
        query(plat + audited("HOMOLOGATION_" + decision) + "INSERT INTO platform.access_homologation_decisions(homologation_id,decision,reason,"
              f"evidence_refs,approved_capabilities,decided_by) VALUES('{identifier}','{decision}','Decisao de ensaio ficticia',{extra},'{platform_actor}'); "
              f"UPDATE platform.access_homologations SET status=platform.homologation_next(status,'{decision}'),version=version+1 WHERE id='{identifier}'",
              actor=platform_actor, role="cp_api")

def facial_installation(n, approved, tenant=None):
    """Monta pelos fluxos reais (gatilhos, trilhas e RLS ligados, sem replica) uma passagem comissionada no condominio A:
    combinacao homologada para piloto pela plataforma, areas pela gestao, ponto, equipamento, canal, vinculo e aceite
    pelo MASTER de implantacao, assinatura das regras pela gestao. n distingue os identificadores (1 digito hex)."""
    tenant = tenant or TA
    u = lambda kind, i=1: f"4{n}{kind}00000-0000-4000-8000-{i:012x}"
    ids = dict(PL=f"10000000-0000-4000-8000-00000000{n}0f1", MG=f"10000000-0000-4000-8000-00000000{n}0f2", MI=f"10000000-0000-4000-8000-00000000{n}0f3",
               G=u("a"), HO=u("b"), HALL=u("c", 1), AREA=u("c", 2), PT=u("d"), KA=u("e"), DV=u("f"), CH=u("f", 2), BD=u("9"), IA=u("8"))
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{ids['PL']}','ACTIVE'),('{ids['MG']}','ACTIVE'),('{ids['MI']}','ACTIVE');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) VALUES('{tenant}','{ids['MG']}',NULL,'GESTAO FICTICIA','MANAGER','ACTIVE');
        INSERT INTO cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,reason) VALUES('{ids['G']}','{tenant}',
          '{ids['MI']}','MASTER_IMPLANTACAO',ARRAY['installation.read','installation.manage','installation.bind_channel','installation.commission'],
          now(),now()+interval '30 days','OPERATIONS','Implantacao ficticia');
        INSERT INTO cp.access_connectors(id,tenant_id,provider,label) VALUES('{ids['KA']}','{tenant}','SIMULATED','Conector ficticio');
    """)
    ids["MID"] = value(f"select id from cp.memberships where account_id='{ids['MG']}'")
    facial_homologation(ids["PL"], ids["HO"], approved, f"9.{n}.0")
    mgr = dict(tenant=tenant, actor=ids["MG"], role="cp_api")
    tec = dict(tenant=tenant, actor=ids["MI"], role="cp_api")
    area = "SET LOCAL app.capabilities='area.manage'; "
    tech = "SET LOCAL app.technical_scopes='installation.read,installation.manage,installation.bind_channel,installation.commission'; "
    query(area + f"INSERT INTO cp.access_areas(id,tenant_id,code,name,area_kind,timezone,status,created_by) VALUES"
          f"('{ids['HALL']}','{tenant}','HALL{n}','Hall ficticio','RESIDENTIAL_ROUTE','America/Sao_Paulo','ACTIVE','{ids['MG']}'),"
          f"('{ids['AREA']}','{tenant}','AREA{n}','Area ficticia','COMMON_AREA','America/Sao_Paulo','ACTIVE','{ids['MG']}')", **mgr)
    query(tech + f"INSERT INTO cp.access_points(id,tenant_id,code,name,from_area_id,to_area_id,point_kind,flow_direction,egress_class,status,created_by) "
          f"VALUES('{ids['PT']}','{tenant}','P{n}','Porta ficticia','{ids['HALL']}','{ids['AREA']}','DOOR','FROM_TO','FREE_EGRESS','MAPPED','{ids['MI']}'); "
          f"INSERT INTO cp.access_devices(id,tenant_id,connector_id,external_id,label,device_role,homologation_id,registered_by) VALUES('{ids['DV']}',"
          f"'{tenant}','{ids['KA']}','ctrl-{n}','Controlador ficticio','CONTROLLER','{ids['HO']}','{ids['MI']}'); "
          f"INSERT INTO cp.device_channels(id,tenant_id,device_id,native_namespace,external_channel,channel_kind,status,created_by) VALUES('{ids['CH']}',"
          f"'{tenant}','{ids['DV']}','relay','1','ACCESS_ACTUATOR','VERIFIED','{ids['MI']}'); "
          f"INSERT INTO cp.point_bindings(id,tenant_id,point_id,actuator_channel_id,controller_device_id,homologation_id,direction_map,wiring_evidence_ref,created_by) "
          f"VALUES('{ids['BD']}','{tenant}','{ids['PT']}','{ids['CH']}','{ids['DV']}','{ids['HO']}','{{}}','ligacao/ficticia','{ids['MI']}'); "
          f"INSERT INTO cp.installation_acceptances(id,tenant_id,plant_ref,plant_hash,topology_manifest_ref,topology_hash,capability_envelope_hash,test_suite_version,"
          f"test_report_ref,critical_tests_pending,critical_tests_failed,emergency_test_ref,rollback_plan_ref,created_by) VALUES('{ids['IA']}','{tenant}',"
          f"'planta/ficticia','{'a'*64}','topologia/ficticia','{'b'*64}','{'c'*64}','hac-1.1','relatorio/ficticio',0,0,'emergencia/ficticia','rollback/ficticio','{ids['MI']}'); "
          f"UPDATE cp.point_bindings SET installation_acceptance_id='{ids['IA']}',status='PENDING_COMMISSION',version=1 WHERE id='{ids['BD']}'; "
          f"UPDATE cp.installation_acceptances SET status='TESTING',technical_grant_id='{ids['G']}',technical_approver_account_id='{ids['MI']}',"
          f"technical_signed_at=now(),version=1 WHERE id='{ids['IA']}'", **tec)
    query(area + f"UPDATE cp.installation_acceptances SET rules_approver_membership_id='{ids['MID']}',rules_approver_account_id='{ids['MG']}',"
          f"rules_signed_at=now(),version=2 WHERE id='{ids['IA']}'", **mgr)
    query(tech + f"UPDATE cp.installation_acceptances SET status='APPROVED',version=3 WHERE id='{ids['IA']}'", **tec)
    expect(value(f"select status from cp.access_points where id='{ids['PT']}'") == "COMMISSIONED", "instalacao ficticia comissionada pelos fluxos reais")
    return ids

def facial_cleanup(ids, tenant=None):
    """Remove a instalacao ficticia sem desligar gatilhos (ordem das referencias)."""
    tenant = tenant or TA
    actors = "','".join([ids["PL"], ids["MG"], ids["MI"]])
    query(f"DELETE FROM cp.audit_log WHERE actor_id in ('{actors}'); "
          f"WITH g AS (DELETE FROM cp.access_grants WHERE tenant_id='{tenant}') DELETE FROM cp.revocation_markers WHERE tenant_id='{tenant}'; "
          f"WITH v AS (DELETE FROM cp.access_policy_validations WHERE tenant_id='{tenant}') DELETE FROM cp.access_policy_versions WHERE tenant_id='{tenant}'; "
          f"DELETE FROM cp.access_policies WHERE tenant_id='{tenant}'; DELETE FROM cp.point_bindings WHERE tenant_id='{tenant}'; "
          f"DELETE FROM cp.installation_acceptances WHERE tenant_id='{tenant}'; DELETE FROM cp.device_channels WHERE tenant_id='{tenant}'; "
          f"DELETE FROM cp.access_devices WHERE connector_id='{ids['KA']}'; DELETE FROM cp.access_connectors WHERE id='{ids['KA']}'; "
          f"DELETE FROM cp.access_points WHERE tenant_id='{tenant}' AND (from_area_id='{ids['HALL']}' OR to_area_id='{ids['AREA']}'); "
          f"DELETE FROM cp.access_areas WHERE id in ('{ids['HALL']}','{ids['AREA']}'); "
          f"DELETE FROM platform.access_homologation_decisions WHERE homologation_id='{ids['HO']}'; DELETE FROM platform.access_homologations WHERE id='{ids['HO']}'; "
          f"DELETE FROM platform.platform_audit_log WHERE actor_id in ('{actors}'); DELETE FROM cp.technical_grants WHERE account_id in ('{actors}'); "
          f"DELETE FROM cp.memberships WHERE account_id in ('{actors}'); DELETE FROM platform.accounts WHERE id in ('{actors}')")

def binding_checks():
    """V042 (AC-004, parte 2): equipamento provisionado, canais, vinculo versionado e aceite de instalacao. Casos HAC-009
    (gestor sem canal/rele), HAC-010 (MASTER limitado ao escopo), HAC-021 (controlador com varios canais), HAC-022 (saida de
    alarme nunca vira atuador), HAC-027 (mudanca fisica exige nova revisao), HAC-098 (comissionamento por condominio) e
    HAC-100 (aceite bloqueado por falha critica) e HAC-007 (isolamento)."""
    MG, MI, MS = "10000000-0000-4000-8000-0000000042a1", "10000000-0000-4000-8000-0000000042a2", "10000000-0000-4000-8000-0000000042a3"
    MC, G3 = "10000000-0000-4000-8000-0000000042a4", "42700000-0000-4000-8000-000000000003"
    KA, KB = "42000000-0000-4000-8000-0000000000a1", "42000000-0000-4000-8000-0000000000b1"
    HOK, HNO = "42000000-0000-4000-8000-0000000000c1", "42000000-0000-4000-8000-0000000000c2"
    A1, A2, P1 = "42100000-0000-4000-8000-000000000001", "42100000-0000-4000-8000-000000000002", "42200000-0000-4000-8000-000000000001"
    D1, D2, D3 = "42300000-0000-4000-8000-000000000001", "42300000-0000-4000-8000-000000000002", "42300000-0000-4000-8000-000000000003"
    CR, CA, CD, CR2 = ("42400000-0000-4000-8000-000000000001", "42400000-0000-4000-8000-000000000002",
                       "42400000-0000-4000-8000-000000000003", "42400000-0000-4000-8000-000000000004")
    B1, B2, B3 = "42500000-0000-4000-8000-000000000001", "42500000-0000-4000-8000-000000000002", "42500000-0000-4000-8000-000000000003"
    I1, I2, I3 = ("42600000-0000-4000-8000-000000000001", "42600000-0000-4000-8000-000000000002",
                  "42600000-0000-4000-8000-000000000003")
    G1, G2 = "42700000-0000-4000-8000-000000000001", "42700000-0000-4000-8000-000000000002"
    def refused(statement, **kwargs):
        # Recusa pela politica (42501) ou antes dela, porque o gatilho nao enxerga o recurso tecnico (23514).
        result = query(statement, check=False, **kwargs)
        return result.returncode != 0 and ("42501" in result.stderr or "23514" in result.stderr)
    def cleanup():
        binding_cleanup(MG, MI, MS, KA, KB, P1, A1, A2, HOK, HNO, G1, G2, D1, D2)
    profile = value("select id from platform.access_model_profiles order by catalog_code limit 1")
    names = ["doorContactEvents","eventBackfill","faceDeletion","faceEnrollment","localValidity","offlineRestriction",
             "onlineDecisionBeforeRelay","passageEvents","perDoorRights","safeRecovery","scopedRemoteOpen","secureTransport"]
    caps = "'{" + ",".join(f'"{n}":{{"documented":true,"observed":null,"approved":null}}' for n in names) + "}'::jsonb"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MG}','ACTIVE'),('{MI}','ACTIVE'),('{MS}','ACTIVE'),('{MC}','ACTIVE');
        INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) VALUES('{TA}','{MG}',NULL,'GESTAO VINCULO','MANAGER','ACTIVE');
        INSERT INTO cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,reason) VALUES
          ('{G3}','{TA}','{MC}','MASTER_IMPLANTACAO',ARRAY['installation.commission'],now(),now()+interval '30 days','OPERATIONS','So comissionamento');
        INSERT INTO cp.technical_grants(id,tenant_id,account_id,role_code,scopes,valid_from,valid_until,granted_via,reason) VALUES
          ('{G1}','{TA}','{MI}','MASTER_IMPLANTACAO',ARRAY['installation.read','installation.manage','installation.bind_channel','installation.commission'],
           now(),now()+interval '30 days','OPERATIONS','Implantacao ficticia'),
          ('{G2}','{TA}','{MS}','TECHNICAL_SUPPORT',ARRAY['installation.read'],now(),now()+interval '30 days','OPERATIONS','Suporte ficticio');
        INSERT INTO cp.access_connectors(id,tenant_id,provider,label) VALUES('{KA}','{TA}','SIMULATED','Conector A'),('{KB}','{TB}','SIMULATED','Conector B');
        INSERT INTO cp.access_areas(id,tenant_id,code,name,area_kind,timezone,created_by) VALUES
          ('{A1}','{TA}','EXT42','Exterior','EXTERIOR','America/Sao_Paulo','{MG}'),('{A2}','{TA}','HALL42','Hall','RESIDENTIAL_ROUTE','America/Sao_Paulo','{MG}');
        INSERT INTO cp.access_points(id,tenant_id,code,name,from_area_id,to_area_id,point_kind,flow_direction,egress_class,status,created_by)
          VALUES('{P1}','{TA}','P42','Portao social','{A1}','{A2}','DOOR','BOTH','FREE_EGRESS','MAPPED','{MI}');
    """)
    # Combinações pelo fluxo real da plataforma: uma aprovada para piloto, outra só documentada.
    facial_homologation(MS, HOK, FACIAL_CAPS, "4.2.0")
    facial_homologation(MS, HNO, FACIAL_CAPS, "4.2.1", pilot=False)
    mgr = "SET LOCAL app.capabilities='area.manage'; "
    tech = "SET LOCAL app.technical_scopes='installation.read,installation.manage,installation.bind_channel,installation.commission'; "
    sup = "SET LOCAL app.technical_scopes='installation.read'; "
    as_mgr, as_tech, as_sup = dict(tenant=TA, actor=MG, role="cp_api"), dict(tenant=TA, actor=MI, role="cp_api"), dict(tenant=TA, actor=MS, role="cp_api")
    def device(identifier, external, role="CONTROLLER", by=MI, connector=KA, tenant=TA):
        return (f"INSERT INTO cp.access_devices(id,tenant_id,connector_id,external_id,label,device_role,connection_mode,homologation_id,registered_by) "
                f"VALUES('{identifier}','{tenant}','{connector}','{external}','Equip {external}','{role}','LOCAL_GATEWAY','{HOK}','{by}')")
    expect(refused(mgr + device(D1, "ctrl-1", by=MG), **as_mgr),
           "HAC-009: gestor nao cadastra equipamento (nem enxerga o conector)")
    expect(refused(sup + device(D1, "ctrl-1", by=MS), **as_sup), "HAC-010: suporte so le a instalacao")
    expect(error(tech + device(D1, "ctrl-1", connector=KB), "23503", **as_tech) or error(tech + device(D1, "ctrl-1", connector=KB), "23514", **as_tech),
           "HAC-007: conector de outro condominio")
    query(tech + device(D1, "ctrl-1") + "; " + device(D2, "leitor-1", role="READER") + "; " + device(D3, "modulo-1", role="EXPANSION_MODULE"), **as_tech)
    expect(value(f"select (provisioned_instance_id is not null)::text||':'||version from cp.access_devices where id='{D1}'") == "true:0",
           "instancia provisionada pelo banco")
    expect(error(tech + f"UPDATE cp.access_devices SET external_id='outro',version=1 WHERE id='{D1}'", "42501", **as_tech), "id externo fixo")
    query(tech + f"UPDATE cp.access_devices SET connection_mode='CLOUD_SERVICE',version=1 WHERE id='{D1}'", **as_tech)
    expect(error(tech + f"UPDATE cp.access_devices SET label='x',version=5 WHERE id='{D1}'", "23514", **as_tech), "versao do equipamento sequencial")
    def channel(identifier, ext, kind, status="VERIFIED", dev=D1):
        return (f"INSERT INTO cp.device_channels(id,tenant_id,device_id,native_namespace,external_channel,channel_kind,status,created_by) "
                f"VALUES('{identifier}','{TA}','{dev}','relay','{ext}','{kind}','{status}','{MI}')")
    expect(refused(mgr + channel(CR, "1", "ACCESS_ACTUATOR").replace(f"'{MI}')", f"'{MG}')"), **as_mgr), "HAC-009: gestor nao cadastra rele")
    query(tech + channel(CR, "1", "ACCESS_ACTUATOR") + "; " + channel(CA, "2", "ALARM_OUTPUT") + "; " + channel(CD, "door-1", "DOOR_CONTACT", dev=D3)
          + "; " + channel(CR2, "3", "ACCESS_ACTUATOR"), **as_tech)
    expect(error(tech + channel("42400000-0000-4000-8000-000000000009", "1", "ACCESS_ACTUATOR"), "23505", **as_tech),
           "HAC-021: canal nativo unico por controlador")
    expect(value(mgr + "select count(*) from cp.device_channels", **as_mgr) == "0", "HAC-009: gestor nao le canal nem rele")
    expect(value(sup + "select count(*) from cp.device_channels", **as_sup) == "4", "suporte tecnico le os canais")
    def binding(identifier, actuator=CR, homologation=HOK, extra_cols="", extra_vals="", direction='{"entryReader":"FROM_TO"}', reader=D2):
        return (f"INSERT INTO cp.point_bindings(id,tenant_id,point_id,actuator_channel_id,controller_device_id,entry_reader_device_id,door_contact_channel_id,"
                f"homologation_id,direction_map,wiring_evidence_ref,created_by{extra_cols}) VALUES('{identifier}','{TA}','{P1}','{actuator}','{D1}',"
                + ("NULL" if reader is None else f"'{reader}'") + f",'{CD}','{homologation}','{direction}'::jsonb,'evid/ligacao-42','{MI}'{extra_vals})")
    expect(error(tech + binding(B1, actuator=CA), "23514", **as_tech), "HAC-022: saida de alarme nunca vira atuador")
    expect(error(tech + binding(B1, direction='{}'), "23514", **as_tech), "sentido do leitor obrigatorio no mapa")
    expect(error(tech + binding(B1, extra_cols=",status", extra_vals=",'ACTIVE'"), "42501", **as_tech), "vinculo nao nasce ativo")
    expect(refused(mgr + binding(B1).replace(f"'{MI}')", f"'{MG}')"), **as_mgr), "HAC-009: gestor nao vincula ponto")
    query(tech + binding(B1), **as_tech)
    expect(value(f"select binding_revision||':'||length(configuration_hash) from cp.point_bindings where id='{B1}'") == "1:64",
           "revisao e hash calculados pelo banco")
    expect(error(tech + f"UPDATE cp.point_bindings SET installation_acceptance_id=NULL,version=1,status='ACTIVE' WHERE id='{B1}'", "23514", **as_tech),
           "vinculo nao ativa sem aceite")
    def acceptance(identifier, pending=0, failed=0, by=MI):
        return (f"INSERT INTO cp.installation_acceptances(id,tenant_id,plant_ref,plant_hash,topology_manifest_ref,topology_hash,capability_envelope_hash,"
                f"test_suite_version,test_report_ref,critical_tests_pending,critical_tests_failed,emergency_test_ref,rollback_plan_ref,created_by) "
                f"VALUES('{identifier}','{TA}','planta/42','{'a'*64}','topologia/42','{'b'*64}','{'c'*64}','hac-1.1','relatorio/42',{pending},{failed},"
                f"'emergencia/42','rollback/42','{by}')")
    expect(error(mgr + acceptance(I1, by=MG), "42501", **as_mgr), "gestor nao abre aceite tecnico")
    query(tech + acceptance(I1, failed=1), **as_tech)
    query(tech + f"UPDATE cp.point_bindings SET installation_acceptance_id='{I1}',status='PENDING_COMMISSION',version=1 WHERE id='{B1}'", **as_tech)
    query(tech + f"UPDATE cp.installation_acceptances SET status='TESTING',version=1 WHERE id='{I1}'", **as_tech)
    expect(error(mgr + f"UPDATE cp.installation_acceptances SET critical_tests_failed=0,version=2 WHERE id='{I1}'", "42501", **as_mgr),
           "gestao nao altera resultado de teste")
    mid = value(f"select id from cp.memberships where account_id='{MG}' and tenant_id='{TA}'")
    query(tech + f"UPDATE cp.installation_acceptances SET technical_grant_id='{G1}',technical_approver_account_id='{MI}',technical_signed_at=now(),version=2 WHERE id='{I1}'", **as_tech)
    query(mgr + f"UPDATE cp.installation_acceptances SET rules_approver_membership_id='{mid}',rules_approver_account_id='{MG}',rules_signed_at=now(),version=3 WHERE id='{I1}'", **as_mgr)
    expect(error(tech + f"UPDATE cp.installation_acceptances SET status='APPROVED',version=4 WHERE id='{I1}'", "23514", **as_tech),
           "HAC-100: falha critica bloqueia o aceite")
    expect(error(tech + f"UPDATE cp.installation_acceptances SET critical_tests_failed=0,version=4 WHERE id='{I1}'", "23514", **as_tech),
           "conteudo assinado nao muda")
    query(tech + f"UPDATE cp.installation_acceptances SET status='REJECTED',status_reason='Falha critica no ensaio',version=4 WHERE id='{I1}'", **as_tech)
    expect(value(f"select status from cp.point_bindings where id='{B1}'") == "SUPERSEDED", "rejeicao descarta o vinculo pendente")
    # Revisao 2: combinacao so documentada nao comissiona; revisao 3 com combinacao piloto ativa.
    query(tech + binding(B2, homologation=HNO), **as_tech)
    query(tech + acceptance(I2), **as_tech)
    expect(value(f"select installation_revision from cp.installation_acceptances where id='{I2}'") == "2", "revisao do aceite sequencial")
    query(tech + f"UPDATE cp.point_bindings SET installation_acceptance_id='{I2}',status='PENDING_COMMISSION',version=1 WHERE id='{B2}'", **as_tech)
    query(tech + f"UPDATE cp.installation_acceptances SET status='TESTING',technical_grant_id='{G1}',technical_approver_account_id='{MI}',technical_signed_at=now(),version=1 WHERE id='{I2}'", **as_tech)
    expect(error(tech + f"UPDATE cp.installation_acceptances SET rules_approver_membership_id='{mid}',rules_approver_account_id='{MG}',rules_signed_at=now(),version=2 WHERE id='{I2}'",
                 "42501", **as_tech), "assinatura das regras so pela propria gestao")
    query(mgr + f"UPDATE cp.installation_acceptances SET rules_approver_membership_id='{mid}',rules_approver_account_id='{MG}',rules_signed_at=now(),version=2 WHERE id='{I2}'", **as_mgr)
    expect(error(mgr + f"UPDATE cp.installation_acceptances SET status='APPROVED',version=3 WHERE id='{I2}'", "42501", **as_mgr), "gestao nao aprova aceite tecnico")
    expect(error(tech + f"UPDATE cp.installation_acceptances SET status='APPROVED',version=3 WHERE id='{I2}'", "23514", **as_tech),
           "combinacao so documentada nao comissiona")
    query(tech + binding(B3), **as_tech)
    expect(value(f"select binding_revision from cp.point_bindings where id='{B3}'") == "3", "HAC-027: nova revisao do vinculo")
    expect(error(tech + f"UPDATE cp.point_bindings SET installation_acceptance_id='{I2}',status='PENDING_COMMISSION',version=1 WHERE id='{B3}'", "23514", **as_tech),
           "vinculo nao entra em aceite ja assinado")
    expect(error(tech + f"UPDATE cp.point_bindings SET status='SUPERSEDED',version=2 WHERE id='{B2}'", "23514", **as_tech),
           "vinculo de aceite assinado so sai pela rejeicao")
    query(tech + f"UPDATE cp.installation_acceptances SET status='REJECTED',status_reason='Combinacao sem aprovacao',version=3 WHERE id='{I2}'", **as_tech)
    query(tech + acceptance(I3), **as_tech)
    expect(error(mgr + f"UPDATE cp.installation_acceptances SET rules_approver_membership_id='{mid}',rules_approver_account_id='{MG}',rules_signed_at=now(),version=1 WHERE id='{I3}'",
                 "23514", **as_mgr), "regras so depois da assinatura tecnica")
    # Canal de vinculo em rascunho que muda de funcao e revalidado na submissao (Codex P1).
    CX, BX = "42400000-0000-4000-8000-000000000005", "42500000-0000-4000-8000-000000000009"
    query(tech + channel(CX, "5", "ACCESS_ACTUATOR") + "; " + binding(BX, actuator=CX), **as_tech)
    query(tech + f"UPDATE cp.device_channels SET channel_kind='ALARM_OUTPUT',version=version+1 WHERE id='{CX}'", **as_tech)
    expect(error(tech + f"UPDATE cp.point_bindings SET installation_acceptance_id='{I3}',status='PENDING_COMMISSION',version=1 WHERE id='{BX}'", "23514", **as_tech),
           "HAC-022: submissao revalida a funcao do canal")
    query(tech + f"UPDATE cp.point_bindings SET status='SUPERSEDED',version=1 WHERE id='{BX}'", **as_tech)
    query(tech + f"UPDATE cp.point_bindings SET installation_acceptance_id='{I3}',status='PENDING_COMMISSION',version=1 WHERE id='{B3}'", **as_tech)
    query(tech + f"UPDATE cp.installation_acceptances SET status='TESTING',technical_grant_id='{G1}',technical_approver_account_id='{MI}',technical_signed_at=now(),version=1 WHERE id='{I3}'", **as_tech)
    query(mgr + f"UPDATE cp.installation_acceptances SET rules_approver_membership_id='{mid}',rules_approver_account_id='{MG}',rules_signed_at=now(),version=2 WHERE id='{I3}'", **as_mgr)
    expect(error(tech + f"UPDATE cp.device_channels SET status='DISCOVERED',version=version+1 WHERE id='{CR}'", "23514", **as_tech),
           "canal em vinculo pendente segue verificado")
    expect(error(tech + f"UPDATE cp.access_devices SET status='RETIRED',version=version+1 WHERE id='{D1}'", "23514", **as_tech),
           "equipamento em vinculo pendente nao e retirado")
    expect(error(tech + f"UPDATE cp.access_points SET flow_direction='FROM_TO',updated_by='{MI}',version=version+1 WHERE id='{P1}'", "23514", **as_tech),
           "HAC-027: ponto com vinculo pendente assinado nao muda de geometria")
    expect(error(tech + f"UPDATE cp.access_devices SET status='RETIRED',version=version+1 WHERE id='{D3}'", "23514", **as_tech),
           "hospedeiro do contato de porta em vinculo pendente nao e retirado")
    expect(value(f"select length(commissioned_fingerprint) from cp.point_bindings where id='{B3}'") == "64", "impressao da configuracao fixada na submissao")
    # Aprovacao por quem so tem installation.commission: o ponto precisa ficar comissionado (Codex P2).
    query("SET LOCAL app.technical_scopes='installation.commission'; " + f"UPDATE cp.installation_acceptances SET status='APPROVED',version=3 WHERE id='{I3}'",
          tenant=TA, actor=MC, role="cp_api")
    expect(value(f"select b.status||':'||(b.valid_from is not null)::text||':'||p.status from cp.point_bindings b join cp.access_points p on p.id=b.point_id where b.id='{B3}'")
           == "ACTIVE:true:COMMISSIONED", "HAC-098: aceite aprovado ativa o vinculo e comissiona o ponto")
    expect(error(tech + f"UPDATE cp.point_bindings SET wiring_evidence_ref='outra',version=3 WHERE id='{B3}'", "42501", **as_tech), "vinculo fisico imutavel")
    expect(error(tech + f"UPDATE cp.access_devices SET connection_mode='LOCAL_GATEWAY',version=version+1 WHERE id='{D1}'", "23514", **as_tech),
           "equipamento de vinculo ativo nao muda conexao sem novo comissionamento")
    expect(error(tech + f"UPDATE cp.access_devices SET hardware_revision='r9',version=version+1 WHERE id='{D2}'", "23514", **as_tech),
           "HAC-096: leitor de vinculo ativo nao muda hardware sem novo comissionamento")
    expect(error(tech + f"UPDATE cp.access_devices SET hardware_revision='r9',version=version+1 WHERE id='{D3}'", "23514", **as_tech),
           "hospedeiro do contato de porta de vinculo ativo nao muda hardware")
    query(tech + f"UPDATE cp.access_devices SET label='Controlador renomeado',version=version+1 WHERE id='{D1}'", **as_tech)
    expect(error(tech + f"UPDATE cp.device_channels SET channel_kind='OTHER',version=version+1 WHERE id='{CR}'", "23514", **as_tech),
           "canal em vinculo ativo nao muda de funcao")
    # Mudanca fisica do ponto comissionado: volta a MAPPED e o vinculo ativo e suspenso.
    query(tech + f"UPDATE cp.access_points SET flow_direction='FROM_TO',updated_by='{MI}',version=version+1 WHERE id='{P1}'", **as_tech)
    expect(value(f"select p.status||':'||b.status||':'||(b.valid_until is not null)::text from cp.access_points p join cp.point_bindings b on b.point_id=p.id where b.id='{B3}'")
           == "MAPPED:SUSPENDED:true", "HAC-027: mudanca fisica suspende o vinculo e exige novo comissionamento")
    # Submissao concorrente com a assinatura tecnica: o mesmo lock da instalacao serializa; vinculo nao entra depois da assinatura.
    I4, B4 = "42600000-0000-4000-8000-000000000004", "42500000-0000-4000-8000-000000000004"
    query(tech + acceptance(I4) + "; " + binding(B4, actuator=CR2), **as_tech)
    def sign_and_hold():
        return query(tech + f"UPDATE cp.installation_acceptances SET technical_grant_id='{G1}',technical_approver_account_id='{MI}',"
                     f"technical_signed_at=now(),version=1 WHERE id='{I4}'; SELECT pg_sleep(1.5)", check=False, **as_tech)
    def late_submit():
        time.sleep(0.4)
        return query(tech + f"UPDATE cp.point_bindings SET installation_acceptance_id='{I4}',status='PENDING_COMMISSION',version=1 WHERE id='{B4}'",
                     check=False, **as_tech)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        signed, submitted = pool.submit(sign_and_hold), pool.submit(late_submit)
        signed, submitted = signed.result(), submitted.result()
    expect(signed.returncode == 0 and submitted.returncode != 0 and "23514" in submitted.stderr
           and value(f"select status from cp.point_bindings where id='{B4}'") == "DRAFT",
           "submissao concorrente espera a assinatura e e recusada depois dela")
    expect(value(sup + "select count(*) from cp.point_bindings", **as_sup) == "5", "suporte le os vinculos")
    expect(value(mgr + "select count(*) from cp.point_bindings", **as_mgr) == "0", "HAC-009: gestor nao le vinculo fisico")
    expect(value(mgr + "select count(*) from cp.installation_acceptances", **as_mgr) == "4", "gestao le o aceite que assina")
    expect(value(tech + "select count(*) from cp.point_bindings", tenant=TB, actor=MI, role="cp_api") == "0", "HAC-007: vinculo nao atravessa condominio")
    expect(error("select wiring_evidence_ref from cp.point_bindings", "42501", tenant=TA, role="cp_worker"), "worker nao le evidencia de instalacao")
    expect(value("select count(*) from cp.point_bindings where status='SUSPENDED'", tenant=TA, role="cp_worker") == "1", "worker le a topologia")
    expect(error(tech + f"DELETE FROM cp.point_bindings WHERE id='{B1}'", "42501", **as_tech), "vinculo nao e apagado")
    expect(error(tech + f"DELETE FROM cp.installation_acceptances WHERE id='{I1}'", "42501", **as_tech), "aceite nao e apagado")
    expect(error(mgr + f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) VALUES('{TA}','{MG}','BINDING_CREATED','POINT_BINDING','{B1}',gen_random_uuid())",
                 "42501", **as_mgr), "trilha BINDING_* so do MASTER de implantacao")
    expect(value("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='cp' and c.relname in "
                 "('device_channels','point_bindings','installation_acceptances') and c.relrowsecurity and c.relforcerowsecurity") == "3",
           "canais, vinculos e aceites com RLS FORCE")
    cleanup()

def policy_checks():
    """V043 (AC-007/AC-008): politica por area, revisoes imutaveis, validacao presa aos fatos e a instalacao, aprovacao pela
    gestao. Instalacao montada pelos fluxos reais. Casos HAC-009 (gestor sem canal/rele), HAC-043 (incompatibilidade com
    capacidade nao valida), HAC-007 (isolamento) e HAC-097 (aprovacao exige validacao vigente e fatos inalterados)."""
    ids = facial_installation(5, ["faceEnrollment","faceDeletion","perDoorRights","secureTransport","offlineRestriction","localValidity"])
    MG, MI, AR, PT = ids["MG"], ids["MI"], ids["AREA"], ids["PT"]
    PO, V1, V2, L1, L2, L3, L4 = ("43900000-0000-4000-8000-000000000001", "43a00000-0000-4000-8000-000000000001", "43a00000-0000-4000-8000-000000000002",
        "43b00000-0000-4000-8000-000000000001", "43b00000-0000-4000-8000-000000000002", "43b00000-0000-4000-8000-000000000003",
        "43b00000-0000-4000-8000-000000000004")
    PX = "43c00000-0000-4000-8000-000000000001"
    edit = "SET LOCAL app.capabilities='policy.edit'; SET LOCAL app.policy_destination_scan='on'; "
    both = "SET LOCAL app.capabilities='policy.edit,policy.publish'; SET LOCAL app.policy_destination_scan='on'; "
    area_only = "SET LOCAL app.capabilities='area.manage'; "
    tech = "SET LOCAL app.technical_scopes='installation.read,installation.manage'; "
    as_mgr = dict(tenant=TA, actor=MG, role="cp_api")
    as_tech = dict(tenant=TA, actor=MI, role="cp_api")
    doc = ('{"schemaVersion":"1.0","name":"Area","areaId":"%s","timezone":"America/Sao_Paulo","eligibleContexts":["RESIDENT"],'
           '"visitorPolicy":"DENY","minimumAgeYears":18}' % AR)
    policy = f"INSERT INTO cp.access_policies(id,tenant_id,area_id,name,created_by) VALUES('{PO}','{TA}','{AR}','Area','{MG}')"
    expect(error(area_only + policy, "42501", **as_mgr), "HAC-009: area.manage sozinho nao cria politica")
    query(edit + policy, **as_mgr)
    expect(error(edit + policy.replace(PO, "43900000-0000-4000-8000-000000000002"), "23505", **as_mgr), "uma politica por area")
    def version(identifier, document=doc):
        return (f"INSERT INTO cp.access_policy_versions(id,tenant_id,policy_id,document_schema_version,document,change_reason,authored_by) "
                f"VALUES('{identifier}','{TA}','{PO}','1.0','{document}'::jsonb,'Regra ficticia da area','{MG}')")
    expect(error(edit + version(V1, doc.replace(AR, ids["HALL"])), "23514", **as_mgr), "documento da area da politica")
    expect(error(version(V1).replace("authored_by)", "authored_by,state)").replace(f"'{MG}')", f"'{MG}','APPROVED')"), "42501", **as_mgr),
           "revisao nao nasce aprovada")
    query(edit + version(V1), **as_mgr)
    expect(value(f"select revision_number||':'||length(canonical_hash)||':'||state from cp.access_policy_versions where id='{V1}'") == "1:64:DRAFT",
           "revisao e hash pelo banco")
    expect(error(edit + f"UPDATE cp.access_policy_versions SET document='{{}}'::jsonb,version=1 WHERE id='{V1}'", "42501", **as_mgr), "documento imutavel")
    rev = value(f"select cp.installation_current_revision('{TA}')")
    def validation(identifier, ver=V1, revision=None, status="VALIDATED", issues="[]", hsh=None):
        h = hsh or value(f"select canonical_hash from cp.access_policy_versions where id='{ver}'")
        return (f"INSERT INTO cp.access_policy_validations(id,tenant_id,policy_id,policy_version_id,canonical_hash,installation_revision,status,issues,"
                f"affected_point_ids,required_capabilities,validated_by) VALUES('{identifier}','{TA}','{PO}','{ver}','{h}',{revision or rev},'{status}',"
                f"'{issues}'::jsonb,ARRAY['{PT}']::uuid[],ARRAY['perDoorRights'],'{MG}')")
    expect(error("SET LOCAL app.capabilities='policy.edit'; " + validation(L1),
                 "42501", **as_mgr), "impressao dos fatos exige a varredura de destinos")
    expect(error(edit + validation(L1, revision=int(rev) + 7), "23514", **as_mgr), "validacao presa a revisao de instalacao vigente")
    expect(error(edit + validation(L1, hsh="f" * 64), "23514", **as_mgr), "validacao presa ao hash da revisao")
    expect(error(edit + validation(L1, issues='[{"code":"CAPABILITY_MISSING","severity":"BLOCKER"}]'), "23514", **as_mgr),
           "HAC-043: impeditivo nao gera validacao aprovada")
    query(edit + validation(L1), **as_mgr)
    expect(value(f"select (expires_at-validated_at)::text||':'||length(facts_hash) from cp.access_policy_validations where id='{L1}'") == "1 day:64",
           "validade de 24 h e impressao dos fatos pelo banco")
    expect(error(edit + f"UPDATE cp.access_policy_versions SET validation_id='{L1}',state='APPROVED',version=1 WHERE id='{V1}'", "23514", **as_mgr),
           "estado segue a validacao")
    query(edit + f"UPDATE cp.access_policy_versions SET validation_id='{L1}',state='VALIDATED',version=1 WHERE id='{V1}'", **as_mgr)
    expect(error(edit + f"UPDATE cp.access_policy_versions SET state='APPROVED',version=2 WHERE id='{V1}'", "42501", **as_mgr),
           "aprovacao so com policy.publish")
    # Fatos mudam depois da validacao: passagem nova de ingresso (nao comissionada) invalida a aprovacao; ao retira-la, os fatos voltam.
    query(tech + f"INSERT INTO cp.access_points(id,tenant_id,code,name,from_area_id,to_area_id,point_kind,flow_direction,egress_class,status,created_by) "
          f"VALUES('{PX}','{TA}','PX5','Porta nova','{ids['HALL']}','{AR}','DOOR','FROM_TO','FREE_EGRESS','MAPPED','{MI}')", **as_tech)
    expect(error(both + f"UPDATE cp.access_policy_versions SET state='APPROVED',version=2 WHERE id='{V1}'", "23514", **as_mgr),
           "passagem nova nao comissionada invalida a validacao")
    query(tech + f"UPDATE cp.access_points SET status='RETIRED',updated_by='{MI}',version=1 WHERE id='{PX}'", **as_tech)
    expect(error("SET LOCAL app.capabilities='policy.edit,policy.publish'; " + f"UPDATE cp.access_policy_versions SET state='APPROVED',version=2 WHERE id='{V1}'",
                 "42501", **as_mgr), "aprovacao confere os fatos com a varredura ligada")
    query(both + f"UPDATE cp.access_policy_versions SET state='APPROVED',version=2 WHERE id='{V1}'", **as_mgr)
    expect(value(f"select approved_by||':'||(approved_at is not null)::text from cp.access_policy_versions where id='{V1}'") == f"{MG}:true",
           "aprovador e horario pelo banco")
    expect(error(edit + validation(L2), "23514", **as_mgr), "revisao aprovada nao recebe nova validacao")
    # Passagem validada que volta a MAPPED (mudanca fisica): a aprovacao exige validar de novo.
    query(edit + version(V2), **as_mgr)
    query(edit + validation(L3, ver=V2) + f"; UPDATE cp.access_policy_versions SET validation_id='{L3}',state='VALIDATED',version=1 WHERE id='{V2}'", **as_mgr)
    query(tech + f"UPDATE cp.access_points SET flow_direction='BOTH',updated_by='{MI}',version=version+1 WHERE id='{PT}'", **as_tech)
    expect(value(f"select status from cp.access_points where id='{PT}'") == "MAPPED", "mudanca fisica devolve a passagem a MAPPED")
    expect(error(both + f"UPDATE cp.access_policy_versions SET state='APPROVED',version=2 WHERE id='{V2}'", "23514", **as_mgr),
           "passagem que volta a MAPPED invalida a validacao")
    expect(error(edit + f"UPDATE cp.access_policy_versions SET state='REJECTED',version=2 WHERE id='{V2}'", "23514", **as_mgr), "rejeicao exige motivo")
    query(edit + f"UPDATE cp.access_policy_versions SET state='REJECTED',rejected_reason='Instalacao mudou',version=2 WHERE id='{V2}'", **as_mgr)
    # Destinos: gestao le vinculo so com a varredura ligada e com policy.edit.
    expect(value("SET LOCAL app.capabilities='policy.edit'; select count(*) from cp.point_bindings", **as_mgr) == "0",
           "HAC-009: gestao nao le vinculo fisico fora da varredura")
    expect(value(edit + "select count(*) from cp.point_bindings", **as_mgr) == "1", "varredura de destinos le os vinculos")
    expect(value(area_only + "SET LOCAL app.policy_destination_scan='on'; select count(*) from cp.point_bindings", **as_mgr) == "0",
           "varredura exige capacidade de politica")
    expect(value(edit + "select count(*) from cp.access_policies", tenant=TB, actor=MG, role="cp_api") == "0", "HAC-007: politica nao atravessa condominio")
    expect(value("SET LOCAL app.technical_scopes='installation.read'; select count(*) from cp.access_policy_versions", **as_tech) == "0",
           "MASTER de implantacao nao le regras da gestao")
    expect(error("select change_reason from cp.access_policy_versions", "42501", tenant=TA, role="cp_worker"), "worker nao le motivo")
    expect(value("select count(*) from cp.access_policy_versions where state='APPROVED'", tenant=TA, role="cp_worker") == "1", "worker le o documento aprovado")
    expect(error(edit + f"DELETE FROM cp.access_policy_versions WHERE id='{V2}'", "42501", **as_mgr), "revisao nao e apagada")
    expect(error(edit + f"UPDATE cp.access_policies SET status='ACTIVE',updated_by='{MG}',version=1 WHERE id='{PO}'", "23514", **as_mgr),
           "politica so ativa pela publicacao")
    expect(error(tech + f"INSERT INTO cp.audit_log(tenant_id,actor_id,action,resource_type,resource_id,trace_id) "
                 f"VALUES('{TA}','{MI}','POLICY_CREATED','ACCESS_POLICY','{PO}',gen_random_uuid())", "42501", **as_tech),
           "trilha POLICY_* so da gestao")
    expect(value("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='cp' and c.relname in "
                 "('access_policies','access_policy_versions','access_policy_validations') and c.relrowsecurity and c.relforcerowsecurity") == "3",
           "politicas com RLS FORCE")
    facial_cleanup(ids)

def grant_checks():
    """V044 (AC-009/AC-015): direitos derivados por sujeito, contexto e area; marcas de revogacao que vencem derivacao antiga
    e alcancam cartao/QR. Instalacao, politica, sujeito e contexto pelos fluxos reais (gatilhos e RLS ligados)."""
    caps = ["faceEnrollment","faceDeletion","perDoorRights","secureTransport","offlineRestriction","localValidity"]
    ids = facial_installation(6, caps)
    MG, AR = ids["MG"], ids["AREA"]
    RS = "10000000-0000-4000-8000-0000000006a1"
    MRS, SRS, CRS, CRF = ("44100000-0000-4000-8000-000000000001", "44200000-0000-4000-8000-000000000001",
                          "44300000-0000-4000-8000-000000000001", "44300000-0000-4000-8000-000000000002")
    PO, V1, L1 = "44400000-0000-4000-8000-000000000001", "44500000-0000-4000-8000-000000000001", "44600000-0000-4000-8000-000000000001"
    G1, G2, G3, G4 = (f"44700000-0000-4000-8000-00000000000{i}" for i in range(1, 5))
    CR = "44800000-0000-4000-8000-000000000001"
    both = "SET LOCAL app.capabilities='memberships.manage,policy.edit,policy.publish'; SET LOCAL app.policy_destination_scan='on'; "
    mm = "SET LOCAL app.capabilities='memberships.manage'; "
    as_mgr = dict(tenant=TA, actor=MG, role="cp_api")
    query(f"INSERT INTO platform.accounts(id,status) VALUES('{RS}','ACTIVE'); INSERT INTO cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) "
          f"VALUES('{MRS}','{TA}','{RS}','{UA}','MORADOR DIREITO','RESIDENT','ACTIVE')")
    doc = ('{"schemaVersion":"1.0","name":"Area","areaId":"%s","timezone":"America/Sao_Paulo","eligibleContexts":["RESIDENT"],'
           '"visitorPolicy":"DENY","reservation":{"mode":"NOT_REQUIRED","entryGraceBeforeSeconds":0,"entryGraceAfterSeconds":0}}' % AR)
    query(both + f"INSERT INTO cp.access_policies(id,tenant_id,area_id,name,created_by) VALUES('{PO}','{TA}','{AR}','Area','{MG}'); "
          f"INSERT INTO cp.access_policy_versions(id,tenant_id,policy_id,document_schema_version,document,change_reason,authored_by) "
          f"VALUES('{V1}','{TA}','{PO}','1.0','{doc}'::jsonb,'Regra ficticia de direitos','{MG}')", **as_mgr)
    h = value(f"select canonical_hash from cp.access_policy_versions where id='{V1}'")
    rev = value(f"select cp.installation_current_revision('{TA}')")
    query(both + f"INSERT INTO cp.access_policy_validations(id,tenant_id,policy_id,policy_version_id,canonical_hash,installation_revision,status,issues,"
          f"affected_point_ids,required_capabilities,validated_by) VALUES('{L1}','{TA}','{PO}','{V1}','{h}',{rev},'VALIDATED','[]'::jsonb,"
          f"ARRAY['{ids['PT']}']::uuid[],ARRAY['perDoorRights'],'{MG}'); "
          f"UPDATE cp.access_policy_versions SET validation_id='{L1}',state='VALIDATED',version=1 WHERE id='{V1}'; "
          f"UPDATE cp.access_policy_versions SET state='APPROVED',version=2 WHERE id='{V1}'", **as_mgr)
    query(mm + f"INSERT INTO cp.access_subjects(id,tenant_id,origin_kind,account_id) VALUES('{SRS}','{TA}','ACCOUNT','{RS}'); "
          f"INSERT INTO cp.subject_contexts(id,tenant_id,subject_id,context_kind,source_kind,membership_id,created_by) "
          f"VALUES('{CRS}','{TA}','{SRS}','RESIDENT','MEMBERSHIP','{MRS}','{MG}')", **as_mgr)
    def grant(identifier, epoch=0, until="now()+interval '20 days'", context=CRS, kind="MEMBERSHIP", origin=MRS, version=None):
        ov = version if version is not None else value(f"select version from cp.memberships where id='{MRS}'")
        col = "origin_membership_id" if kind == "MEMBERSHIP" else "origin_functional_context_id"
        return (f"INSERT INTO cp.access_grants(id,tenant_id,subject_id,context_id,area_id,policy_id,policy_version_id,origin_kind,{col},origin_version,"
                f"valid_from,valid_until,subject_restriction_epoch,reason_codes,derived_by) VALUES('{identifier}','{TA}','{SRS}','{context}','{AR}','{PO}',"
                f"'{V1}','{kind}','{origin}',{ov},now(),{until},{epoch},ARRAY['ELIGIBLE_CONTEXT'],'{MG}')")
    expect(error(mm + grant(G1), "42501", **as_mgr), "derivar exige vinculos e publicacao")
    expect(error(both + grant(G1, until="now()+interval '40 days'"), "23514", **as_mgr), "vigencia nunca alem do horizonte de 30 dias")
    expect(error(both + grant(G1, version=99), "23514", **as_mgr), "origem em outra versao nao deriva")
    query(both + grant(G1), **as_mgr)
    expect(value(f"select (generation>0)::text||':'||length(derivation_key)||':'||origin_key from cp.access_grants where id='{G1}'") == f"true:64:MEMBERSHIP:{MRS}",
           "geracao, chave e origem pelo banco")
    expect(value(both + f"select cp.access_grant_current('{TA}','{G1}',now())", **as_mgr) == "t", "direito derivado vigente")
    expect(error(both + grant(G2), "23505", **as_mgr), "um direito ativo por contexto, area e origem")
    expect(error(both + grant(G2, epoch=5), "23514", **as_mgr), "epoca do sujeito diferente nao deriva")
    # Contexto funcional nao elegivel na regra (so RESIDENT).
    query(mm + f"INSERT INTO cp.subject_contexts(id,tenant_id,subject_id,context_kind,source_kind,functional_authorization_ref,approved_by_account_id,"
          f"valid_until,created_by) VALUES('{CRF}','{TA}','{SRS}','EMPLOYEE','FUNCTIONAL_AUTHORIZATION','autorizacao-ficticia-1','{MG}',"
          f"now()+interval '10 days','{MG}')", **as_mgr)
    expect(error(both + grant(G2, context=CRF, kind="FUNCTIONAL_AUTHORIZATION", origin=CRF, version=0), "23514", **as_mgr), "contexto nao elegivel nao deriva")
    # Cartao do morador; bloqueio do sujeito: marca, direito revogado e cartao revogado na mesma transacao.
    query(f"INSERT INTO cp.access_credentials(id,tenant_id,connector_id,membership_id,unit_id,kind,label,created_by) "
          f"VALUES('{CR}','{TA}','{ids['KA']}','{MRS}','{UA}','CARD','Cartao direito','{MG}')")
    query(mm + f"UPDATE cp.access_subjects SET status='BLOCKED',blocked_reason='Ordem do sindico',blocked_by='{MG}',restriction_epoch=1,version=1 WHERE id='{SRS}'", **as_mgr)
    expect(value(f"select target_kind||':'||reason_code||':'||subject_restriction_epoch||':'||array_to_string(covered_methods,',') "
                 f"from cp.revocation_markers where subject_id='{SRS}'") == "SUBJECT:SUBJECT_BLOCKED:1:FACE,CARD,PIN,QR,INTERMEDIARY_GRANT",
           "AC-015: bloqueio grava marca que cobre face, cartao, PIN, QR e intermediario")
    expect(value(f"select status||':'||status_reason_code from cp.access_grants where id='{G1}'") == "REVOKED:SUBJECT_BLOCKED", "bloqueio revoga o direito")
    expect(value(f"select desired_state||':'||desired_version from cp.access_credentials where id='{CR}'") == "REVOKED:2"
           and value(f"select count(*) from cp.outbox where aggregate_id='{CR}' and event_type='CREDENTIAL_REVOKED'") == "1",
           "bloqueio revoga o cartao com evento prioritario")
    expect(error(mm + f"INSERT INTO cp.access_credentials(id,tenant_id,connector_id,membership_id,unit_id,kind,label,created_by) "
                 f"VALUES('44800000-0000-4000-8000-000000000002','{TA}','{ids['KA']}','{MRS}','{UA}','QR','QR bloqueado','{MG}')", "23514", **as_mgr),
           "sujeito restrito nao recebe credencial")
    expect(error(both + grant(G2, epoch=0), "23514", **as_mgr), "derivacao antiga (epoca anterior) nao vence a revogacao")
    query(mm + f"UPDATE cp.access_subjects SET status='ACTIVE',blocked_reason=NULL,blocked_by=NULL,restriction_epoch=2,version=2 WHERE id='{SRS}'", **as_mgr)
    query(both + grant(G2, epoch=2), **as_mgr)
    expect(value(f"select (g2.generation>m.revoked_through_generation)::text from cp.access_grants g2, cp.revocation_markers m "
                 f"where g2.id='{G2}' and m.subject_id='{SRS}' and m.target_kind='SUBJECT'") == "true",
           "reativacao legitima nasce em geracao nova, acima da marca")
    expect(error(both + f"UPDATE cp.revocation_markers SET reason_code='MANUAL_REVOCATION' WHERE subject_id='{SRS}'", "42501", **as_mgr), "marca imutavel para a API")
    expect(error(f"UPDATE cp.revocation_markers SET reason_code='MANUAL_REVOCATION' WHERE subject_id='{SRS}'", "23514"), "marca imutavel ate para o dono")
    expect(error(both + f"DELETE FROM cp.revocation_markers WHERE subject_id='{SRS}'", "42501", **as_mgr), "marca nao e apagada")
    expect(error(both + f"INSERT INTO cp.revocation_markers(tenant_id,target_kind,subject_id,grant_id,reason_code) VALUES('{TA}','GRANT','{SRS}','{G2}','ORIGIN_CHANGED')",
                 "23514", **as_mgr), "varredura nao marca direito ainda vigente")
    expect(error(f"INSERT INTO cp.revocation_markers(tenant_id,target_kind,subject_id,grant_id,reason_code) VALUES('{TA}','GRANT','{SRS}','{G2}','MANUAL_REVOCATION')",
                 "42501", tenant=TA, actor=RS, role="cp_api"), "morador nao revoga direito")
    expect(error(both + f"UPDATE cp.access_grants SET status='EXPIRED',status_reason_code='WINDOW_ENDED',version=1 WHERE id='{G2}'", "23514", **as_mgr),
           "direito vigente nao expira")
    # Contexto revogado: marca CONTEXT, direito revogado e contexto nunca mais recebe direito.
    query(mm + f"UPDATE cp.subject_contexts SET status='REVOKED',status_reason='Ordem da gestao',version=1 WHERE id='{CRS}'", **as_mgr)
    expect(value(f"select status||':'||status_reason_code from cp.access_grants where id='{G2}'") == "REVOKED:CONTEXT_REVOKED", "contexto revogado revoga o direito")
    expect(value(f"select count(*)::text||':'||bool_and(previous_entry_hash is not null or journal_sequence=1)::text||':'||max(journal_sequence) "
                 f"from cp.revocation_markers where tenant_id='{TA}' and subject_id='{SRS}'") == "2:true:"
           + value(f"select max(journal_sequence) from cp.revocation_markers where tenant_id='{TA}'"), "diario encadeado e continuo por condominio")
    expect(value(f"select count(*) from cp.revocation_markers a join cp.revocation_markers b on b.tenant_id=a.tenant_id "
                 f"and b.journal_sequence=a.journal_sequence+1 where a.tenant_id='{TA}' and b.previous_entry_hash is distinct from a.entry_hash") == "0",
           "cada entrada cita o hash da anterior")
    # Visitante (AC-009): direito do convite nunca alem dele; convite revogado derruba o direito na hora e a varredura grava a marca.
    V2, L2 = "44500000-0000-4000-8000-000000000002", "44600000-0000-4000-8000-000000000002"
    VV, PV, SV, CV = ("44900000-0000-4000-8000-000000000001", "44900000-0000-4000-8000-000000000002", "44900000-0000-4000-8000-000000000003",
                      "44900000-0000-4000-8000-000000000004")
    doc2 = doc.replace('"eligibleContexts":["RESIDENT"],"visitorPolicy":"DENY"', '"eligibleContexts":["RESIDENT","VISITOR"],"visitorPolicy":"ALLOW_WITH_VALID_PERMIT"')
    query(both + f"INSERT INTO cp.access_policy_versions(id,tenant_id,policy_id,document_schema_version,document,change_reason,authored_by) "
          f"VALUES('{V2}','{TA}','{PO}','1.0','{doc2}'::jsonb,'Regra ficticia com visitante','{MG}')", **as_mgr)
    h2 = value(f"select canonical_hash from cp.access_policy_versions where id='{V2}'")
    query(both + f"INSERT INTO cp.access_policy_validations(id,tenant_id,policy_id,policy_version_id,canonical_hash,installation_revision,status,issues,"
          f"affected_point_ids,required_capabilities,validated_by) VALUES('{L2}','{TA}','{PO}','{V2}','{h2}',{rev},'VALIDATED','[]'::jsonb,"
          f"ARRAY['{ids['PT']}']::uuid[],ARRAY['perDoorRights'],'{MG}'); "
          f"UPDATE cp.access_policy_versions SET validation_id='{L2}',state='VALIDATED',version=1 WHERE id='{V2}'; "
          f"UPDATE cp.access_policy_versions SET state='APPROVED',version=2 WHERE id='{V2}'", **as_mgr)
    query(f"INSERT INTO cp.visitors(id,tenant_id,full_name,created_by) VALUES('{VV}','{TA}','CONVIDADO FICTICIO','{RS}'); "
          f"INSERT INTO cp.visit_permits(id,tenant_id,visitor_id,unit_id,created_by,valid_from,valid_until,reservation_id) "
          f"VALUES('{PV}','{TA}','{VV}','{UA}','{RS}',now(),now()+interval '2 days',NULL); "
          f"INSERT INTO cp.access_subjects(id,tenant_id,origin_kind,visitor_id) VALUES('{SV}','{TA}','VISITOR','{VV}'); "
          f"INSERT INTO cp.subject_contexts(id,tenant_id,subject_id,context_kind,source_kind,permit_id,valid_until,created_by) "
          f"VALUES('{CV}','{TA}','{SV}','VISITOR','VISIT_PERMIT','{PV}',now()+interval '2 days','{MG}')")
    guest = (f"INSERT INTO cp.access_grants(id,tenant_id,subject_id,context_id,area_id,policy_id,policy_version_id,origin_kind,origin_permit_id,origin_version,"
             f"valid_from,valid_until,subject_restriction_epoch,reason_codes,derived_by) VALUES('{G3}','{TA}','{SV}','{CV}','{AR}','{PO}','{V2}','VISIT_PERMIT',"
             f"'{PV}',0,now()+interval '1 hour',now()+interval '1 day',0,ARRAY['VISIT_PERMIT'],'{MG}')")
    scan = "SET LOCAL app.subject_context_scan='on'; "
    expect(error(both + scan + guest.replace("'1 day'", "'3 days'"), "23514", **as_mgr), "direito do convidado nunca alem do convite")
    query(both + scan + guest, **as_mgr)
    later = "now()+interval '2 hours'"
    expect(value(both + scan + f"select cp.access_grant_current('{TA}','{G3}',{later})", **as_mgr) == "t", "visitante com convite vigente tem direito")
    expect(value(both + f"select cp.access_grant_current('{TA}','{G3}',{later})", **as_mgr) == "f", "sem a conferencia ligada a gestao nao le o convite: nega")
    expect(error(both + scan + f"INSERT INTO cp.revocation_markers(tenant_id,target_kind,subject_id,grant_id,reason_code) "
                 f"VALUES('{TA}','GRANT','{SV}','{G3}','ORIGIN_CHANGED')", "23514", **as_mgr), "direito ainda por comecar nao e origem invalida")
    expect(error(both + scan + f"UPDATE cp.access_grants SET status='EXPIRED',status_reason_code='WINDOW_ENDED',version=1 WHERE id='{G3}'", "23514", **as_mgr),
           "direito ainda por comecar nao expira")
    query(f"UPDATE cp.visit_permits SET state='REVOKED',revoked_by='{RS}',revoked_at=now(),revoke_reason='Cancelado',version=version+1 WHERE id='{PV}'")
    expect(value(both + scan + f"select cp.access_grant_current('{TA}','{G3}',{later})", **as_mgr) == "f",
           "AC-009: convite cancelado derruba na hora o direito do visitante")
    query(both + scan + f"INSERT INTO cp.revocation_markers(tenant_id,target_kind,subject_id,grant_id,reason_code) VALUES('{TA}','GRANT','{SV}','{G3}','ORIGIN_CHANGED')", **as_mgr)
    expect(value(f"select status||':'||status_reason_code from cp.access_grants where id='{G3}'") == "REVOKED:ORIGIN_CHANGED",
           "varredura grava a marca do direito que deixou de valer")
    expect(error(both + scan + guest.replace(G3, G4), "23514", **as_mgr), "convite cancelado nao gera direito novo")
    # Corrida cartao x bloqueio (mesmo lock por condominio): nenhuma ordem deixa cartao ativo para sujeito bloqueado.
    def card(identifier):
        return (mm + f"INSERT INTO cp.access_credentials(id,tenant_id,connector_id,membership_id,unit_id,kind,label,created_by) "
                f"VALUES('{identifier}','{TA}','{ids['KA']}','{MRS}','{UA}','CARD','Cartao corrida','{MG}')")
    def block(epoch, version):
        return (mm + f"UPDATE cp.access_subjects SET status='BLOCKED',blocked_reason='Ordem do sindico',blocked_by='{MG}',restriction_epoch={epoch},"
                f"version={version} WHERE id='{SRS}'")
    def unblock(epoch, version):
        query(mm + f"UPDATE cp.access_subjects SET status='ACTIVE',blocked_reason=NULL,blocked_by=NULL,restriction_epoch={epoch},version={version} "
              f"WHERE id='{SRS}'", **as_mgr)
    def race(first, second):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            a = pool.submit(query, first + "; SELECT pg_sleep(1.5)", check=False, **as_mgr)
            time.sleep(0.5)
            b = pool.submit(query, second, check=False, **as_mgr)
            return a.result(), b.result()
    K1, K2 = "44800000-0000-4000-8000-000000000011", "44800000-0000-4000-8000-000000000012"
    blocked_first, card_late = race(block(3, 3), card(K1))
    expect(blocked_first.returncode == 0 and card_late.returncode != 0 and "23514" in card_late.stderr
           and value(f"select count(*) from cp.access_credentials where id='{K1}'") == "0", "corrida: bloqueio primeiro recusa o cartao concorrente")
    unblock(4, 4)
    card_first, blocked_late = race(card(K2), block(5, 5))
    expect(card_first.returncode == 0 and blocked_late.returncode == 0
           and value(f"select desired_state from cp.access_credentials where id='{K2}'") == "REVOKED", "corrida: cartao primeiro e revogado pela cascata do bloqueio")
    unblock(6, 6)
    query(f"DELETE FROM cp.outbox WHERE aggregate_id='{K2}'; DELETE FROM cp.access_credentials WHERE id='{K2}'")
    expect(value(both + f"select count(*) from cp.access_grants", tenant=TB, actor=MG, role="cp_api") == "0", "HAC-007: direitos nao atravessam condominio")
    expect(value(f"select count(*) from cp.access_grants where id='{G1}'", tenant=TA, actor=RS, role="cp_api") == "0", "morador nao le direitos da gestao")
    expect(error("select derived_by from cp.access_grants", "42501", tenant=TA, role="cp_worker"), "worker nao le autoria do direito")
    expect(value("select count(*) from cp.revocation_markers", tenant=TA, role="cp_worker") != "0", "worker le as marcas")
    expect(value("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='cp' and c.relname in "
                 "('access_grants','revocation_markers') and c.relrowsecurity and c.relforcerowsecurity") == "2", "direitos e marcas com RLS FORCE")
    query(f"DELETE FROM cp.outbox WHERE aggregate_id='{CR}'; DELETE FROM cp.credential_syncs WHERE credential_id='{CR}'; DELETE FROM cp.access_credentials WHERE id='{CR}'")
    query(f"WITH g AS (DELETE FROM cp.access_grants WHERE tenant_id='{TA}') DELETE FROM cp.revocation_markers WHERE tenant_id='{TA}'; "
          f"DELETE FROM cp.subject_contexts WHERE subject_id in ('{SRS}','{SV}'); DELETE FROM cp.access_subjects WHERE id in ('{SRS}','{SV}'); "
          f"DELETE FROM cp.visit_permits WHERE id='{PV}'; DELETE FROM cp.visitors WHERE id='{VV}'; "
          f"DELETE FROM cp.memberships WHERE id='{MRS}'; DELETE FROM platform.accounts WHERE id='{RS}'")
    facial_cleanup(ids)

def binding_cleanup(MG, MI, MS, KA, KB, P1, A1, A2, HOK, HNO, G1, G2, D1, D2):
    query(f"DELETE FROM cp.audit_log WHERE actor_id in ('{MG}','{MI}','{MS}'); "
          f"DELETE FROM cp.point_bindings WHERE tenant_id in ('{TA}','{TB}'); DELETE FROM cp.installation_acceptances WHERE tenant_id in ('{TA}','{TB}'); "
          f"DELETE FROM cp.device_channels WHERE tenant_id in ('{TA}','{TB}'); DELETE FROM cp.access_devices WHERE connector_id in ('{KA}','{KB}'); "
          f"DELETE FROM cp.access_connectors WHERE id in ('{KA}','{KB}'); DELETE FROM cp.access_points WHERE id='{P1}'; "
          f"DELETE FROM cp.access_areas WHERE id in ('{A1}','{A2}'); "
          f"DELETE FROM platform.access_homologation_decisions WHERE homologation_id in ('{HOK}','{HNO}'); "
          f"DELETE FROM platform.access_homologations WHERE id in ('{HOK}','{HNO}'); DELETE FROM platform.platform_audit_log WHERE actor_id='{MS}'; "
          f"DELETE FROM cp.technical_grants WHERE id in ('{G1}','{G2}','42700000-0000-4000-8000-000000000003'); "
          f"DELETE FROM cp.memberships WHERE account_id in ('{MG}','{MI}','{MS}'); "
          f"DELETE FROM platform.accounts WHERE id in ('{MG}','{MI}','{MS}','10000000-0000-4000-8000-0000000042a4')")

def maintenance_checks():
    """V046: zelador (MAINTENANCE) com maintenance.operate le e registra andamento so dos pedidos atribuidos ao proprio
    vinculo; nao muda responsavel nem prazo, nao encerra e nao le cadastro."""
    MG, ZL, Z2, RS = ("10000000-0000-4000-8000-0000000046a1", "10000000-0000-4000-8000-0000000046a2",
                      "10000000-0000-4000-8000-0000000046a3", "10000000-0000-4000-8000-0000000046a4")
    MZ, MZ2 = "46000000-0000-4000-8000-000000000001", "46000000-0000-4000-8000-000000000002"
    R1, R2 = "46000000-0000-4000-8000-000000000011", "46000000-0000-4000-8000-000000000012"
    query(f"""
        INSERT INTO platform.accounts(id,status) VALUES('{MG}','ACTIVE'),('{ZL}','ACTIVE'),('{Z2}','ACTIVE'),('{RS}','ACTIVE');
        INSERT INTO cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status) VALUES
          (gen_random_uuid(),'{TA}','{MG}',NULL,'GESTAO ZELADORIA','MANAGER','ACTIVE'),
          ('{MZ}','{TA}','{ZL}',NULL,'ZELADOR FICTICIO','MAINTENANCE','ACTIVE'),
          ('{MZ2}','{TA}','{Z2}',NULL,'OUTRO ZELADOR','MAINTENANCE','ACTIVE'),
          (gen_random_uuid(),'{TA}','{RS}','{UA}','RESPONSAVEL ZELADORIA','RESPONSIBLE','ACTIVE');
    """)
    expect(error(f"INSERT INTO cp.memberships(tenant_id,account_id,unit_id,display_name,role,status) "
                 f"VALUES('{TA}','{Z2}','{UA}','ZELADOR COM UNIDADE','MAINTENANCE','PENDING')", "23514"), "zelador nunca tem unidade")
    for rid, title in ((R1, "Lampada do hall"), (R2, "Portao da garagem")):
        query(f"INSERT INTO cp.service_requests(id,tenant_id,unit_id,requester_id,category,title,description) "
              f"VALUES('{rid}','{TA}','{UA}','{RS}','MAINTENANCE','{title}','Descricao ficticia')", tenant=TA, actor=RS, role="cp_api")
    mgr = "SET LOCAL app.capabilities='requests.manage'; "
    query(mgr + f"UPDATE cp.service_requests SET state='TRIAGED',assignee_membership_id='{MZ}',version=version+1 WHERE id='{R1}'",
          tenant=TA, actor=MG, role="cp_api")
    zl = "SET LOCAL app.capabilities='maintenance.operate'; "
    expect(value(zl + "select count(*) from cp.service_requests", tenant=TA, actor=ZL, role="cp_api") == "1",
           "zelador ve somente o pedido atribuido")
    expect(value("select count(*) from cp.service_requests", tenant=TA, actor=ZL, role="cp_api") == "0",
           "sem capacidade publicada (AAL1) o zelador nao ve pedidos")
    expect(value(zl + "select count(*) from cp.service_requests", tenant=TA, actor=Z2, role="cp_api") == "0",
           "outro zelador nao ve pedido alheio")
    expect(value(zl + "select count(*) from cp.memberships", tenant=TA, actor=ZL, role="cp_api") == "1",
           "zelador nao le cadastro de vinculos")
    expect(error(zl + f"UPDATE cp.service_requests SET state='IN_PROGRESS',assignee_membership_id=NULL,version=version+1 WHERE id='{R1}'",
                 "42501", tenant=TA, actor=ZL, role="cp_api"), "zelador nao muda responsavel")
    expect(error(zl + f"UPDATE cp.service_requests SET state='IN_PROGRESS',due_on=current_date,version=version+1 WHERE id='{R1}'",
                 "42501", tenant=TA, actor=ZL, role="cp_api"), "zelador nao muda prazo")
    expect(error(zl + f"UPDATE cp.service_requests SET state='CLOSED',version=version+1 WHERE id='{R1}'",
                 "42501", tenant=TA, actor=ZL, role="cp_api"), "zelador nao encerra")
    expect(query(zl + f"UPDATE cp.service_requests SET state='IN_PROGRESS',version=version+1 WHERE id='{R2}' RETURNING id",
                 tenant=TA, actor=ZL, role="cp_api").stdout.strip() == "", "zelador nao alcanca pedido nao atribuido")
    query(zl + f"UPDATE cp.service_requests SET state='IN_PROGRESS',version=version+1 WHERE id='{R1}'", tenant=TA, actor=ZL, role="cp_api")
    expect(value(f"select state||':'||version from cp.service_requests where id='{R1}'") == "IN_PROGRESS:2", "zelador registra o andamento")
    query(zl + f"INSERT INTO cp.request_messages(tenant_id,request_id,author_id,author_role,body,visibility) "
          f"VALUES('{TA}','{R1}','{ZL}','STAFF','Troca agendada','RESIDENT')", tenant=TA, actor=ZL, role="cp_api")
    expect(value(f"select author_name from cp.request_messages where request_id='{R1}'") == "ZELADOR FICTICIO", "nome do zelador na conversa")
    expect(error(zl + f"INSERT INTO cp.request_messages(tenant_id,request_id,author_id,author_role,body,visibility) "
                 f"VALUES('{TA}','{R2}','{ZL}','STAFF','Fora','RESIDENT')", "42501", tenant=TA, actor=ZL, role="cp_api"),
           "zelador nao escreve em pedido nao atribuido")
    expect(value(zl + "select count(*) from cp.request_messages", tenant=TA, actor=ZL, role="cp_api") == "1", "zelador le a conversa do atribuido")
    query(f"DELETE FROM cp.request_messages WHERE request_id in ('{R1}','{R2}'); DELETE FROM cp.service_requests WHERE id in ('{R1}','{R2}'); "
          f"DELETE FROM cp.memberships WHERE account_id in ('{MG}','{ZL}','{Z2}','{RS}'); "
          f"DELETE FROM platform.accounts WHERE id in ('{MG}','{ZL}','{Z2}','{RS}')")

def main():
    guard()
    if os.environ.get("CP_SCHEMA_ALREADY_MIGRATED") != "true":
        for migration in sorted((ROOT / "db/migrations").glob("V*.sql")):
            subprocess.run(["psql", "-X", "-v", "ON_ERROR_STOP=1", "-f", str(migration)], check=True)
    else:
        expected = len(list((ROOT / "db/migrations").glob("V*.sql")))
        expect(value("select count(*) from platform.flyway_schema_history where success and version is not null") == str(expected),
               f"Flyway executou as {expected} migrations versionadas")
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
        INSERT INTO cp.visitors(id,tenant_id,full_name,created_by) VALUES('{VA}','{TA}','VISITANTE FICTICIO','{AA}');
        INSERT INTO cp.arrival_requests(id,tenant_id,unit_id,visitor_id,created_by)
          VALUES('{ARR}','{TA}','{UA}','{VA}','{AA}');
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
    # The pool context has joined every process; rechecks cannot overlap the wave.
    reconciled = reconcile_reservation_race(results, book)
    expect(all(r.returncode == 0 or reservation_error_state(r) == "23P01" for r in reconciled),
           "perdedoras confirmam conflito de exclusao apos rechecagem somente de deadlocks abortados")
    expect(value(f"select count(*) from cp.reservations where tenant_id='{TA}' and amenity_id='{RA}' "
                 "and tstzrange(occupied_from,occupied_until,'[)') && "
                 "tstzrange('2030-01-01 12:00+00'::timestamptz,'2030-01-01 13:00+00'::timestamptz,'[)')",
                 tenant=TA, role="cp_api") == "1",
           "banco mantem exatamente uma reserva sobreposta apos todas as transacoes e rechecagens")
    expect(bool(query(reservation(start="13:00",end="14:00"),tenant=TA,role="cp_api").stdout.strip()),
           "intervalos adjacentes sem buffer sao permitidos")
    query(reservation(start="14:00",end="15:00",occupied_end="15:30"),tenant=TA,role="cp_api")
    expect(error(reservation(start="15:00",end="16:00"),"23P01",tenant=TA,role="cp_api"),
           "buffer de limpeza participa da exclusao")
    expect(bool(query(reservation(tenant=TB,area=RB,unit=UB,actor=AB),tenant=TB,actor=AB,role="cp_api").stdout.strip()),
           "mesmo horario em outro tenant permanece independente")
    query(f"update cp.reservations set status='CANCELLED',version=version+1 where starts_at='2030-01-01 12:00+00'",tenant=TA,role="cp_api")
    expect(bool(query(reservation(),tenant=TA,role="cp_api").stdout.strip()),"cancelamento libera intervalo")

    member_a = f"(SELECT m.id FROM cp.memberships m WHERE m.tenant_id='{TA}' AND m.account_id='{AA}' AND m.role='RESIDENT')"
    pickup = f"""
        SET LOCAL app.capabilities='concierge.operate';
        WITH claimed AS(
          UPDATE cp.parcels SET status='DELIVERED',version=version+1
          WHERE id='{PA}' AND status='RECEIVED' RETURNING id
        )
        INSERT INTO cp.parcel_handoffs(tenant_id,parcel_id,operator_id,recipient_name,recipient_membership_id,evidence_kind)
        SELECT '{TA}',id,'{AA}','PESSOA FICTICIA A',{member_a},'ASSISTED' FROM claimed RETURNING id
    """
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(lambda _: query(pickup,tenant=TA,role="cp_api",check=False), range(20)))
    expect(all(r.returncode == 0 for r in results), "20 retiradas concorrentes terminam sem erro")
    expect(sum(bool(r.stdout.strip()) for r in results) == 1, "20 retiradas concorrentes geram somente um handoff")
    duplicate = (f"SET LOCAL app.capabilities='concierge.operate'; insert into cp.parcel_handoffs(tenant_id,parcel_id,operator_id,"
                 f"recipient_name,recipient_membership_id,evidence_kind) values('{TA}','{PA}','{AA}','PESSOA FICTICIA A',{member_a},'ASSISTED')")
    expect(error(duplicate,"23505",tenant=TA,role="cp_api"), "UNIQUE impede segunda evidencia de retirada")

    def decide(state):
        return query(f"update cp.arrival_requests set state='{state}',decided_by='{AA}',decision_source='APP',version=version+1 where id='{ARR}' and state='WAITING' and version=0 and expires_at>now() returning state",tenant=TA,role="cp_api")
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
    manage_checks()
    invitation_checks()
    decision_checks()
    recovery_checks()
    platform_checks()
    billing_checks()
    parcel_checks()
    visit_checks()
    access_checks()
    worker_checks()
    announcement_checks()
    request_checks()
    import_checks()
    attachment_checks()
    permit_link_checks()
    reservation_guest_checks()
    visit_rule_checks()
    waitlist_checks()
    waitlist_management_checks()
    waitlist_offer_notice_checks()
    unit_notice_checks()
    outbox_replay_checks()
    occurrence_checks()
    delegation_checks()
    move_checks()
    credential_checks()
    device_event_checks()
    device_health_checks()
    rental_checks()
    finance_checks()
    technical_grant_checks()
    subject_checks()
    age_checks()
    homologation_checks()
    area_checks()
    binding_checks()
    policy_checks()
    grant_checks()
    maintenance_checks()
    rollback = reservation(start="18:00",end="19:00",identifier=ROLL).replace(" RETURNING id","")
    query(f"BEGIN; SET LOCAL ROLE cp_api; SET LOCAL app.tenant_id='{TA}'; SET LOCAL app.actor_id='{AA}'; {rollback}; INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES('{TA}','TEST','{ROLL}',0,'{{}}'); ROLLBACK")
    expect(value(f"select (select count(*) from cp.reservations where id='{ROLL}')+(select count(*) from cp.outbox where aggregate_id='{ROLL}')")=="0",
           "rollback desfaz reserva e outbox juntas")
    query(f"update cp.memberships set status='REVOKED' where account_id='{AA}'")
    expect(value("select count(*) from platform.tenants",role="cp_api")=="0","vinculo revogado remove tenant do catalogo")
    print(f"PASS: {checks} verificacoes PostgreSQL; nao constitui aceite de produto.", flush=True)

if __name__ == "__main__":
    main()

