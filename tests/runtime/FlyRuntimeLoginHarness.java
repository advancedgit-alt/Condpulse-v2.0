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

/** Fixture descartável de CI para FlyRuntimeLogin. Nenhum main/flag de ambiente a seleciona em produção. */
public final class FlyRuntimeLoginHarness {
    private static final String USER = "postgres", PASSWORD = "condpulse_ci_only", SHA = "b".repeat(40);
    private static final List<String> SOURCES = List.of("V001__foundation.sql", "V002__roles_and_rls.sql");
    private static final List<String> createdRoles = new ArrayList<>();
    private static String host, port, database, stage = "guard";
    private static int maxConnections, logins;

    private static void require(boolean value) { if (!value) throw new AssertionError("SYNTHETIC_FLY_ASSERTION_FAILED"); }
    private static String url(String name) { return "jdbc:postgresql://" + host + ":" + port + "/" + name + "?sslmode=disable"; }
    private static Connection owner() throws Exception { return DriverManager.getConnection(url(database), USER, PASSWORD); }
    private static void sql(Connection c, String statement) throws Exception { try (var q = c.createStatement()) { q.setQueryTimeout(15); q.execute(statement); } }
    private static String text(Connection c, String statement) throws Exception {
        try (var q = c.createStatement(); var r = q.executeQuery(statement)) { require(r.next()); return r.getString(1); }
    }
    private static String source(String name) throws Exception {
        try (var stream = FlyRuntimeLoginHarness.class.getResourceAsStream("/db/migration/" + name)) {
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
    private static FlyRuntimeLogin.Config config(UUID generation, String mode, String fingerprint, int max) {
        return new FlyRuntimeLogin.Config(generation, SHA, mode, fingerprint, max);
    }
    private static FlyRuntimeLogin.Outcome run(FlyRuntimeLogin.Config config, String secret, boolean commit) throws Exception {
        String role = config.role(); if (!createdRoles.contains(role)) createdRoles.add(role);
        try (var c = owner()) {
            return FlyRuntimeLogin.execute(config, c, flyway(), (name, value) -> {
                require(role.equals(name) && secret.equals(value));
                Properties p = new Properties(); p.setProperty("user", name); p.setProperty("password", value); p.setProperty("requireAuth", "scram-sha-256");
                logins++; return DriverManager.getConnection(url(database), p);
            }, new FlyRuntimeLogin.Channel() {
                @Override public String password() { return secret; }
                @Override public boolean confirmStaged() throws Exception {
                    // Durante a gravação na Fly o login está STAGED, com validade curta e teto 4.
                    try (var o = owner(); var q = o.prepareStatement("select rolcanlogin,rolvaliduntil<=clock_timestamp()+interval '11 minutes',rolconnlimit,shobj_description(oid,'pg_authid') from pg_roles where rolname=?")) {
                        q.setString(1, role); try (var r = q.executeQuery()) {
                            require(r.next() && r.getBoolean(1) && r.getBoolean(2) && r.getInt(3) == 4 && (config.marker() + ":STAGED").equals(r.getString(4)));
                        }
                    }
                    return commit;
                }
            });
        }
    }
    private static void role(String role, boolean login, String state) throws Exception {
        try (var c = owner(); var q = c.prepareStatement("select rolcanlogin,rolvaliduntil::text,rolconnlimit,shobj_description(oid,'pg_authid'),(select count(*) from pg_auth_members m where m.member=r.oid) from pg_roles r where rolname=?")) {
            q.setString(1, role); try (var r = q.executeQuery()) {
                require(r.next() && r.getBoolean(1) == login && r.getInt(3) == 4 && r.getString(4).endsWith(":" + state) && r.getLong(5) == 1);
                if ("READY".equals(state)) require("infinity".equals(r.getString(2)));
            }
        }
    }
    private static boolean exists(String role) throws Exception {
        try (var c = owner(); var q = c.prepareStatement("select count(*) from pg_roles where rolname=?")) {
            q.setString(1, role); try (var r = q.executeQuery()) { require(r.next()); return r.getLong(1) == 1; }
        }
    }
    private static void drop(String role) throws Exception {
        require(role.matches("cp_runtime_[a-f0-9]{32}"));
        try (var c = owner()) { if (exists(role)) sql(c, "DROP ROLE " + role); }
    }
    private static String vercelRole(int limit) throws Exception {
        UUID generation = UUID.randomUUID(); String role = "cp_runtime_" + generation.toString().replace("-", "");
        createdRoles.add(role);
        try (var c = owner()) {
            sql(c, "CREATE ROLE " + role + " NOLOGIN CONNECTION LIMIT " + limit);
            sql(c, "COMMENT ON ROLE " + role + " IS 'condpulse-runtime:rpnrqfrfwywglvyykgrg:" + generation + ":" + "c".repeat(40) + ":READY'");
        }
        return role;
    }

    private static void scenarios() throws Exception {
        UUID generation = UUID.randomUUID(); String role = "cp_runtime_" + generation.toString().replace("-", "");
        stage = "inspect";
        var inspected = run(config(generation, "inspect", "", maxConnections), "", false);
        require(inspected.eligible() && "ABSENT".equals(inspected.state()) && inspected.code().isEmpty() && inspected.fingerprint().matches("[a-f0-9]{64}"));
        require(!exists(role) && logins == 0);

        stage = "stale-fingerprint";
        var stale = run(config(generation, "apply", "0".repeat(64), maxConnections), password(), true);
        require("FLY_RUNTIME_INSPECTION_CHANGED".equals(stale.code()) && !exists(role) && logins == 0);

        stage = "abort-disables";
        String first = password();
        var aborted = run(config(generation, "apply", inspected.fingerprint(), maxConnections), first, false);
        require("FLY_RUNTIME_PARTIAL_RECONCILE_REQUIRED".equals(aborted.code()) && "DISABLED".equals(aborted.state()) && logins == 1);
        role(role, false, "DISABLED");
        boolean denied = false;
        try (var ignored = DriverManager.getConnection(url(database), role, first)) { /* NOLOGIN precisa recusar. */ }
        catch (java.sql.SQLException expected) { denied = true; }
        require(denied);

        stage = "recover-ready";
        var recover = run(config(generation, "inspect", "", maxConnections), "", false);
        require(recover.eligible() && "DISABLED".equals(recover.state()) && !recover.fingerprint().equals(inspected.fingerprint()));
        String second = password();
        var applied = run(config(generation, "apply", recover.fingerprint(), maxConnections), second, true);
        require(applied.applied() && "READY".equals(applied.state()) && applied.code().isEmpty() && logins == 2);
        role(role, true, "READY");
        try (var login = DriverManager.getConnection(url(database), role, second)) {
            FlyRuntimeLogin.verifyLogin(login, role);
            require("on".equals(text(login, "select current_setting('row_security')")));
            require("false".equals(text(login, "select (has_schema_privilege(current_user,'cp','CREATE') or pg_has_role(current_user,'cp_worker','MEMBER') or pg_has_role(current_user,'cp_onboarding','MEMBER'))::text")));
        }

        stage = "ready-replay";
        var ready = run(config(generation, "inspect", "", maxConnections), "", false);
        require(ready.alreadyApplied() && "READY".equals(ready.state()) && ready.code().isEmpty() && logins == 2);
        var replay = run(config(generation, "apply", ready.fingerprint(), maxConnections), "", true);
        require(replay.alreadyApplied() && !replay.applied() && replay.code().isEmpty() && logins == 2);

        stage = "direct-grant-denied";
        try (var c = owner()) { sql(c, "GRANT SELECT ON platform.tenants TO " + role); }
        try { require("FLY_RUNTIME_DIRECT_GRANT_FORBIDDEN".equals(run(config(generation, "inspect", "", maxConnections), "", false).code())); }
        finally { try (var c = owner()) { sql(c, "REVOKE SELECT ON platform.tenants FROM " + role); } }

        stage = "membership-denied";
        try (var c = owner()) { sql(c, "GRANT cp_worker TO " + role); }
        try { require("FLY_RUNTIME_MEMBERSHIP_UNSAFE".equals(run(config(generation, "inspect", "", maxConnections), "", false).code())); }
        finally { try (var c = owner()) { sql(c, "REVOKE cp_worker FROM " + role); } }

        stage = "other-fly-generation";
        UUID next = UUID.randomUUID();
        require("FLY_RUNTIME_OTHER_GENERATION_EXISTS".equals(run(config(next, "inspect", "", maxConnections), "", false).code()));
        drop(role);

        stage = "vercel-login-coexists";
        String vercel = vercelRole(16);
        var coexist = run(config(next, "inspect", "", maxConnections), "", false);
        require(coexist.eligible() && "ABSENT".equals(coexist.state()) && coexist.code().isEmpty());

        stage = "budget";
        require("FLY_RUNTIME_BUDGET_INSUFFICIENT".equals(run(config(next, "inspect", "", maxConnections + 1), "", false).code()));
        try (var c = owner()) { sql(c, "ALTER ROLE " + vercel + " CONNECTION LIMIT " + (maxConnections - 20 - 4)); }
        require("FLY_RUNTIME_BUDGET_INSUFFICIENT".equals(run(config(next, "inspect", "", maxConnections), "", false).code()));
        try (var c = owner()) { sql(c, "ALTER ROLE " + vercel + " CONNECTION LIMIT -1"); }
        require("FLY_RUNTIME_BUDGET_UNBOUNDED".equals(run(config(next, "inspect", "", maxConnections), "", false).code()));
        drop(vercel);

        stage = "unknown-runtime-role";
        String unknown = "cp_runtime_" + UUID.randomUUID().toString().replace("-", ""); createdRoles.add(unknown);
        try (var c = owner()) { sql(c, "CREATE ROLE " + unknown + " NOLOGIN CONNECTION LIMIT 1"); }
        require("FLY_RUNTIME_UNKNOWN_RUNTIME_ROLE".equals(run(config(next, "inspect", "", maxConnections), "", false).code()));
        drop(unknown);

        stage = "api-grant-drift";
        try (var c = owner()) { sql(c, "GRANT TRUNCATE ON cp.audit_log TO cp_api"); }
        try { require("FLY_RUNTIME_API_GRANTS_UNSAFE".equals(run(config(next, "inspect", "", maxConnections), "", false).code())); }
        finally { try (var c = owner()) { sql(c, "REVOKE TRUNCATE ON cp.audit_log FROM cp_api"); } }
        require(!exists("cp_runtime_" + next.toString().replace("-", "")));

        stage = "tls-denied";
        boolean tls = false;
        try (var c = owner()) { FlyRuntimeLogin.requireTls(c); } catch (FlyRuntimeLogin.Denied expected) { tls = "FLY_RUNTIME_DATABASE_TLS_REQUIRED".equals(expected.code); }
        require(tls);
    }

    public static void main(String[] args) {
        java.util.logging.LogManager.getLogManager().reset(); LogFactory.setLogCreator(new BootstrapBaseline.QuietLogCreator());
        try {
            require(args.length == 0 && "DISPOSABLE".equals(System.getenv("CP_DB_TEST_OK")));
            host = System.getenv("PGHOST"); port = System.getenv("PGPORT");
            require(Set.of("localhost", "127.0.0.1").contains(host) && port != null && port.matches("[0-9]{1,5}"));
            require("condpulse_ci".equals(System.getenv("PGDATABASE")) && USER.equals(System.getenv("PGUSER")) && PASSWORD.equals(System.getenv("PGPASSWORD")));
            database = "condpulse_fly_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            try (var admin = DriverManager.getConnection(url("condpulse_ci"), USER, PASSWORD)) {
                try (var q = admin.createStatement(); var r = q.executeQuery("select count(*) from pg_roles where rolname like 'cp\\_runtime\\_%' escape '\\'")) { require(r.next() && r.getLong(1) == 0); }
                maxConnections = Integer.parseInt(text(admin, "show max_connections"));
                sql(admin, "CREATE DATABASE " + database);
                try {
                    stage = "migrate";
                    // SQL bruto só para modelar V001/V002 legadas neste banco novo e descartável, como no harness da Vercel.
                    try (var c = owner()) { for (String name : SOURCES) sql(c, source(name)); }
                    var flyway = flyway(); flyway.baseline(); require(flyway.migrate().migrationsExecuted >= 3); flyway.validate();
                    scenarios();
                } finally {
                    for (String role : createdRoles) drop(role);
                    sql(admin, "DROP DATABASE " + database);
                }
            }
            System.out.println("{\"passed\":true,\"logins\":" + logins + ",\"connectionLimit\":4,\"abortDisables\":true,\"readyReplay\":true,\"vercelCoexists\":true,\"budgetDenied\":true,\"driftDenied\":true}");
        } catch (Throwable ignored) {
            System.out.println("{\"passed\":false,\"code\":\"FLY_HARNESS_FAILED\",\"stage\":\"" + stage + "\"}"); System.exit(1);
        }
    }
}
