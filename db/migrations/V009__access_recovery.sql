-- Recuperação de acesso (CP-020). Estado curto entre o link de recuperação e a nova senha, ligado a um cookie
-- opaco próprio; nunca autentica a API. Guarda só o token de acesso do provedor, cifrado (sem refresh), e o hash
-- do cookie é a única chave de acesso da role runtime à linha.
CREATE TABLE platform.recovery_attempts (
 id uuid PRIMARY KEY,
 token_hash text NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
 account_id uuid NOT NULL REFERENCES platform.accounts(id),
 encrypted_access_token text NOT NULL CHECK (length(encrypted_access_token) BETWEEN 1 AND 32768),
 factor_id uuid,
 mfa_required boolean NOT NULL,
 failures integer NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 5),
 lease_id uuid,
 lease_until timestamptz,
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 CHECK (expires_at > created_at AND expires_at <= created_at + interval '15 minutes'),
 CHECK ((lease_id IS NULL) = (lease_until IS NULL)),
 CHECK (NOT mfa_required OR factor_id IS NOT NULL)
);
CREATE INDEX recovery_attempts_expiry ON platform.recovery_attempts(expires_at);
ALTER TABLE platform.recovery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.recovery_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY recovery_read ON platform.recovery_attempts FOR SELECT TO cp_api
 USING(token_hash = nullif(current_setting('app.recovery_hash',true),''));
CREATE POLICY recovery_insert ON platform.recovery_attempts FOR INSERT TO cp_api
 WITH CHECK(token_hash = nullif(current_setting('app.recovery_hash',true),'')
   AND account_id = platform.current_actor_id());
CREATE POLICY recovery_update ON platform.recovery_attempts FOR UPDATE TO cp_api
 USING(token_hash = nullif(current_setting('app.recovery_hash',true),''))
 WITH CHECK(token_hash = nullif(current_setting('app.recovery_hash',true),''));
CREATE POLICY recovery_delete ON platform.recovery_attempts FOR DELETE TO cp_api
 USING(token_hash = nullif(current_setting('app.recovery_hash',true),''));
REVOKE ALL ON platform.recovery_attempts FROM PUBLIC;
GRANT SELECT,INSERT,DELETE ON platform.recovery_attempts TO cp_api;
-- Conta, hash, fator e prazo são imutáveis para a role runtime.
GRANT UPDATE(encrypted_access_token,mfa_required,failures,lease_id,lease_until,version)
 ON platform.recovery_attempts TO cp_api;

-- Troca de senha encerra todas as sessões web da conta. Leitura e remoção por conta exigem o marcador local da
-- transação de recuperação, nunca presente no fluxo comum de sessão.
CREATE POLICY web_session_recovery_read ON platform.web_sessions FOR SELECT TO cp_api
 USING(account_id = platform.current_actor_id() AND current_setting('app.recovery_purge',true) = 'on');
CREATE POLICY web_session_recovery_delete ON platform.web_sessions FOR DELETE TO cp_api
 USING(account_id = platform.current_actor_id() AND current_setting('app.recovery_purge',true) = 'on');
