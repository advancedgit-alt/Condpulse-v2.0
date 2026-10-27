-- Sessões web opacas. Somente a API usa estas linhas; nenhum token bruto é persistido.
CREATE TABLE platform.web_sessions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 token_hash text NOT NULL UNIQUE CHECK(token_hash ~ '^[0-9a-f]{64}$'),
 account_id uuid NOT NULL REFERENCES platform.accounts(id),
 encrypted_access_token text NOT NULL,
 encrypted_refresh_token text NOT NULL,
 access_expires_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 refresh_lease_id uuid,
 refresh_lease_until timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version >= 0),
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(expires_at > created_at),
 CHECK((refresh_lease_id IS NULL) = (refresh_lease_until IS NULL))
);
CREATE INDEX web_sessions_expiry ON platform.web_sessions(expires_at);
ALTER TABLE platform.web_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.web_sessions FORCE ROW LEVEL SECURITY;
-- O hash de sessão é definido localmente na transação a partir do cookie opaco.
CREATE POLICY web_session_read ON platform.web_sessions FOR SELECT TO cp_api
 USING(token_hash = nullif(current_setting('app.session_hash',true),''));
CREATE POLICY web_session_insert ON platform.web_sessions FOR INSERT TO cp_api
 WITH CHECK(account_id=platform.current_actor_id()
   AND token_hash=nullif(current_setting('app.session_hash',true),''));
CREATE POLICY web_session_update ON platform.web_sessions FOR UPDATE TO cp_api
 USING(token_hash=nullif(current_setting('app.session_hash',true),''))
 WITH CHECK(token_hash=nullif(current_setting('app.session_hash',true),''));
CREATE POLICY web_session_delete ON platform.web_sessions FOR DELETE TO cp_api
 USING(token_hash=nullif(current_setting('app.session_hash',true),''));
REVOKE ALL ON platform.web_sessions FROM PUBLIC;
GRANT SELECT,INSERT,DELETE ON platform.web_sessions TO cp_api;
-- Identidade, hash e limite absoluto da sessão são imutáveis para a role runtime.
GRANT UPDATE(encrypted_access_token,encrypted_refresh_token,access_expires_at,
 refresh_lease_id,refresh_lease_until,version) ON platform.web_sessions TO cp_api;
