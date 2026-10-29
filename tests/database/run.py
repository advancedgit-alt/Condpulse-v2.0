"""Testes de integridade CondPulse, exclusivamente em PostgreSQL descartável."""
import concurrent.futures
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
    manage_checks()
    invitation_checks()
    decision_checks()
    recovery_checks()
    platform_checks()
    billing_checks()
    rollback = reservation(start="18:00",end="19:00",identifier=ROLL).replace(" RETURNING id","")
    query(f"BEGIN; SET LOCAL ROLE cp_api; SET LOCAL app.tenant_id='{TA}'; SET LOCAL app.actor_id='{AA}'; {rollback}; INSERT INTO cp.outbox(tenant_id,event_type,aggregate_id,aggregate_version,payload) VALUES('{TA}','TEST','{ROLL}',0,'{{}}'); ROLLBACK")
    expect(value(f"select (select count(*) from cp.reservations where id='{ROLL}')+(select count(*) from cp.outbox where aggregate_id='{ROLL}')")=="0",
           "rollback desfaz reserva e outbox juntas")
    query(f"update cp.memberships set status='REVOKED' where account_id='{AA}'")
    expect(value("select count(*) from platform.tenants",role="cp_api")=="0","vinculo revogado remove tenant do catalogo")
    print(f"PASS: {checks} verificacoes PostgreSQL; nao constitui aceite de produto.", flush=True)

if __name__ == "__main__":
    main()

