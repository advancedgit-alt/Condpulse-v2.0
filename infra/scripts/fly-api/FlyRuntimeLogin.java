package br.com.condpulse.bootstrap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.logging.LogFactory;
import org.postgresql.util.PasswordUtil;

/**
 * Login de banco exclusivo da API na Fly.io (docs/FLY_API.md). Executor manual privilegiado, nunca componente da API.
 *
 * Mesma política do login da Vercel (RuntimeConfiguration): nome cp_runtime_<geração>, associação única a cp_api
 * (ADMIN=false, INHERIT=true, SET=false), nenhum grant direto, sem SUPERUSER/BYPASSRLS/CREATEDB/CREATEROLE/REPLICATION,
 * senha SCRAM com validade de 10 minutos até a confirmação, prova de login TLS e READY só depois do readback na Fly.
 * Diferenças com motivo escrito: (1) o schema atual vai além da V005 do executor da Vercel, então a conferência de
 * grants compara o login com cp_api em todas as tabelas/colunas/funções em vez da lista fixa da V005; (2) o login da
 * Vercel continua existindo, então só ele (marker da Vercel) é tolerado e o teto de conexões dele entra na margem da
 * ADR-0006; (3) teto 4 em vez de 16: com 18 backends não runtime medidos em 30/10, 18+16+8+20=62 não cabe em 60; 4 = Hikari 2 + sobreposição de deploy.
 *
 * A senha é gerada pelo processo Node do runner e chega só pela primeira linha do stdin; nunca é impressa.
 * Protocolo no stdout: {"phase":"STAGED"} quando o login foi provado, depois uma linha {"phase":"RESULT",...}.
 * No stdin, depois da senha: COMMIT (segredos gravados e conferidos na Fly) ou qualquer outra coisa (desabilita).
 */
public final class FlyRuntimeLogin {
    public static final String PROJECT = RuntimeConfiguration.PROJECT;
    public static final String APP = "condpulse-v2-0";
    static final int CONNECTION_LIMIT = 4;
    static final int MAX_CONNECTIONS = 60;
    static final int MARGIN = 20;
    static final String CA_PATH = "/run/secrets/db-ca.crt";
    static final String OWNER_URL = RuntimeConfiguration.OWNER_URL;
    private static final String UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final String VERCEL_MARKER = "condpulse-runtime:" + PROJECT + ":" + UUID_PATTERN + ":[a-f0-9]{40}:(STAGED|DISABLED|READY)";

    /** Canal com o processo Node: senha gerada no runner e confirmação do readback dos segredos da Fly. */
    interface Channel {
        String password() throws Exception;
        boolean confirmStaged() throws Exception;
    }
    @FunctionalInterface interface RuntimeConnector { Connection open(String role, String password) throws Exception; }

    record Config(UUID generation, String sha, String mode, String expectedFingerprint, int maxConnections) {
        Config {
            require(generation != null && !generation.equals(new UUID(0, 0)) && sha != null && sha.matches("[a-f0-9]{40}"), "FLY_RUNTIME_REQUEST_INVALID");
            require("inspect".equals(mode) || "apply".equals(mode), "FLY_RUNTIME_OPERATION_INVALID");
            require("apply".equals(mode) ? expectedFingerprint != null && expectedFingerprint.matches("[a-f0-9]{64}")
                : expectedFingerprint == null || expectedFingerprint.isEmpty(), "FLY_RUNTIME_FINGERPRINT_INVALID");
            require(maxConnections > 0, "FLY_RUNTIME_REQUEST_INVALID");
        }
        String role() { return "cp_runtime_" + generation.toString().replace("-", ""); }
        String marker() { return "condpulse-runtime-fly:" + PROJECT + ":" + generation + ":" + sha; }
        @Override public String toString() { return "FlyRuntimeConfig[" + role() + "," + mode + "]"; }
    }
    record Outcome(boolean eligible, boolean applied, boolean alreadyApplied, String state, String fingerprint, String code) { }
    private record Role(boolean exists, boolean login, String expiry, String comment, String state, int limit) { }
    static final class Denied extends IllegalStateException {
        final String code; Denied(String code) { super(code); this.code = code; }
    }
    static void require(boolean allowed, String code) { if (!allowed) throw new Denied(code); }
    private FlyRuntimeLogin() { }

    public static void main(String[] args) {
        java.util.logging.LogManager.getLogManager().reset();
        LogFactory.setLogCreator(new BootstrapBaseline.QuietLogCreator());
        Outcome outcome = null; Connection owner = null;
        try {
            require(args.length == 0, "FLY_RUNTIME_ARGUMENTS_FORBIDDEN");
            Map<String, String> env = System.getenv();
            BootstrapBaseline.productionConfig(env);
            require(OWNER_URL.equals(env.get("DATABASE_URL")) && ("postgres." + PROJECT).equals(env.get("DATABASE_USER")), "FLY_RUNTIME_TARGET_DENIED");
            require(BootstrapManifest.sourceCodes(BootstrapManifest.load()).isEmpty(), "FLY_RUNTIME_SOURCE_INVALID");
            String generation = env.get("CP_FLY_GENERATION"), sha = env.get("CP_FLY_EXPECTED_COMMIT");
            require(generation != null && generation.matches(UUID_PATTERN), "FLY_RUNTIME_GENERATION_INVALID");
            require(sha != null && sha.equals(env.get("GITHUB_SHA")), "FLY_RUNTIME_COMMIT_MISMATCH");
            require(CA_PATH.equals(env.get("CP_FLY_CA_PATH")) && Files.isRegularFile(Path.of(CA_PATH), LinkOption.NOFOLLOW_LINKS), "FLY_RUNTIME_CA_INVALID");
            String mode = env.getOrDefault("CP_FLY_RUNTIME_OPERATION", "inspect");
            Config config = new Config(UUID.fromString(generation), sha, mode, env.getOrDefault("CP_FLY_DB_FINGERPRINT", ""), MAX_CONNECTIONS);
            owner = DriverManager.getConnection(OWNER_URL, properties(env.get("DATABASE_USER"), env.get("DATABASE_PASSWORD")));
            requireTls(owner);
            Flyway flyway = Flyway.configure().dataSource(OWNER_URL, env.get("DATABASE_USER"), env.get("DATABASE_PASSWORD"))
                .defaultSchema("platform").schemas("platform", "cp").createSchemas(false).locations("classpath:db/migration")
                .baselineOnMigrate(false).cleanDisabled(true).validateMigrationNaming(true).outOfOrder(false)
                .loggers(BootstrapBaseline.QuietLogCreator.class.getName()).load();
            outcome = execute(config, owner, flyway, (role, password) -> {
                Connection connection = DriverManager.getConnection(OWNER_URL, properties(role + "." + PROJECT, password));
                try { requireTls(connection); return connection; }
                catch (Exception error) { connection.close(); throw error; }
            }, new StdioChannel());
        } catch (Denied denied) { outcome = new Outcome(false, false, false, "NOT_ATTEMPTED", "", denied.code); }
        catch (Exception ignored) { outcome = new Outcome(false, false, false, "NOT_ATTEMPTED", "", "FLY_RUNTIME_PREFLIGHT_FAILED"); }
        finally {
            if (owner != null) try { owner.close(); } catch (Exception ignored) { if (outcome == null) outcome = new Outcome(false, false, false, "UNKNOWN", "", "FLY_RUNTIME_RESULT_UNKNOWN"); }
            if (outcome == null) outcome = new Outcome(false, false, false, "UNKNOWN", "", "FLY_RUNTIME_RESULT_UNKNOWN");
        }
        System.out.println(result(outcome)); System.out.flush();
        System.exit(outcome.code().isEmpty() ? 0 : 1);
    }

    static String result(Outcome o) {
        return String.format("{\"phase\":\"RESULT\",\"eligible\":%s,\"applied\":%s,\"alreadyApplied\":%s,\"state\":\"%s\",\"fingerprint\":\"%s\",\"code\":\"%s\"}",
            o.eligible(), o.applied(), o.alreadyApplied(), o.state(), o.fingerprint(), o.code());
    }

    /** Lê a senha (primeira linha) e a confirmação com prazo; nunca ecoa o que leu. */
    private static final class StdioChannel implements Channel {
        private final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        private String line(long seconds) throws Exception {
            var pending = CompletableFuture.supplyAsync(() -> { try { return input.readLine(); } catch (Exception error) { return null; } });
            try { return pending.get(seconds, TimeUnit.SECONDS); } catch (Exception timeout) { return null; }
        }
        @Override public String password() throws Exception { return line(30); }
        @Override public boolean confirmStaged() throws Exception {
            System.out.println("{\"phase\":\"STAGED\"}"); System.out.flush();
            return "COMMIT".equals(line(8 * 60));
        }
    }

    private static Properties properties(String user, String password) {
        Properties p = new Properties(); p.setProperty("user", user); p.setProperty("password", password);
        p.setProperty("connectTimeout", "5"); p.setProperty("socketTimeout", "20"); p.setProperty("ApplicationName", "condpulse-fly-runtime-login");
        p.setProperty("requireAuth", "scram-sha-256"); return p;
    }

    static void requireTls(Connection c) throws Exception {
        try (var q = query(c, "select ssl from pg_stat_ssl where pid=pg_backend_pid()"); var r = q.executeQuery()) {
            require(r.next() && r.getBoolean(1), "FLY_RUNTIME_DATABASE_TLS_REQUIRED");
        }
    }

    static Outcome execute(Config config, Connection owner, Flyway flyway, RuntimeConnector runtime, Channel channel) {
        String fingerprint = ""; boolean locked = false, attempted = false;
        try {
            require(owner.getAutoCommit(), "FLY_RUNTIME_TRANSACTION_STATE_INVALID"); owner.setReadOnly(true);
            try (var q = query(owner, "select current_user,session_user,pg_try_advisory_lock(?)")) {
                q.setLong(1, BootstrapBaseline.LOCK_KEY);
                try (var r = q.executeQuery()) {
                    require(r.next(), "FLY_RUNTIME_ACTOR_INVALID"); locked = r.getBoolean(3);
                    require("postgres".equals(r.getString(1)) && "postgres".equals(r.getString(2)), "FLY_RUNTIME_ACTOR_INVALID");
                    require(locked, "FLY_RUNTIME_BUSY");
                }
            }
            String version = foundation(owner, flyway);
            Role role = role(owner, config);
            List<String> others = others(owner, config);
            if (role.exists()) verifyRole(owner, config.role());
            fingerprint = fingerprint(config, version, role, others);
            if ("READY".equals(role.state())) {
                require(role.login() && "infinity".equals(role.expiry()), "FLY_RUNTIME_READY_ROLE_DIVERGED");
                if ("apply".equals(config.mode())) require(fingerprint.equals(config.expectedFingerprint()), "FLY_RUNTIME_INSPECTION_CHANGED");
                return new Outcome(true, false, true, "READY", fingerprint, "");
            }
            budget(owner, config);
            if ("inspect".equals(config.mode())) return new Outcome(true, false, false, role.state(), fingerprint, "");
            require(fingerprint.equals(config.expectedFingerprint()), "FLY_RUNTIME_INSPECTION_CHANGED");
            String password = channel.password();
            require(password != null && password.matches("[A-Za-z0-9_-]{48}"), "FLY_RUNTIME_PASSWORD_INVALID");
            String verifier = PasswordUtil.encodeScramSha256(password.toCharArray());
            attempted = true; stage(owner, config, role.exists(), verifier); verifyRole(owner, config.role());
            try (Connection login = runtime.open(config.role(), password)) { verifyLogin(login, config.role()); }
            require(channel.confirmStaged(), "FLY_RUNTIME_SECRETS_NOT_CONFIRMED");
            verifyRole(owner, config.role()); ready(owner, config);
            Role finalRole = role(owner, config);
            require("READY".equals(finalRole.state()) && finalRole.login() && "infinity".equals(finalRole.expiry()), "FLY_RUNTIME_READBACK_FAILED");
            return new Outcome(true, true, false, "READY", fingerprint(config, version, finalRole, others(owner, config)), "");
        } catch (Exception failure) {
            if (attempted) {
                boolean disabled = disable(owner, config);
                return new Outcome(false, false, false, disabled ? "DISABLED" : "UNKNOWN", fingerprint,
                    disabled ? "FLY_RUNTIME_PARTIAL_RECONCILE_REQUIRED" : "FLY_RUNTIME_RESULT_UNKNOWN");
            }
            return new Outcome(false, false, false, "NOT_ATTEMPTED", fingerprint, failure instanceof Denied denied ? denied.code : "FLY_RUNTIME_INSPECTION_FAILED");
        } finally {
            try { if (!owner.getAutoCommit()) { owner.rollback(); owner.setAutoCommit(true); } } catch (Exception ignored) { }
            if (locked) try (var q = query(owner, "select pg_advisory_unlock(?)")) { q.setLong(1, BootstrapBaseline.LOCK_KEY); q.execute(); }
            catch (Exception ignored) { /* Fechar a sessão libera o lock; a validade de 10 minutos limita o login STAGED. */ }
        }
    }

    private static String fingerprint(Config c, String version, Role r, List<String> others) throws Exception {
        StringBuilder body = new StringBuilder("fly-runtime-v1|").append(PROJECT).append('|').append(APP).append('|').append(c.generation())
            .append('|').append(c.sha()).append('|').append(version).append('|').append(c.maxConnections()).append('|').append(r.exists())
            .append('|').append(r.login()).append('|').append(r.expiry()).append('|').append(r.comment()).append('|').append(r.limit());
        for (String other : others) body.append('\n').append(other);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Histórico oficial (BASELINE 002 + SQL contíguas), nada pendente no JAR revisado e invariantes de dono/RLS/cp_api. */
    private static String foundation(Connection c, Flyway flyway) throws Exception {
        var versions = new ArrayList<Integer>();
        try (var q = query(c, "select version,type,success,checksum from platform.flyway_schema_history order by installed_rank"); var r = q.executeQuery()) {
            while (r.next()) {
                int version = new java.math.BigInteger(r.getString(1)).intValueExact(); int index = versions.size();
                require(r.getBoolean(3) && (index == 0 ? version == 2 && "BASELINE".equals(r.getString(2))
                    : version == index + 2 && "SQL".equals(r.getString(2)) && r.getObject(4) != null), "FLY_RUNTIME_HISTORY_INCOMPATIBLE");
                versions.add(version);
            }
        }
        require(versions.size() >= 4, "FLY_RUNTIME_HISTORY_INCOMPATIBLE");
        flyway.validate(); require(flyway.info().pending().length == 0, "FLY_RUNTIME_MIGRATIONS_PENDING");
        try (var q = query(c, "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname in ('platform','cp') and c.relkind in ('r','p') and c.relname<>'flyway_schema_history' and (pg_get_userbyid(c.relowner)<>'postgres' or not c.relrowsecurity or not c.relforcerowsecurity)"); var r = q.executeQuery()) {
            require(r.next() && r.getLong(1) == 0, "FLY_RUNTIME_SCHEMA_RLS_DIVERGED");
        }
        try (var q = query(c, "select count(*) from pg_namespace where nspname in ('platform','cp') and pg_get_userbyid(nspowner)<>'postgres'"); var r = q.executeQuery()) {
            require(r.next() && r.getLong(1) == 0, "FLY_RUNTIME_SCHEMA_OWNER_DIVERGED");
        }
        try (var q = query(c, "select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname in ('platform','cp') and (pg_get_userbyid(p.proowner)<>'postgres' or p.prosecdef)"); var r = q.executeQuery()) {
            require(r.next() && r.getLong(1) == 0, "FLY_RUNTIME_FUNCTION_OWNER_DIVERGED");
        }
        try (var q = query(c, "select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication or rolcanlogin,(select count(*) from pg_auth_members m where m.member=pg_roles.oid) from pg_roles where rolname='cp_api'"); var r = q.executeQuery()) {
            require(r.next() && !r.getBoolean(1) && r.getLong(2) == 0, "FLY_RUNTIME_API_ROLE_DIVERGED");
        }
        noDdlOrOwnership(c, "cp_api");
        // cp_api nunca recebe privilégios estruturais nem repasse de grant nas tabelas da aplicação.
        try (var q = query(c, "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace cross join unnest(array['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER','MAINTAIN']) p(privilege) where n.nspname in ('platform','cp') and c.relkind in ('r','p','v','m') and ((p.privilege in ('TRUNCATE','REFERENCES','TRIGGER','MAINTAIN') and has_table_privilege('cp_api',c.oid,p.privilege)) or has_table_privilege('cp_api',c.oid,p.privilege||' WITH GRANT OPTION'))"); var r = q.executeQuery()) {
            require(r.next() && r.getLong(1) == 0, "FLY_RUNTIME_API_GRANTS_UNSAFE");
        }
        return Integer.toString(versions.get(versions.size() - 1));
    }

    private static Role role(Connection c, Config config) throws Exception {
        try (var q = query(c, "select rolcanlogin,rolvaliduntil::text,shobj_description(oid,'pg_authid'),rolconnlimit from pg_roles where rolname=?")) {
            q.setString(1, config.role());
            try (var r = q.executeQuery()) {
                if (!r.next()) return new Role(false, false, "", "", "ABSENT", 0);
                String comment = r.getString(3);
                // Mesma geração: o marker do commit atual, ou READY gravado por um commit anterior. READY só é lido e
                // devolvido sem escrita (o apply só regrava segredos na Fly); STAGED/DISABLED de outro commit segue conflito.
                String generationPrefix = "condpulse-runtime-fly:" + PROJECT + ":" + config.generation() + ":";
                boolean own = comment != null && comment.startsWith(config.marker() + ":");
                boolean earlierReady = comment != null && comment.startsWith(generationPrefix)
                    && comment.substring(generationPrefix.length()).matches("[a-f0-9]{40}:READY");
                require(own || earlierReady, "FLY_RUNTIME_ROLE_OWNERSHIP_CONFLICT");
                String state = comment.substring(generationPrefix.length() + 41);
                require(Set.of("STAGED", "DISABLED", "READY").contains(state), "FLY_RUNTIME_ROLE_STATE_INVALID");
                return new Role(true, r.getBoolean(1), r.getString(2), comment, state, r.getInt(4));
            }
        }
    }

    /** Só o login da Vercel (marker próprio) pode coexistir; outra geração da Fly ou role desconhecida bloqueia. */
    private static List<String> others(Connection c, Config config) throws Exception {
        List<String> result = new ArrayList<>();
        try (var q = query(c, "select rolname,coalesce(shobj_description(oid,'pg_authid'),''),rolconnlimit,rolcanlogin from pg_roles where rolname like 'cp\\_runtime\\_%' escape '\\' and rolname<>? order by rolname")) {
            q.setString(1, config.role());
            try (var r = q.executeQuery()) {
                while (r.next()) {
                    String comment = r.getString(2);
                    require(!comment.startsWith("condpulse-runtime-fly:"), "FLY_RUNTIME_OTHER_GENERATION_EXISTS");
                    require(comment.matches(VERCEL_MARKER), "FLY_RUNTIME_UNKNOWN_RUNTIME_ROLE");
                    require(r.getInt(3) >= 0, "FLY_RUNTIME_BUDGET_UNBOUNDED");
                    result.add(r.getString(1) + "|" + comment + "|" + r.getInt(3) + "|" + r.getBoolean(4));
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Margem da ADR-0006 com duas identidades: backends que não são runtime + tetos das outras roles runtime
     * + teto desta + 20 precisam caber em max_connections, que precisa ser exatamente o valor revisado.
     */
    private static void budget(Connection c, Config config) throws Exception {
        try (var q = query(c, "select current_setting('max_connections')::int,(select count(*) from pg_stat_activity a where a.usename is null or a.usename not like 'cp\\_runtime\\_%' escape '\\'),(select coalesce(sum(rolconnlimit),0) from pg_roles where rolname like 'cp\\_runtime\\_%' escape '\\' and rolname<>?)")) {
            q.setString(1, config.role());
            try (var r = q.executeQuery()) {
                require(r.next() && r.getInt(1) == config.maxConnections()
                    && r.getLong(2) + r.getLong(3) + CONNECTION_LIMIT + MARGIN <= config.maxConnections(), "FLY_RUNTIME_BUDGET_INSUFFICIENT");
            }
        }
    }

    static void verifyRole(Connection c, String role) throws Exception {
        try (var q = query(c, "select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication or not rolinherit,rolconnlimit from pg_roles where rolname=?")) {
            q.setString(1, role);
            try (var r = q.executeQuery()) { require(r.next() && !r.getBoolean(1) && r.getInt(2) == CONNECTION_LIMIT, "FLY_RUNTIME_ROLE_UNSAFE"); }
        }
        try (var q = query(c, "select g.rolname,m.admin_option,m.inherit_option,m.set_option from pg_auth_members m join pg_roles u on u.oid=m.member join pg_roles g on g.oid=m.roleid where u.rolname=?")) {
            q.setString(1, role);
            try (var r = q.executeQuery()) {
                require(r.next() && "cp_api".equals(r.getString(1)) && !r.getBoolean(2) && r.getBoolean(3) && !r.getBoolean(4) && !r.next(), "FLY_RUNTIME_MEMBERSHIP_UNSAFE");
            }
        }
        noDdlOrOwnership(c, role);
        // Nenhum grant direto em lugar algum: tudo que o login pode fazer vem de cp_api, igual ao login da Vercel.
        try (var q = query(c, "with target as (select oid from pg_roles where rolname=?) select (select count(*) from pg_class c cross join lateral aclexplode(c.relacl) a where a.grantee=(select oid from target))+(select count(*) from pg_attribute t cross join lateral aclexplode(t.attacl) a where a.grantee=(select oid from target))+(select count(*) from pg_namespace n cross join lateral aclexplode(n.nspacl) a where a.grantee=(select oid from target))+(select count(*) from pg_proc p cross join lateral aclexplode(p.proacl) a where a.grantee=(select oid from target))+(select count(*) from pg_database d cross join lateral aclexplode(d.datacl) a where a.grantee=(select oid from target))")) {
            q.setString(1, role);
            try (var r = q.executeQuery()) { require(r.next() && r.getLong(1) == 0, "FLY_RUNTIME_DIRECT_GRANT_FORBIDDEN"); }
        }
        // Privilégio efetivo idêntico ao de cp_api em tabelas, colunas, funções e schemas da aplicação.
        try (var q = query(c, "select (select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace cross join unnest(array['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER','MAINTAIN']) p(privilege) where n.nspname in ('platform','cp') and c.relkind in ('r','p','v','m') and (has_table_privilege(?,c.oid,p.privilege)<>has_table_privilege('cp_api',c.oid,p.privilege) or has_table_privilege(?,c.oid,p.privilege||' WITH GRANT OPTION')))"
                + "+(select count(*) from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace cross join unnest(array['SELECT','INSERT','UPDATE','REFERENCES']) p(privilege) where n.nspname in ('platform','cp') and c.relkind in ('r','p','v','m') and a.attnum>0 and not a.attisdropped and has_column_privilege(?,c.oid,a.attnum,p.privilege)<>has_column_privilege('cp_api',c.oid,a.attnum,p.privilege))"
                + "+(select count(*) from pg_proc f join pg_namespace n on n.oid=f.pronamespace where n.nspname in ('platform','cp') and has_function_privilege(?,f.oid,'EXECUTE')<>has_function_privilege('cp_api',f.oid,'EXECUTE'))"
                + "+(select count(*) from pg_namespace n cross join unnest(array['USAGE','CREATE']) p(privilege) where n.nspname in ('platform','cp') and has_schema_privilege(?,n.oid,p.privilege)<>has_schema_privilege('cp_api',n.oid,p.privilege))")) {
            for (int index = 1; index <= 5; index++) q.setString(index, role);
            try (var r = q.executeQuery()) { require(r.next() && r.getLong(1) == 0, "FLY_RUNTIME_GRANTS_DIVERGED"); }
        }
    }
    private static void noDdlOrOwnership(Connection c, String role) throws Exception {
        try (var q = query(c, "select (select count(*) from pg_shdepend where refclassid='pg_authid'::regclass and refobjid=(select oid from pg_roles where rolname=?) and deptype='o')+(select count(*) from pg_namespace where nspname !~ '^pg_' and nspname<>'information_schema' and has_schema_privilege(?,oid,'CREATE'))")) {
            q.setString(1, role); q.setString(2, role);
            try (var r = q.executeQuery()) { require(r.next() && r.getLong(1) == 0, "FLY_RUNTIME_DDL_OR_OWNERSHIP_UNSAFE"); }
        }
    }
    static void verifyLogin(Connection c, String role) throws Exception {
        try (var q = query(c, "select current_user,session_user,current_setting('row_security')"); var r = q.executeQuery()) {
            require(r.next() && role.equals(r.getString(1)) && role.equals(r.getString(2)) && "on".equals(r.getString(3)), "FLY_RUNTIME_LOGIN_PROOF_FAILED");
        }
        verifyRole(c, role);
        try (var q = query(c, "select count(*) from cp.reservations"); var r = q.executeQuery()) { require(r.next() && r.getLong(1) == 0, "FLY_RUNTIME_RLS_PROOF_FAILED"); }
    }

    private static void stage(Connection c, Config config, boolean exists, String verifier) throws Exception {
        c.setReadOnly(false); c.setAutoCommit(false);
        try {
            if (!exists) sql(c, "CREATE ROLE " + config.role() + " NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT " + CONNECTION_LIMIT);
            sql(c, "GRANT cp_api TO " + config.role() + " WITH ADMIN FALSE"); sql(c, "GRANT cp_api TO " + config.role() + " WITH INHERIT TRUE"); sql(c, "GRANT cp_api TO " + config.role() + " WITH SET FALSE");
            Instant expiry;
            try (var q = query(c, "select clock_timestamp()+interval '10 minutes'"); var r = q.executeQuery()) { require(r.next(), "FLY_RUNTIME_DATABASE_TIME_INVALID"); expiry = r.getTimestamp(1).toInstant(); }
            sql(c, "ALTER ROLE " + config.role() + " LOGIN PASSWORD " + literal(verifier) + " VALID UNTIL " + literal(expiry.toString()));
            sql(c, "COMMENT ON ROLE " + config.role() + " IS " + literal(config.marker() + ":STAGED")); c.commit();
        } catch (Exception error) { c.rollback(); throw error; } finally { c.setAutoCommit(true); }
    }
    private static void ready(Connection c, Config config) throws Exception {
        c.setReadOnly(false); c.setAutoCommit(false);
        try { sql(c, "ALTER ROLE " + config.role() + " VALID UNTIL 'infinity'"); sql(c, "COMMENT ON ROLE " + config.role() + " IS " + literal(config.marker() + ":READY")); c.commit(); }
        catch (Exception error) { c.rollback(); throw error; } finally { c.setAutoCommit(true); }
    }
    private static boolean disable(Connection c, Config config) {
        try {
            if (!c.getAutoCommit()) { c.rollback(); c.setAutoCommit(true); } c.setReadOnly(false);
            Role current = role(c, config); if (!current.exists()) return true;
            // Só esta geração, marcada por este source revisado, pode ser desabilitada.
            c.setAutoCommit(false); sql(c, "ALTER ROLE " + config.role() + " NOLOGIN PASSWORD NULL VALID UNTIL '1970-01-01T00:00:00Z'");
            sql(c, "COMMENT ON ROLE " + config.role() + " IS " + literal(config.marker() + ":DISABLED")); c.commit(); c.setAutoCommit(true); return true;
        } catch (Exception ignored) { try { c.rollback(); c.setAutoCommit(true); } catch (Exception closed) { } return false; }
    }
    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static PreparedStatement query(Connection c, String sql) throws Exception { var q = c.prepareStatement(sql); q.setQueryTimeout(10); return q; }
    private static void sql(Connection c, String sql) throws Exception { try (var q = c.createStatement()) { q.setQueryTimeout(10); q.execute(sql); } }
}
