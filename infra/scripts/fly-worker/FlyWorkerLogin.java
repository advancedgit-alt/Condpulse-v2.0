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
 * Login de banco exclusivo do worker na Fly.io (docs/FLY_WORKER.md). Executor manual privilegiado, nunca componente
 * do worker.
 *
 * Mesma política do login da API na Fly (FlyRuntimeLogin), trocando o grupo: nome cp_worker_<geração>, associação
 * única a cp_worker (ADMIN=false, INHERIT=true, SET=false), nunca cp_api, nenhum grant direto, sem
 * SUPERUSER/BYPASSRLS/CREATEDB/CREATEROLE/REPLICATION, senha SCRAM com validade de 10 minutos até a confirmação, prova
 * de login e READY só depois do readback dos nomes na Fly. Teto 2 (pool Hikari 1 + uma sobreposição de reinício).
 *
 * Margem da ADR-0006 com três identidades: backends que não são runtime nem worker + tetos de todas as roles
 * cp_runtime_* (Vercel e API na Fly) + teto deste login + 20 precisam caber em max_connections=60.
 *
 * Operação separada vercel-limit: reduz o teto do login READY da Vercel de 16 para 4 (o tráfego da API está na Fly).
 * Só esse atributo muda, com ACK próprio impresso pela inspeção; nunca roda junto do apply.
 *
 * Protocolo no stdout: {"phase":"STAGED"} quando o login foi provado, depois uma linha {"phase":"RESULT",...}.
 * No stdin: senha (primeira linha) e depois COMMIT (segredos gravados e conferidos na Fly) ou outra coisa (desabilita).
 */
public final class FlyWorkerLogin {
    public static final String PROJECT = RuntimeConfiguration.PROJECT;
    public static final String APP = "condpulse-v2-0-worker";
    static final int CONNECTION_LIMIT = 2;
    static final int MAX_CONNECTIONS = 60;
    static final int MARGIN = 20;
    static final int VERCEL_FROM = 16, VERCEL_TO = 4;
    static final String CA_PATH = "/run/secrets/db-ca.crt";
    static final String OWNER_URL = RuntimeConfiguration.OWNER_URL;
    private static final String UUID_PATTERN = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    static final String VERCEL_MARKER = "condpulse-runtime:" + PROJECT + ":" + UUID_PATTERN + ":[a-f0-9]{40}:(STAGED|DISABLED|READY)";
    static final String FLY_API_MARKER = "condpulse-runtime-fly:" + PROJECT + ":" + UUID_PATTERN + ":[a-f0-9]{40}:(STAGED|DISABLED|READY)";
    static final String WORKER_PREFIX = "condpulse-worker-fly:" + PROJECT + ":";

    interface Channel {
        String password() throws Exception;
        boolean confirmStaged() throws Exception;
    }
    @FunctionalInterface interface WorkerConnector { Connection open(String role, String password) throws Exception; }

    record Config(UUID generation, String sha, String mode, String expectedFingerprint, int maxConnections) {
        Config {
            require(sha != null && sha.matches("[a-f0-9]{40}") && maxConnections > 0, "FLY_WORKER_REQUEST_INVALID");
            require(Set.of("inspect", "apply", "vercel-limit").contains(mode), "FLY_WORKER_OPERATION_INVALID");
            require("vercel-limit".equals(mode) ? generation == null
                : generation != null && !generation.equals(new UUID(0, 0)), "FLY_WORKER_REQUEST_INVALID");
            require("inspect".equals(mode) ? expectedFingerprint == null || expectedFingerprint.isEmpty()
                : expectedFingerprint != null && expectedFingerprint.matches("[a-f0-9]{64}"), "FLY_WORKER_FINGERPRINT_INVALID");
        }
        String role() { return "cp_worker_" + generation.toString().replace("-", ""); }
        String marker() { return WORKER_PREFIX + generation + ":" + sha; }
        @Override public String toString() { return "FlyWorkerConfig[" + mode + "]"; }
    }
    /** Números da margem (sem nomes nem valores sensíveis) e situação do teto da Vercel. */
    record Budget(int maxConnections, long otherBackends, long runtimeLimits, boolean fits) { }
    record Vercel(String state, int limit, long backends, String fingerprint, String code) { }
    record Outcome(boolean eligible, boolean applied, boolean alreadyApplied, String state, String fingerprint, String code,
                   Budget budget, Vercel vercel) {
        Outcome(boolean eligible, boolean applied, boolean alreadyApplied, String state, String fingerprint, String code) {
            this(eligible, applied, alreadyApplied, state, fingerprint, code, null, null);
        }
    }
    private record Role(boolean exists, boolean login, String expiry, String comment, String state, int limit) { }
    static final class Denied extends IllegalStateException {
        final String code; Denied(String code) { super(code); this.code = code; }
    }
    static void require(boolean allowed, String code) { if (!allowed) throw new Denied(code); }
    private FlyWorkerLogin() { }

    public static void main(String[] args) {
        java.util.logging.LogManager.getLogManager().reset();
        LogFactory.setLogCreator(new BootstrapBaseline.QuietLogCreator());
        Outcome outcome = null; Connection owner = null;
        try {
            require(args.length == 0, "FLY_WORKER_ARGUMENTS_FORBIDDEN");
            Map<String, String> env = System.getenv();
            BootstrapBaseline.productionConfig(env);
            require(OWNER_URL.equals(env.get("DATABASE_URL")) && ("postgres." + PROJECT).equals(env.get("DATABASE_USER")), "FLY_WORKER_TARGET_DENIED");
            require(BootstrapManifest.sourceCodes(BootstrapManifest.load()).isEmpty(), "FLY_WORKER_SOURCE_INVALID");
            String mode = env.getOrDefault("CP_FLY_WORKER_OPERATION", "inspect");
            String generation = env.getOrDefault("CP_FLY_GENERATION", ""), sha = env.get("CP_FLY_EXPECTED_COMMIT");
            require("vercel-limit".equals(mode) ? generation.isEmpty() : generation.matches(UUID_PATTERN), "FLY_WORKER_GENERATION_INVALID");
            require(sha != null && sha.equals(env.get("GITHUB_SHA")), "FLY_WORKER_COMMIT_MISMATCH");
            require(CA_PATH.equals(env.get("CP_FLY_CA_PATH")) && Files.isRegularFile(Path.of(CA_PATH), LinkOption.NOFOLLOW_LINKS), "FLY_WORKER_CA_INVALID");
            Config config = new Config(generation.isEmpty() ? null : UUID.fromString(generation), sha, mode,
                env.getOrDefault("CP_FLY_DB_FINGERPRINT", ""), MAX_CONNECTIONS);
            owner = DriverManager.getConnection(OWNER_URL, properties(env.get("DATABASE_USER"), env.get("DATABASE_PASSWORD")));
            requireTls(owner);
            Flyway flyway = Flyway.configure().dataSource(OWNER_URL, env.get("DATABASE_USER"), env.get("DATABASE_PASSWORD"))
                .defaultSchema("platform").schemas("platform", "cp").createSchemas(false).locations("classpath:db/migration")
                .baselineOnMigrate(false).cleanDisabled(true).validateMigrationNaming(true).outOfOrder(false)
                .loggers(BootstrapBaseline.QuietLogCreator.class.getName()).load();
            outcome = "vercel-limit".equals(mode) ? vercelLimit(config, owner) : execute(config, owner, flyway, (role, password) -> {
                Connection connection = DriverManager.getConnection(OWNER_URL, properties(role + "." + PROJECT, password));
                try { requireTls(connection); return connection; }
                catch (Exception error) { connection.close(); throw error; }
            }, new StdioChannel());
        } catch (Denied denied) { outcome = new Outcome(false, false, false, "NOT_ATTEMPTED", "", denied.code); }
        catch (Exception ignored) { outcome = new Outcome(false, false, false, "NOT_ATTEMPTED", "", "FLY_WORKER_PREFLIGHT_FAILED"); }
        finally {
            if (owner != null) try { owner.close(); } catch (Exception ignored) { if (outcome == null) outcome = new Outcome(false, false, false, "UNKNOWN", "", "FLY_WORKER_RESULT_UNKNOWN"); }
            if (outcome == null) outcome = new Outcome(false, false, false, "UNKNOWN", "", "FLY_WORKER_RESULT_UNKNOWN");
        }
        System.out.println(result(outcome)); System.out.flush();
        System.exit(outcome.code().isEmpty() ? 0 : 1);
    }

    static String result(Outcome o) {
        String budget = o.budget() == null ? "null" : String.format("{\"maxConnections\":%d,\"otherBackends\":%d,\"runtimeLimits\":%d,\"workerLimit\":%d,\"margin\":%d,\"fits\":%s}",
            o.budget().maxConnections(), o.budget().otherBackends(), o.budget().runtimeLimits(), CONNECTION_LIMIT, MARGIN, o.budget().fits());
        String vercel = o.vercel() == null ? "null" : String.format("{\"state\":\"%s\",\"limit\":%d,\"backends\":%d,\"fingerprint\":\"%s\",\"code\":\"%s\"}",
            o.vercel().state(), o.vercel().limit(), o.vercel().backends(), o.vercel().fingerprint(), o.vercel().code());
        return String.format("{\"phase\":\"RESULT\",\"eligible\":%s,\"applied\":%s,\"alreadyApplied\":%s,\"state\":\"%s\",\"fingerprint\":\"%s\",\"code\":\"%s\",\"budget\":%s,\"vercel\":%s}",
            o.eligible(), o.applied(), o.alreadyApplied(), o.state(), o.fingerprint(), o.code(), budget, vercel);
    }

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
        p.setProperty("connectTimeout", "5"); p.setProperty("socketTimeout", "20"); p.setProperty("ApplicationName", "condpulse-fly-worker-login");
        p.setProperty("requireAuth", "scram-sha-256"); return p;
    }

    static void requireTls(Connection c) throws Exception {
        try (var q = query(c, "select ssl from pg_stat_ssl where pid=pg_backend_pid()"); var r = q.executeQuery()) {
            require(r.next() && r.getBoolean(1), "FLY_WORKER_DATABASE_TLS_REQUIRED");
        }
    }

    private static boolean lock(Connection owner) throws Exception {
        require(owner.getAutoCommit(), "FLY_WORKER_TRANSACTION_STATE_INVALID"); owner.setReadOnly(true);
        try (var q = query(owner, "select current_user,session_user,pg_try_advisory_lock(?)")) {
            q.setLong(1, BootstrapBaseline.LOCK_KEY);
            try (var r = q.executeQuery()) {
                require(r.next(), "FLY_WORKER_ACTOR_INVALID"); boolean locked = r.getBoolean(3);
                if (locked && !("postgres".equals(r.getString(1)) && "postgres".equals(r.getString(2)))) { unlock(owner); throw new Denied("FLY_WORKER_ACTOR_INVALID"); }
                require("postgres".equals(r.getString(1)) && "postgres".equals(r.getString(2)), "FLY_WORKER_ACTOR_INVALID");
                require(locked, "FLY_WORKER_BUSY");
                return true;
            }
        }
    }
    private static void unlock(Connection owner) {
        try { if (!owner.getAutoCommit()) { owner.rollback(); owner.setAutoCommit(true); } } catch (Exception ignored) { }
        try (var q = query(owner, "select pg_advisory_unlock(?)")) { q.setLong(1, BootstrapBaseline.LOCK_KEY); q.execute(); }
        catch (Exception ignored) { /* Fechar a sessão libera o lock; a validade de 10 minutos limita o login STAGED. */ }
    }

    static Outcome execute(Config config, Connection owner, Flyway flyway, WorkerConnector worker, Channel channel) {
        String fingerprint = ""; boolean locked = false, attempted = false; Budget budget = null; Vercel vercel = null;
        try {
            require(!"vercel-limit".equals(config.mode()), "FLY_WORKER_OPERATION_INVALID");
            locked = lock(owner);
            String version = foundation(owner, flyway);
            Role role = role(owner, config);
            List<String> others = others(owner, config);
            if (role.exists()) verifyRole(owner, config.role());
            fingerprint = fingerprint(config, version, role, others);
            budget = budget(owner, config.maxConnections()); vercel = vercel(owner);
            if ("READY".equals(role.state())) {
                require(role.login() && "infinity".equals(role.expiry()), "FLY_WORKER_READY_ROLE_DIVERGED");
                if ("apply".equals(config.mode())) require(fingerprint.equals(config.expectedFingerprint()), "FLY_WORKER_INSPECTION_CHANGED");
                return new Outcome(true, false, true, "READY", fingerprint, "", budget, vercel);
            }
            require(budget.fits(), "FLY_WORKER_BUDGET_INSUFFICIENT");
            if ("inspect".equals(config.mode())) return new Outcome(true, false, false, role.state(), fingerprint, "", budget, vercel);
            require(fingerprint.equals(config.expectedFingerprint()), "FLY_WORKER_INSPECTION_CHANGED");
            String password = channel.password();
            require(password != null && password.matches("[A-Za-z0-9_-]{48}"), "FLY_WORKER_PASSWORD_INVALID");
            String verifier = PasswordUtil.encodeScramSha256(password.toCharArray());
            attempted = true; stage(owner, config, role.exists(), verifier); verifyRole(owner, config.role());
            try (Connection login = worker.open(config.role(), password)) { verifyLogin(login, config.role()); }
            require(channel.confirmStaged(), "FLY_WORKER_SECRETS_NOT_CONFIRMED");
            verifyRole(owner, config.role()); ready(owner, config);
            Role finalRole = role(owner, config);
            require("READY".equals(finalRole.state()) && finalRole.login() && "infinity".equals(finalRole.expiry()), "FLY_WORKER_READBACK_FAILED");
            return new Outcome(true, true, false, "READY", fingerprint(config, version, finalRole, others(owner, config)), "", budget, vercel);
        } catch (Exception failure) {
            if (attempted) {
                boolean disabled = disable(owner, config);
                return new Outcome(false, false, false, disabled ? "DISABLED" : "UNKNOWN", fingerprint,
                    disabled ? "FLY_WORKER_PARTIAL_RECONCILE_REQUIRED" : "FLY_WORKER_RESULT_UNKNOWN", budget, vercel);
            }
            return new Outcome(false, false, false, "NOT_ATTEMPTED", fingerprint,
                failure instanceof Denied denied ? denied.code : "FLY_WORKER_INSPECTION_FAILED", budget, vercel);
        } finally {
            if (locked) unlock(owner);
        }
    }

    private static String fingerprint(Config c, String version, Role r, List<String> others) throws Exception {
        StringBuilder body = new StringBuilder("fly-worker-v1|").append(PROJECT).append('|').append(APP).append('|').append(c.generation())
            .append('|').append(c.sha()).append('|').append(version).append('|').append(c.maxConnections()).append('|').append(r.exists())
            .append('|').append(r.login()).append('|').append(r.expiry()).append('|').append(r.comment()).append('|').append(r.limit());
        for (String other : others) body.append('\n').append(other);
        return sha256(body.toString());
    }
    private static String sha256(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Histórico oficial (BASELINE 002 + SQL contíguas), nada pendente, dono/RLS e o grupo cp_worker sem privilégio estrutural. */
    private static String foundation(Connection c, Flyway flyway) throws Exception {
        var versions = new ArrayList<Integer>();
        try (var q = query(c, "select version,type,success,checksum from platform.flyway_schema_history order by installed_rank"); var r = q.executeQuery()) {
            while (r.next()) {
                int version = new java.math.BigInteger(r.getString(1)).intValueExact(); int index = versions.size();
                require(r.getBoolean(3) && (index == 0 ? version == 2 && "BASELINE".equals(r.getString(2))
                    : version == index + 2 && "SQL".equals(r.getString(2)) && r.getObject(4) != null), "FLY_WORKER_HISTORY_INCOMPATIBLE");
                versions.add(version);
            }
        }
        // O worker precisa do outbox com a caixa interna (V015) e da fila de espera (V023).
        require(versions.size() >= 4 && versions.get(versions.size() - 1) >= 23, "FLY_WORKER_HISTORY_INCOMPATIBLE");
        flyway.validate(); require(flyway.info().pending().length == 0, "FLY_WORKER_MIGRATIONS_PENDING");
        try (var q = query(c, "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname in ('platform','cp') and c.relkind in ('r','p') and c.relname<>'flyway_schema_history' and (pg_get_userbyid(c.relowner)<>'postgres' or not c.relrowsecurity or not c.relforcerowsecurity)"); var r = q.executeQuery()) {
            require(r.next() && r.getLong(1) == 0, "FLY_WORKER_SCHEMA_RLS_DIVERGED");
        }
        try (var q = query(c, "select count(*) from pg_namespace where nspname in ('platform','cp') and pg_get_userbyid(nspowner)<>'postgres'"); var r = q.executeQuery()) {
            require(r.next() && r.getLong(1) == 0, "FLY_WORKER_SCHEMA_OWNER_DIVERGED");
        }
        try (var q = query(c, "select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname in ('platform','cp') and (pg_get_userbyid(p.proowner)<>'postgres' or p.prosecdef)"); var r = q.executeQuery()) {
            require(r.next() && r.getLong(1) == 0, "FLY_WORKER_FUNCTION_OWNER_DIVERGED");
        }
        // Grupo do worker: sem login nem atributo privilegiado e sem herdar outro grupo (nunca cp_api).
        try (var q = query(c, "select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication or rolcanlogin,(select count(*) from pg_auth_members m where m.member=pg_roles.oid) from pg_roles where rolname='cp_worker'"); var r = q.executeQuery()) {
            require(r.next() && !r.getBoolean(1) && r.getLong(2) == 0, "FLY_WORKER_GROUP_DIVERGED");
        }
        noDdlOrOwnership(c, "cp_worker");
        try (var q = query(c, "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace cross join unnest(array['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER','MAINTAIN']) p(privilege) where n.nspname in ('platform','cp') and c.relkind in ('r','p','v','m') and ((p.privilege in ('TRUNCATE','REFERENCES','TRIGGER','MAINTAIN') and has_table_privilege('cp_worker',c.oid,p.privilege)) or has_table_privilege('cp_worker',c.oid,p.privilege||' WITH GRANT OPTION'))"); var r = q.executeQuery()) {
            require(r.next() && r.getLong(1) == 0, "FLY_WORKER_GROUP_GRANTS_UNSAFE");
        }
        return Integer.toString(versions.get(versions.size() - 1));
    }

    private static Role role(Connection c, Config config) throws Exception {
        try (var q = query(c, "select rolcanlogin,rolvaliduntil::text,shobj_description(oid,'pg_authid'),rolconnlimit from pg_roles where rolname=?")) {
            q.setString(1, config.role());
            try (var r = q.executeQuery()) {
                if (!r.next()) return new Role(false, false, "", "", "ABSENT", 0);
                String comment = r.getString(3);
                // Mesma geração: marker do commit atual, ou READY de um commit anterior (lido sem escrita).
                String generationPrefix = WORKER_PREFIX + config.generation() + ":";
                boolean own = comment != null && comment.startsWith(config.marker() + ":");
                boolean earlierReady = comment != null && comment.startsWith(generationPrefix)
                    && comment.substring(generationPrefix.length()).matches("[a-f0-9]{40}:READY");
                require(own || earlierReady, "FLY_WORKER_ROLE_OWNERSHIP_CONFLICT");
                String state = comment.substring(generationPrefix.length() + 41);
                require(Set.of("STAGED", "DISABLED", "READY").contains(state), "FLY_WORKER_ROLE_STATE_INVALID");
                return new Role(true, r.getBoolean(1), r.getString(2), comment, state, r.getInt(4));
            }
        }
    }

    /**
     * Nenhum outro login do worker: outra geração da Fly ou cp_worker_* desconhecida (por exemplo do worker-login.sql)
     * bloqueia, assim como qualquer membro de cp_worker fora desta geração. Logins runtime (Vercel/API na Fly) só entram
     * pelo teto, que precisa ser finito.
     */
    private static List<String> others(Connection c, Config config) throws Exception {
        List<String> result = new ArrayList<>();
        try (var q = query(c, "select rolname,coalesce(shobj_description(oid,'pg_authid'),'') from pg_roles where rolname like 'cp\\_worker\\_%' escape '\\' and rolname<>? order by rolname")) {
            q.setString(1, config.role());
            try (var r = q.executeQuery()) {
                while (r.next()) {
                    require(!r.getString(2).startsWith(WORKER_PREFIX), "FLY_WORKER_OTHER_GENERATION_EXISTS");
                    throw new Denied("FLY_WORKER_UNKNOWN_WORKER_ROLE");
                }
            }
        }
        // A única outra associação tolerada é a de bootstrap que o PostgreSQL 16+ cria para quem criou o grupo:
        // postgres com ADMIN, sem INHERIT nem SET (não herda nem assume os privilégios de cp_worker).
        try (var q = query(c, "select count(*) from pg_auth_members m join pg_roles g on g.oid=m.roleid join pg_roles u on u.oid=m.member where g.rolname='cp_worker' and u.rolname<>? and not (u.rolname='postgres' and m.admin_option and not m.inherit_option and not m.set_option)")) {
            q.setString(1, config.role());
            try (var r = q.executeQuery()) { require(r.next() && r.getLong(1) == 0, "FLY_WORKER_UNKNOWN_GROUP_MEMBER"); }
        }
        try (var q = query(c, "select rolname,coalesce(shobj_description(oid,'pg_authid'),''),rolconnlimit,rolcanlogin from pg_roles where rolname like 'cp\\_runtime\\_%' escape '\\' order by rolname")) {
            try (var r = q.executeQuery()) {
                while (r.next()) {
                    String comment = r.getString(2);
                    require(comment.matches(VERCEL_MARKER) || comment.matches(FLY_API_MARKER), "FLY_WORKER_UNKNOWN_RUNTIME_ROLE");
                    require(r.getInt(3) >= 0, "FLY_WORKER_BUDGET_UNBOUNDED");
                    result.add(r.getString(1) + "|" + comment + "|" + r.getInt(3) + "|" + r.getBoolean(4));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Leitura da margem: backends que não são runtime nem worker + tetos runtime + teto do worker + 20 <= 60. */
    static Budget budget(Connection c, int expectedMax) throws Exception {
        try (var q = query(c, "select current_setting('max_connections')::int,(select count(*) from pg_stat_activity a where a.usename is null or (a.usename not like 'cp\\_runtime\\_%' escape '\\' and a.usename not like 'cp\\_worker\\_%' escape '\\')),(select coalesce(sum(rolconnlimit),0) from pg_roles where rolname like 'cp\\_runtime\\_%' escape '\\'),(select count(*) from pg_roles where rolname like 'cp\\_runtime\\_%' escape '\\' and rolconnlimit<0)");
             var r = q.executeQuery()) {
            require(r.next(), "FLY_WORKER_BUDGET_INSUFFICIENT");
            int max = r.getInt(1); long other = r.getLong(2), runtime = r.getLong(3);
            require(r.getLong(4) == 0, "FLY_WORKER_BUDGET_UNBOUNDED");
            return new Budget(max, other, runtime, max == expectedMax && other + runtime + CONNECTION_LIMIT + MARGIN <= expectedMax);
        }
    }

    /** Login READY da Vercel (marker próprio): estado do teto e backends atuais, sem nome nem marker na saída. */
    private static Vercel vercel(Connection c) throws Exception {
        List<String[]> found = new ArrayList<>();
        try (var q = query(c, "select r.rolname,coalesce(shobj_description(r.oid,'pg_authid'),''),r.rolconnlimit,r.rolcanlogin,coalesce(r.rolvaliduntil::text,''),(select count(*) from pg_stat_activity a where a.usename=r.rolname) from pg_roles r where r.rolname like 'cp\\_runtime\\_%' escape '\\' order by r.rolname");
             var r = q.executeQuery()) {
            while (r.next()) if (r.getString(2).matches(VERCEL_MARKER))
                found.add(new String[] {r.getString(1), r.getString(2), Integer.toString(r.getInt(3)), Boolean.toString(r.getBoolean(4)), r.getString(5), Long.toString(r.getLong(6))});
        }
        if (found.isEmpty()) return new Vercel("ABSENT", 0, 0, "", "");
        if (found.size() > 1) return new Vercel("AMBIGUOUS", 0, 0, "", "FLY_WORKER_VERCEL_ROLE_AMBIGUOUS");
        String[] v = found.get(0); int limit = Integer.parseInt(v[2]); long backends = Long.parseLong(v[5]);
        String fingerprint = sha256("fly-worker-vercel-limit-v1|" + PROJECT + "|" + v[0] + "|" + v[1] + "|" + v[2] + "|" + v[3] + "|" + v[4]);
        if (limit == VERCEL_TO) return new Vercel("REDUCED", limit, backends, fingerprint, "");
        String code = !v[1].endsWith(":READY") || !"true".equals(v[3]) || !"infinity".equals(v[4]) ? "FLY_WORKER_VERCEL_ROLE_NOT_READY"
            : limit != VERCEL_FROM ? "FLY_WORKER_VERCEL_LIMIT_UNEXPECTED"
            : backends > VERCEL_TO ? "FLY_WORKER_VERCEL_BACKENDS_EXCEED" : "";
        try { verifyRuntimeMembership(c, v[0]); } catch (Denied denied) { if (code.isEmpty()) code = denied.code; }
        return new Vercel(code.isEmpty() ? "ELIGIBLE" : "BLOCKED", limit, backends, fingerprint, code);
    }
    private static void verifyRuntimeMembership(Connection c, String role) throws Exception {
        try (var q = query(c, "select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication or not rolinherit from pg_roles where rolname=?")) {
            q.setString(1, role); try (var r = q.executeQuery()) { require(r.next() && !r.getBoolean(1), "FLY_WORKER_VERCEL_ROLE_UNSAFE"); }
        }
        try (var q = query(c, "select g.rolname,m.admin_option,m.inherit_option,m.set_option from pg_auth_members m join pg_roles u on u.oid=m.member join pg_roles g on g.oid=m.roleid where u.rolname=?")) {
            q.setString(1, role);
            try (var r = q.executeQuery()) {
                require(r.next() && "cp_api".equals(r.getString(1)) && !r.getBoolean(2) && r.getBoolean(3) && !r.getBoolean(4) && !r.next(), "FLY_WORKER_VERCEL_ROLE_UNSAFE");
            }
        }
    }

    /**
     * Operação separada, com ACK próprio: CONNECTION LIMIT do login READY da Vercel 16 -> 4. Só esse atributo muda;
     * 4 já aplicado é noop. Exige o fingerprint da inspeção e no máximo 4 backends atuais desse login.
     */
    static Outcome vercelLimit(Config config, Connection owner) {
        boolean locked = false;
        try {
            require("vercel-limit".equals(config.mode()), "FLY_WORKER_OPERATION_INVALID");
            locked = lock(owner);
            Vercel before = vercel(owner);
            require(!"ABSENT".equals(before.state()), "FLY_WORKER_VERCEL_ROLE_ABSENT");
            require(before.fingerprint().equals(config.expectedFingerprint()), "FLY_WORKER_INSPECTION_CHANGED");
            if ("REDUCED".equals(before.state())) return new Outcome(true, false, true, "REDUCED", before.fingerprint(), "", budget(owner, config.maxConnections()), before);
            require("ELIGIBLE".equals(before.state()), before.code().isEmpty() ? "FLY_WORKER_VERCEL_NOT_ELIGIBLE" : before.code());
            String role = null, comment = null;
            try (var q = query(owner, "select rolname,shobj_description(oid,'pg_authid') from pg_roles where rolname like 'cp\\_runtime\\_%' escape '\\' and rolconnlimit=?")) {
                q.setInt(1, VERCEL_FROM);
                try (var r = q.executeQuery()) {
                    while (r.next()) if (r.getString(2) != null && r.getString(2).matches(VERCEL_MARKER)) { require(role == null, "FLY_WORKER_VERCEL_ROLE_AMBIGUOUS"); role = r.getString(1); comment = r.getString(2); }
                }
            }
            require(role != null && role.matches("cp_runtime_[a-f0-9]{32}"), "FLY_WORKER_VERCEL_ROLE_ABSENT");
            owner.setReadOnly(false); owner.setAutoCommit(false);
            try {
                sql(owner, "ALTER ROLE " + role + " CONNECTION LIMIT " + VERCEL_TO);
                owner.commit();
            } catch (Exception error) { owner.rollback(); throw error; } finally { owner.setAutoCommit(true); }
            Vercel after = vercel(owner);
            try (var q = query(owner, "select rolconnlimit,rolcanlogin,rolvaliduntil::text,shobj_description(oid,'pg_authid') from pg_roles where rolname=?")) {
                q.setString(1, role);
                try (var r = q.executeQuery()) {
                    require(r.next() && r.getInt(1) == VERCEL_TO && r.getBoolean(2) && "infinity".equals(r.getString(3)) && comment.equals(r.getString(4))
                        && "REDUCED".equals(after.state()), "FLY_WORKER_VERCEL_READBACK_FAILED");
                }
            }
            return new Outcome(true, true, false, "REDUCED", after.fingerprint(), "", budget(owner, config.maxConnections()), after);
        } catch (Exception failure) {
            return new Outcome(false, false, false, "NOT_ATTEMPTED", "", failure instanceof Denied denied ? denied.code : "FLY_WORKER_VERCEL_LIMIT_FAILED");
        } finally {
            if (locked) unlock(owner);
        }
    }

    static void verifyRole(Connection c, String role) throws Exception {
        try (var q = query(c, "select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication or not rolinherit,rolconnlimit from pg_roles where rolname=?")) {
            q.setString(1, role);
            try (var r = q.executeQuery()) { require(r.next() && !r.getBoolean(1) && r.getInt(2) == CONNECTION_LIMIT, "FLY_WORKER_ROLE_UNSAFE"); }
        }
        try (var q = query(c, "select g.rolname,m.admin_option,m.inherit_option,m.set_option from pg_auth_members m join pg_roles u on u.oid=m.member join pg_roles g on g.oid=m.roleid where u.rolname=?")) {
            q.setString(1, role);
            try (var r = q.executeQuery()) {
                require(r.next() && "cp_worker".equals(r.getString(1)) && !r.getBoolean(2) && r.getBoolean(3) && !r.getBoolean(4) && !r.next(), "FLY_WORKER_MEMBERSHIP_UNSAFE");
            }
        }
        noDdlOrOwnership(c, role);
        try (var q = query(c, "with target as (select oid from pg_roles where rolname=?) select (select count(*) from pg_class c cross join lateral aclexplode(c.relacl) a where a.grantee=(select oid from target))+(select count(*) from pg_attribute t cross join lateral aclexplode(t.attacl) a where a.grantee=(select oid from target))+(select count(*) from pg_namespace n cross join lateral aclexplode(n.nspacl) a where a.grantee=(select oid from target))+(select count(*) from pg_proc p cross join lateral aclexplode(p.proacl) a where a.grantee=(select oid from target))+(select count(*) from pg_database d cross join lateral aclexplode(d.datacl) a where a.grantee=(select oid from target))")) {
            q.setString(1, role);
            try (var r = q.executeQuery()) { require(r.next() && r.getLong(1) == 0, "FLY_WORKER_DIRECT_GRANT_FORBIDDEN"); }
        }
        // Privilégio efetivo idêntico ao de cp_worker em tabelas, colunas, funções e schemas da aplicação.
        try (var q = query(c, "select (select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace cross join unnest(array['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER','MAINTAIN']) p(privilege) where n.nspname in ('platform','cp') and c.relkind in ('r','p','v','m') and (has_table_privilege(?,c.oid,p.privilege)<>has_table_privilege('cp_worker',c.oid,p.privilege) or has_table_privilege(?,c.oid,p.privilege||' WITH GRANT OPTION')))"
                + "+(select count(*) from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace cross join unnest(array['SELECT','INSERT','UPDATE','REFERENCES']) p(privilege) where n.nspname in ('platform','cp') and c.relkind in ('r','p','v','m') and a.attnum>0 and not a.attisdropped and has_column_privilege(?,c.oid,a.attnum,p.privilege)<>has_column_privilege('cp_worker',c.oid,a.attnum,p.privilege))"
                + "+(select count(*) from pg_proc f join pg_namespace n on n.oid=f.pronamespace where n.nspname in ('platform','cp') and has_function_privilege(?,f.oid,'EXECUTE')<>has_function_privilege('cp_worker',f.oid,'EXECUTE'))"
                + "+(select count(*) from pg_namespace n cross join unnest(array['USAGE','CREATE']) p(privilege) where n.nspname in ('platform','cp') and has_schema_privilege(?,n.oid,p.privilege)<>has_schema_privilege('cp_worker',n.oid,p.privilege))")) {
            for (int index = 1; index <= 5; index++) q.setString(index, role);
            try (var r = q.executeQuery()) { require(r.next() && r.getLong(1) == 0, "FLY_WORKER_GRANTS_DIVERGED"); }
        }
    }
    private static void noDdlOrOwnership(Connection c, String role) throws Exception {
        try (var q = query(c, "select (select count(*) from pg_shdepend where refclassid='pg_authid'::regclass and refobjid=(select oid from pg_roles where rolname=?) and deptype='o')+(select count(*) from pg_namespace where nspname !~ '^pg_' and nspname<>'information_schema' and has_schema_privilege(?,oid,'CREATE'))")) {
            q.setString(1, role); q.setString(2, role);
            try (var r = q.executeQuery()) { require(r.next() && r.getLong(1) == 0, "FLY_WORKER_DDL_OR_OWNERSHIP_UNSAFE"); }
        }
    }
    /** Prova do login: identidade exata, RLS ligada, só cp_worker (nunca cp_api) e nenhuma tarefa visível sem condomínio. */
    static void verifyLogin(Connection c, String role) throws Exception {
        try (var q = query(c, "select current_user,session_user,current_setting('row_security'),pg_has_role(current_user,'cp_worker','USAGE'),pg_has_role(current_user,'cp_api','MEMBER') or pg_has_role(current_user,'cp_onboarding','MEMBER')"); var r = q.executeQuery()) {
            require(r.next() && role.equals(r.getString(1)) && role.equals(r.getString(2)) && "on".equals(r.getString(3)) && r.getBoolean(4) && !r.getBoolean(5), "FLY_WORKER_LOGIN_PROOF_FAILED");
        }
        verifyRole(c, role);
        try (var q = query(c, "select count(*) from cp.outbox"); var r = q.executeQuery()) { require(r.next() && r.getLong(1) == 0, "FLY_WORKER_RLS_PROOF_FAILED"); }
    }

    private static void stage(Connection c, Config config, boolean exists, String verifier) throws Exception {
        c.setReadOnly(false); c.setAutoCommit(false);
        try {
            if (!exists) sql(c, "CREATE ROLE " + config.role() + " NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT " + CONNECTION_LIMIT);
            sql(c, "GRANT cp_worker TO " + config.role() + " WITH ADMIN FALSE"); sql(c, "GRANT cp_worker TO " + config.role() + " WITH INHERIT TRUE"); sql(c, "GRANT cp_worker TO " + config.role() + " WITH SET FALSE");
            Instant expiry;
            try (var q = query(c, "select clock_timestamp()+interval '10 minutes'"); var r = q.executeQuery()) { require(r.next(), "FLY_WORKER_DATABASE_TIME_INVALID"); expiry = r.getTimestamp(1).toInstant(); }
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
            c.setAutoCommit(false); sql(c, "ALTER ROLE " + config.role() + " NOLOGIN PASSWORD NULL VALID UNTIL '1970-01-01T00:00:00Z'");
            sql(c, "COMMENT ON ROLE " + config.role() + " IS " + literal(config.marker() + ":DISABLED")); c.commit(); c.setAutoCommit(true); return true;
        } catch (Exception ignored) { try { c.rollback(); c.setAutoCommit(true); } catch (Exception closed) { } return false; }
    }
    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static PreparedStatement query(Connection c, String sql) throws Exception { var q = c.prepareStatement(sql); q.setQueryTimeout(10); return q; }
    private static void sql(Connection c, String sql) throws Exception { try (var q = c.createStatement()) { q.setQueryTimeout(10); q.execute(sql); } }
}
