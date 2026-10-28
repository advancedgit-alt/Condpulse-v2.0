-- Contadores compartilhados de autenticacao. Nunca armazenar e-mail, IP ou cookie.
CREATE TABLE platform.auth_rate_limits (
 bucket_key text PRIMARY KEY CHECK(bucket_key ~ '^[0-9a-f]{64}$'),
 window_started_at timestamptz NOT NULL,
 attempts integer NOT NULL CHECK(attempts BETWEEN 1 AND 601),
 expires_at timestamptz NOT NULL,
 CHECK(expires_at > window_started_at AND expires_at <= window_started_at + interval '15 minutes')
);
CREATE INDEX auth_rate_limits_expiry ON platform.auth_rate_limits(expires_at);
ALTER TABLE platform.auth_rate_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.auth_rate_limits FORCE ROW LEVEL SECURITY;
-- A leitura de expirados serve exclusivamente a coleta limitada. Ativos alheios
-- continuam invisiveis; toda escrita de contador exige a chave local da transacao.
CREATE POLICY auth_rate_read ON platform.auth_rate_limits FOR SELECT TO cp_api
 USING(bucket_key = nullif(current_setting('app.auth_rate_key',true),'') OR expires_at <= clock_timestamp());
CREATE POLICY auth_rate_insert ON platform.auth_rate_limits FOR INSERT TO cp_api
 WITH CHECK(bucket_key = nullif(current_setting('app.auth_rate_key',true),''));
CREATE POLICY auth_rate_update ON platform.auth_rate_limits FOR UPDATE TO cp_api
 -- Expirados podem ser travados pelo GC SKIP LOCKED. Modificacao continua
 -- exigindo a chave propria no WITH CHECK, inclusive para um bucket expirado.
 USING(bucket_key = nullif(current_setting('app.auth_rate_key',true),'') OR expires_at <= clock_timestamp())
 WITH CHECK(bucket_key = nullif(current_setting('app.auth_rate_key',true),''));
CREATE POLICY auth_rate_expired_delete ON platform.auth_rate_limits FOR DELETE TO cp_api
 USING(expires_at <= clock_timestamp());
REVOKE ALL ON platform.auth_rate_limits FROM PUBLIC;
GRANT SELECT,INSERT,DELETE ON platform.auth_rate_limits TO cp_api;
GRANT UPDATE(window_started_at,attempts,expires_at) ON platform.auth_rate_limits TO cp_api;
