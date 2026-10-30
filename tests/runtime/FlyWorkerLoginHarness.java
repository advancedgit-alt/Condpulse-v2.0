package br.com.condpulse.bootstrap;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.logging.LogFactory;

/** Fixture descartável de CI para FlyWorkerLogin. Nenhum main/flag de ambiente a seleciona em produção. */
public final class FlyWorkerLoginHarness {
    private static final String USER = "postgres", PASSWORD = "condpulse_ci_only", SHA = "b".repeat(40);
    private static final List<String> SOURCES = List.of("V001__foundation.sql", "V002__roles_and_rls.sql");
    private static final List<String> createdRoles = new ArrayList<>();
    private static String host, port, database, stage = "guard";
    private static int maxConnections, logins;

    private static void require(boolean value) { if (!value) throw new AssertionError("SYNTHETIC_FLY_WORKER_ASSERTION_FAILED"); }
    private static String url(String name) { return "jdbc:postgresql://" + host + ":" + port + "/" + name + "?sslmode=disable"; }
    private static Connection owner() throws Exception { return DriverManager.getConnection(url(database), USER, PASSWORD); }
    private static void sql(Connection c, String statement) throws Exception { try (var q = c.createStatement()) { q.setQueryTimeout(15); q.execute(statement); } }
    private static void sql(String statement) throws Exception { try (var c = owner()) { sql(c, statement); } }
    private static String text(Connection c, String statement) throws Exception {
        try (var q = c.createStatement(); var r = q.executeQuery(statement)) { require(r.next()); return r.getString(1); }
    }
    private static String source(String name) throws Exception {
        try (var stream = FlyWorkerLoginHarness.class.getResourceAsStream("/db/migration/" + name)) {
            require(stream != null); return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static Flyway flyway() {
        return Flyway.configure().dataSource(url(database), USER, PASSWORD).defaultSchema("platform").schemas("platform", "cp")
            .createSchemas(false).locations("classpath:db/migration").baselineVersion("002").baselineOnMigrate(false)
            .cleanDisabled(true).validateMigrationNaming(true).outOfOrder(false).loggers(BootstrapBaseline.QuietLogCreator.class.getName()).load();
    }
    private static String password() {
        byte[] bytes = new byte[36]; new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static FlyWorkerLogin.Config config(UUID generation, String mode, String fingerprint) {
        return new FlyWorkerLogin.Config(generation, SHA, mode, fingerprint, maxConnections);
    }
    private static String roleOf(UUID generation) { return "cp_worker_" + generation.toString().replace("-", ""); }
    private static FlyWorkerLogin.Outcome run(FlyWorkerLogin.Config config, String secret, boolean commit) throws Exception {
        String role = config.role(); if (!createdRoles.contains(role)) createdRoles.add(role);
        try (var c = owner()) {
            return FlyWorkerLogin.execute(config, c, flyway(), (name, value) -> {
                require(role.equals(name) && secret.equals(value));
                Properties p = new Properties(); p.setProperty("user", name); p.setProperty("password", value); p.setProperty("requireAuth", "scram-sha-256");
                logins++; return DriverManager.getConnection(url(database), p);
            }, new FlyWorkerLogin.Channel() {
                @Override public String password() { return secret; }
                @Override public boolean confirmStaged() throws Exception {
                    // Durante a gravação na Fly o login está STAGED, com validade curta e teto 2.
                    try (var o = owner(); var q = o.prepareStatement("select rolcanlogin,rolvaliduntil<=clock_timestamp()+interval '11 minutes',rolconnlimit,shobj_description(oid,'pg_authid') from pg_roles where rolname=?")) {
                        q.setString(1, role); try (var r = q.executeQuery()) {
                            require(r.next() && r.getBoolean(1) && r.getBoolean(2) && r.getInt(3) == 2 && (config.marker() + ":STAGED").equals(r.getString(4)));
                        }
                    }
                    return commit;
                }
            });
        }
    }
    private static FlyWorkerLogin.Outcome limit(String fingerprint) throws Exception {
        try (var c = owner()) { return FlyWorkerLogin.vercelLimit(new FlyWorkerLogin.Config(null, SHA, "vercel-limit", fingerprint, maxConnections), c); }
    }
    private static void role(String role, boolean login, String state) throws Exception {
        try (var c = owner(); var q = c.prepareStatement("select rolcanlogin,rolvaliduntil::text,rolconnlimit,shobj_description(oid,'pg_authid'),(select count(*) from pg_auth_members m where m.member=r.oid),(select count(*) from pg_auth_members m join pg_roles g on g.oid=m.roleid where m.member=r.oid and g.rolname='cp_worker') from pg_roles r where rolname=?")) {
            q.setString(1, role); try (var r = q.executeQuery()) {
                require(r.next() && r.getBoolean(1) == login && r.getInt(3) == 2 && r.getString(4).endsWith(":" + state) && r.getLong(5) == 1 && r.getLong(6) == 1);
                if ("READY".equals(state)) require("infinity".equals(r.getString(2)));
            }
        }
    }
    private static boolean exists(String role) throws Exception {
        try (var c = owner(); var q = c.prepareStatement("select count(*) from pg_roles where rolname=?")) {
            q.setString(1, role); try (var r = q.executeQuery()) { require(r.next()); return r.getLong(1) == 1; }
        }
    }
    private static int connlimit(String role) throws Exception {
        try (var c = owner(); var q = c.prepareStatement("select rolconnlimit from pg_roles where rolname=?")) {
            q.setString(1, role); try (var r = q.executeQuery()) { require(r.next()); return r.getInt(1); }
        }
    }
    private static void drop(String role) throws Exception {
        require(role.matches("cp_(runtime|worker)_[a-f0-9]{32}"));
        try (var c = owner()) { if (exists(role)) sql(c, "DROP ROLE " + role); }
    }
    /** Login runtime sintético com marker da Vercel (READY, LOGIN, validade infinita, só cp_api). */
    private static String runtimeRole(String markerPrefix, int limit, String password) throws Exception {
        UUID generation = UUID.randomUUID(); String role = "cp_runtime_" + generation.toString().replace("-", "");
        createdRoles.add(role);
        try (var c = owner()) {
            sql(c, "CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "' VALID UNTIL 'infinity' CONNECTION LIMIT " + limit);
            sql(c, "GRANT cp_api TO " + role + " WITH ADMIN FALSE"); sql(c, "GRANT cp_api TO " + role + " WITH INHERIT TRUE"); sql(c, "GRANT cp_api TO " + role + " WITH SET FALSE");
            sql(c, "COMMENT ON ROLE " + role + " IS '" + markerPrefix + ":rpnrqfrfwywglvyykgrg:" + generation + ":" + "c".repeat(40) + ":READY'");
        }
        return role;
    }

    private static void scenarios() throws Exception {
        UUID generation = UUID.randomUUID(); String role = roleOf(generation);
        stage = "inspect";
        var inspected = run(config(generation, "inspect", ""), "", false);
        require(inspected.eligible() && "ABSENT".equals(inspected.state()) && inspected.code().isEmpty() && inspected.fingerprint().matches("[a-f0-9]{64}"));
        require(inspected.budget() != null && inspected.budget().fits() && inspected.budget().runtimeLimits() == 0 && "ABSENT".equals(inspected.vercel().state()));
        require(!exists(role) && logins == 0);

        stage = "stale-fingerprint";
        var stale = run(config(generation, "apply", "0".repeat(64)), password(), true);
        require("FLY_WORKER_INSPECTION_CHANGED".equals(stale.code()) && !exists(role) && logins == 0);

        stage = "abort-disables";
        String first = password();
        var aborted = run(config(generation, "apply", inspected.fingerprint()), first, false);
        require("FLY_WORKER_PARTIAL_RECONCILE_REQUIRED".equals(aborted.code()) && "DISABLED".equals(aborted.state()) && logins == 1);
        role(role, false, "DISABLED");
        boolean denied = false;
        try (var ignored = DriverManager.getConnection(url(database), role, first)) { /* NOLOGIN precisa recusar. */ }
        catch (java.sql.SQLException expected) { denied = true; }
        require(denied);

        stage = "recover-ready";
        var recover = run(config(generation, "inspect", ""), "", false);
        require(recover.eligible() && "DISABLED".equals(recover.state()) && !recover.fingerprint().equals(inspected.fingerprint()));
        String second = password();
        var applied = run(config(generation, "apply", recover.fingerprint()), second, true);
        require(applied.applied() && "READY".equals(applied.state()) && applied.code().isEmpty() && logins == 2);
        role(role, true, "READY");
        try (var login = DriverManager.getConnection(url(database), role, second)) {
            FlyWorkerLogin.verifyLogin(login, role);
            require("false".equals(text(login, "select (has_schema_privilege(current_user,'cp','CREATE') or pg_has_role(current_user,'cp_api','MEMBER') or pg_has_role(current_user,'cp_onboarding','MEMBER'))::text")));
            require("true".equals(text(login, "select pg_has_role(current_user,'cp_worker','USAGE')::text")));
        }

        stage = "ready-replay";
        var ready = run(config(generation, "inspect", ""), "", false);
        require(ready.alreadyApplied() && "READY".equals(ready.state()) && ready.code().isEmpty() && logins == 2);
        var replay = run(config(generation, "apply", ready.fingerprint()), "", true);
        require(replay.alreadyApplied() && !replay.applied() && replay.code().isEmpty() && logins == 2);

        stage = "earlier-commit-ready";
        String current = FlyWorkerLogin.WORKER_PREFIX + generation + ":" + SHA, earlier = FlyWorkerLogin.WORKER_PREFIX + generation + ":" + "e".repeat(40);
        sql("COMMENT ON ROLE " + role + " IS '" + earlier + ":READY'");
        try {
            var earlierInspect = run(config(generation, "inspect", ""), "", false);
            require(earlierInspect.alreadyApplied() && "READY".equals(earlierInspect.state()) && earlierInspect.code().isEmpty());
            sql("COMMENT ON ROLE " + role + " IS '" + earlier + ":STAGED'");
            require("FLY_WORKER_ROLE_OWNERSHIP_CONFLICT".equals(run(config(generation, "inspect", ""), "", false).code()) && logins == 2);
        } finally { sql("COMMENT ON ROLE " + role + " IS '" + current + ":READY'"); }
        role(role, true, "READY");

        stage = "direct-grant-denied";
        sql("GRANT SELECT ON platform.tenants TO " + role);
        try { require("FLY_WORKER_DIRECT_GRANT_FORBIDDEN".equals(run(config(generation, "inspect", ""), "", false).code())); }
        finally { sql("REVOKE SELECT ON platform.tenants FROM " + role); }

        stage = "membership-denied";
        sql("GRANT cp_api TO " + role);
        try { require("FLY_WORKER_MEMBERSHIP_UNSAFE".equals(run(config(generation, "inspect", ""), "", false).code())); }
        finally { sql("REVOKE cp_api FROM " + role); }

        stage = "group-drift";
        sql("GRANT TRUNCATE ON cp.outbox TO cp_worker");
        try { require("FLY_WORKER_GROUP_GRANTS_UNSAFE".equals(run(config(generation, "inspect", ""), "", false).code())); }
        finally { sql("REVOKE TRUNCATE ON cp.outbox FROM cp_worker"); }

        stage = "other-fly-generation";
        UUID next = UUID.randomUUID();
        require("FLY_WORKER_OTHER_GENERATION_EXISTS".equals(run(config(next, "inspect", ""), "", false).code()));
        drop(role);

        stage = "unknown-worker-login";
        String unknown = roleOf(UUID.randomUUID()); createdRoles.add(unknown);
        sql("CREATE ROLE " + unknown + " NOLOGIN CONNECTION LIMIT 6");
        require("FLY_WORKER_UNKNOWN_WORKER_ROLE".equals(run(config(next, "inspect", ""), "", false).code()));
        drop(unknown);
        String member = "cp_ci_fly_worker_member"; sql("CREATE ROLE " + member + " NOLOGIN IN ROLE cp_worker");
        try { require("FLY_WORKER_UNKNOWN_GROUP_MEMBER".equals(run(config(next, "inspect", ""), "", false).code())); }
        finally { sql("DROP ROLE " + member); }

        stage = "budget";
        String vercelPassword = "synthetic_vercel_only";
        String vercel = runtimeRole("condpulse-runtime", 16, vercelPassword);
        String flyApi = runtimeRole("condpulse-runtime-fly", 4, "synthetic_fly_api_only");
        var coexist = run(config(next, "inspect", ""), "", false);
        require(coexist.eligible() && coexist.budget().runtimeLimits() == 20 && coexist.code().isEmpty());
        require("ELIGIBLE".equals(coexist.vercel().state()) && coexist.vercel().limit() == 16 && coexist.vercel().fingerprint().matches("[a-f0-9]{64}"));
        // Teto da API na Fly empurrado até não sobrar margem para os 2 do worker.
        sql("ALTER ROLE " + flyApi + " CONNECTION LIMIT " + (maxConnections - 20 - 16 - 1));
        var noRoom = run(config(next, "inspect", ""), "", false);
        require("FLY_WORKER_BUDGET_INSUFFICIENT".equals(noRoom.code()) && noRoom.budget() != null && !noRoom.budget().fits() && !exists(roleOf(next)));
        sql("ALTER ROLE " + flyApi + " CONNECTION LIMIT -1");
        require("FLY_WORKER_BUDGET_UNBOUNDED".equals(run(config(next, "inspect", ""), "", false).code()));
        sql("ALTER ROLE " + flyApi + " CONNECTION LIMIT 4");
        String stranger = "cp_runtime_" + UUID.randomUUID().toString().replace("-", ""); createdRoles.add(stranger);
        sql("CREATE ROLE " + stranger + " NOLOGIN CONNECTION LIMIT 1");
        require("FLY_WORKER_UNKNOWN_RUNTIME_ROLE".equals(run(config(next, "inspect", ""), "", false).code()));
        drop(stranger);

        stage = "vercel-limit";
        String fingerprint = coexist.vercel().fingerprint();
        require("FLY_WORKER_INSPECTION_CHANGED".equals(limit("0".repeat(64)).code()) && connlimit(vercel) == 16);
        // Mais de 4 backends ativos desse login: recusado, nada muda.
        List<Connection> open = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) open.add(DriverManager.getConnection(url(database), vercel, vercelPassword));
            require("FLY_WORKER_VERCEL_BACKENDS_EXCEED".equals(limit(fingerprint).code()) && connlimit(vercel) == 16);
        } finally { for (var c : open) c.close(); }
        FlyWorkerLogin.Outcome reduced = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            reduced = limit(fingerprint);
            if (!"FLY_WORKER_VERCEL_BACKENDS_EXCEED".equals(reduced.code())) break;
            Thread.sleep(100);
        }
        require(reduced.applied() && "REDUCED".equals(reduced.state()) && reduced.code().isEmpty() && connlimit(vercel) == 4 && reduced.budget().runtimeLimits() == 8);
        require(connlimit(flyApi) == 4);
        var again = limit(reduced.fingerprint());
        require(again.alreadyApplied() && !again.applied() && again.code().isEmpty() && connlimit(vercel) == 4);
        var afterInspect = run(config(next, "inspect", ""), "", false);
        require(afterInspect.eligible() && "REDUCED".equals(afterInspect.vercel().state()) && afterInspect.budget().runtimeLimits() == 8);
        // Limite inesperado (nem 16 nem 4): recusado.
        sql("ALTER ROLE " + vercel + " CONNECTION LIMIT 8");
        var odd = run(config(next, "inspect", ""), "", false);
        require("BLOCKED".equals(odd.vercel().state()) && "FLY_WORKER_VERCEL_LIMIT_UNEXPECTED".equals(odd.vercel().code()));
        require("FLY_WORKER_VERCEL_LIMIT_UNEXPECTED".equals(limit(odd.vercel().fingerprint()).code()) && connlimit(vercel) == 8);
        drop(vercel); drop(flyApi);
        require(!exists(roleOf(next)));

        stage = "tls-denied";
        boolean tls = false;
        try (var c = owner()) { FlyWorkerLogin.requireTls(c); } catch (FlyWorkerLogin.Denied expected) { tls = "FLY_WORKER_DATABASE_TLS_REQUIRED".equals(expected.code); }
        require(tls);
    }

    public static void main(String[] args) {
        java.util.logging.LogManager.getLogManager().reset(); LogFactory.setLogCreator(new BootstrapBaseline.QuietLogCreator());
        try {
            require(args.length == 0 && "DISPOSABLE".equals(System.getenv("CP_DB_TEST_OK")));
            host = System.getenv("PGHOST"); port = System.getenv("PGPORT");
            require(Set.of("localhost", "127.0.0.1").contains(host) && port != null && port.matches("[0-9]{1,5}"));
            require("condpulse_ci".equals(System.getenv("PGDATABASE")) && USER.equals(System.getenv("PGUSER")) && PASSWORD.equals(System.getenv("PGPASSWORD")));
            database = "condpulse_flyw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            try (var admin = DriverManager.getConnection(url("condpulse_ci"), USER, PASSWORD)) {
                try (var q = admin.createStatement(); var r = q.executeQuery("select count(*) from pg_roles where rolname like 'cp\\_runtime\\_%' escape '\\' or rolname like 'cp\\_worker\\_%' escape '\\'")) { require(r.next() && r.getLong(1) == 0); }
                maxConnections = Integer.parseInt(text(admin, "show max_connections"));
                sql(admin, "CREATE DATABASE " + database);
                try {
                    stage = "migrate";
                    try (var c = owner()) { for (String name : SOURCES) sql(c, source(name)); }
                    var flyway = flyway(); flyway.baseline(); require(flyway.migrate().migrationsExecuted >= 21); flyway.validate();
                    scenarios();
                } finally {
                    for (String role : createdRoles) drop(role);
                    sql(admin, "DROP DATABASE " + database);
                }
            }
            System.out.println("{\"passed\":true,\"logins\":" + logins + ",\"connectionLimit\":2,\"abortDisables\":true,\"readyReplay\":true,\"budgetDenied\":true,\"vercelLimitReduced\":true,\"driftDenied\":true}");
        } catch (Throwable ignored) {
            System.out.println("{\"passed\":false,\"code\":\"FLY_WORKER_HARNESS_FAILED\",\"stage\":\"" + stage + "\"}"); System.exit(1);
        }
    }
}
