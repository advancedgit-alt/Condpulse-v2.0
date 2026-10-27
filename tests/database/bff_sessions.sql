-- Somente depois do suite descartável tests/database/run.py.
-- Nenhuma fixture desta transação é persistida.
BEGIN;
SET LOCAL ROLE cp_api;
SELECT set_config('app.actor_id','10000000-0000-4000-8000-000000000001',true);
SELECT set_config('app.session_hash',repeat('a',64),true);
INSERT INTO platform.web_sessions(id,token_hash,account_id,encrypted_access_token,encrypted_refresh_token,access_expires_at,expires_at)
VALUES('70000000-0000-4000-8000-000000000001',repeat('a',64),'10000000-0000-4000-8000-000000000001',
 'SYNTHETIC_CIPHERTEXT_FOR_SQL_POLICY_TEST_ONLY','SYNTHETIC_CIPHERTEXT_FOR_SQL_POLICY_TEST_ONLY',now()+interval '1 hour',now()+interval '8 hours');
DO $$
DECLARE changed integer;
BEGIN
 IF (SELECT count(*) FROM platform.web_sessions)<>1 THEN RAISE EXCEPTION 'BFF session not visible by own opaque hash'; END IF;
 PERFORM set_config('app.session_hash','',true);
 IF (SELECT count(*) FROM platform.web_sessions)<>0 THEN RAISE EXCEPTION 'BFF session leaked without opaque hash'; END IF;
 PERFORM set_config('app.session_hash',repeat('b',64),true);
 IF (SELECT count(*) FROM platform.web_sessions)<>0 THEN RAISE EXCEPTION 'BFF session leaked across hash'; END IF;
 PERFORM set_config('app.session_hash',repeat('a',64),true);
 BEGIN
  UPDATE platform.web_sessions SET account_id='10000000-0000-4000-8000-000000000002';
  RAISE EXCEPTION 'BFF account must be immutable';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  UPDATE platform.web_sessions SET expires_at=expires_at+interval '1 day';
  RAISE EXCEPTION 'BFF absolute expiry must be immutable';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 UPDATE platform.web_sessions SET refresh_lease_id='80000000-0000-4000-8000-000000000001',refresh_lease_until=now()+interval '30 seconds'
 WHERE token_hash=repeat('a',64) AND version=0 AND(refresh_lease_until IS NULL OR refresh_lease_until<now());
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'First BFF refresh lease failed'; END IF;
 UPDATE platform.web_sessions SET refresh_lease_id='80000000-0000-4000-8000-000000000002',refresh_lease_until=now()+interval '30 seconds'
 WHERE token_hash=repeat('a',64) AND version=0 AND(refresh_lease_until IS NULL OR refresh_lease_until<now());
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>0 THEN RAISE EXCEPTION 'Second BFF refresh must not take active lease'; END IF;
 UPDATE platform.web_sessions SET version=version+1,refresh_lease_id=NULL,refresh_lease_until=NULL
 WHERE token_hash=repeat('a',64) AND version=0 AND refresh_lease_id='80000000-0000-4000-8000-000000000001';
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'BFF lease CAS failed'; END IF;
 DELETE FROM platform.web_sessions WHERE token_hash=repeat('a',64);
 UPDATE platform.web_sessions SET version=version+1 WHERE token_hash=repeat('a',64) AND version=1;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>0 THEN RAISE EXCEPTION 'BFF refresh resurrected logged-out session'; END IF;
END $$;
ROLLBACK;
-- Janela de crash: lease vencida não pode ser reassumida nem concluir rotação.
-- O DELETE é commitado, e uma transação posterior comprova a invalidação durável.
BEGIN;
SET LOCAL ROLE cp_api;
SELECT set_config('app.actor_id','10000000-0000-4000-8000-000000000001',true);
SELECT set_config('app.session_hash',repeat('c',64),true);
INSERT INTO platform.web_sessions(id,token_hash,account_id,encrypted_access_token,encrypted_refresh_token,access_expires_at,expires_at)
VALUES('70000000-0000-4000-8000-000000000003',repeat('c',64),'10000000-0000-4000-8000-000000000001',
 'SYNTHETIC_CIPHERTEXT_FOR_SQL_POLICY_TEST_ONLY','SYNTHETIC_CIPHERTEXT_FOR_SQL_POLICY_TEST_ONLY',now()+interval '1 hour',now()+interval '8 hours');
UPDATE platform.web_sessions
 SET refresh_lease_id='80000000-0000-4000-8000-000000000003',refresh_lease_until=now()-interval '1 second'
 WHERE token_hash=repeat('c',64);
COMMIT;
BEGIN;
SET LOCAL ROLE cp_api;
SELECT set_config('app.actor_id','10000000-0000-4000-8000-000000000001',true);
SELECT set_config('app.session_hash',repeat('c',64),true);
DO $$
DECLARE changed integer;
BEGIN
 PERFORM 1 FROM platform.web_sessions WHERE token_hash=repeat('c',64) FOR UPDATE;
 UPDATE platform.web_sessions SET version=version+1,refresh_lease_id=NULL,refresh_lease_until=NULL
 WHERE token_hash=repeat('c',64) AND refresh_lease_id='80000000-0000-4000-8000-000000000003'
 AND refresh_lease_until>now() AND version=0 AND expires_at>now();
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>0 THEN RAISE EXCEPTION 'Expired BFF lease must not complete refresh'; END IF;
 DELETE FROM platform.web_sessions WHERE token_hash=repeat('c',64)
 AND refresh_lease_id IS NOT NULL AND refresh_lease_until<=now();
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'Uncertain BFF session must be invalidated'; END IF;
END $$;
COMMIT;
BEGIN;
SET LOCAL ROLE cp_api;
SELECT set_config('app.actor_id','10000000-0000-4000-8000-000000000001',true);
SELECT set_config('app.session_hash',repeat('c',64),true);
DO $$
BEGIN
 IF EXISTS(SELECT 1 FROM platform.web_sessions WHERE token_hash=repeat('c',64)) THEN
  RAISE EXCEPTION 'Uncertain BFF session deletion was not durable';
 END IF;
END $$;
ROLLBACK;
