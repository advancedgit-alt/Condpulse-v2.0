package br.com.condpulse.bootstrap;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.logging.LogFactory;

/** Explicitly disposable CI fixture. No main/environment flag can select it in production. */
public final class RuntimeConfigurationHarness {
    private static final String USER = "postgres", PASSWORD = "condpulse_ci_only", SHA = "a".repeat(40);
    private static final String PUBLIC_CA = Base64.getEncoder().encodeToString("SYNTHETIC_PUBLIC_CA_NOT_A_SECRET".getBytes(StandardCharsets.UTF_8));
    private static final List<String> SOURCES = List.of("V001__foundation.sql", "V002__roles_and_rls.sql");
    private static String host, port, stage = "guard";
    private static final Set<String> createdDatabases = new HashSet<>();
    private static final Map<String, RuntimeConfiguration.Config> ownedRoles = new TreeMap<>();
    private static int authenticatedConnections;

    private static void require(boolean value) { if (!value) throw new AssertionError("SYNTHETIC_RUNTIME_ASSERTION_FAILED"); }
    private static void databaseName(String value) {
        require(value != null && value.matches("condpulse_runtime_[a-f0-9]{12}_(positive|partial|drift)"));
    }
    private static String url(String database) {
        if (!"condpulse_ci".equals(database)) databaseName(database);
        return "jdbc:postgresql://" + host + ":" + port + "/" + database + "?sslmode=disable";
    }
    private static Connection owner(String database) throws Exception {
        databaseName(database); require(createdDatabases.contains(database));
        return DriverManager.getConnection(url(database), USER, PASSWORD);
    }
    private static Connection login(String database, String role, String password) throws Exception {
        databaseName(database); require(createdDatabases.contains(database));
        require(ownedRoles.containsKey(role) && role.matches("cp_runtime_[a-f0-9]{32}"));
        require(password != null && password.matches("[A-Za-z0-9_-]{48}"));
        Properties properties = new Properties(); properties.setProperty("user", role); properties.setProperty("password", password);
        properties.setProperty("requireAuth", "scram-sha-256"); properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "30");
        return DriverManager.getConnection(url(database), properties);
    }
    private static void sql(Connection connection, String statement) throws Exception {
        try (var query = connection.createStatement()) { query.setQueryTimeout(15); query.execute(statement); }
    }
    private static long count(Connection connection, String statement) throws Exception {
        try (var query = connection.createStatement()) { query.setQueryTimeout(15);
            try (var rows = query.executeQuery(statement)) { require(rows.next()); return rows.getLong(1); }
        }
    }
    private static String source(String name) throws Exception {
        require(SOURCES.contains(name));
        try (var stream = RuntimeConfigurationHarness.class.getResourceAsStream("/db/migration/" + name)) {
            require(stream != null); return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static Flyway flyway(String database) {
        databaseName(database); require(createdDatabases.contains(database));
        return Flyway.configure().dataSource(url(database), USER, PASSWORD)
            .defaultSchema("platform").schemas("platform", "cp").createSchemas(false).locations("classpath:db/migration")
            .baselineVersion("002").baselineOnMigrate(false).cleanDisabled(true).validateMigrationNaming(true)
            .outOfOrder(false).target("005").loggers(BootstrapBaseline.QuietLogCreator.class.getName()).load();
    }
    private static void migrate(String database) throws Exception {
        // Raw SQL is allowed only to model legacy V001/V002 in this newly created disposable database.
        try (var connection = owner(database)) { for (String name : SOURCES) sql(connection, source(name)); }
        var flyway = flyway(database); flyway.baseline(); require(flyway.migrate().migrationsExecuted == 3); flyway.validate();
    }
    private static RuntimeConfiguration.Config config(UUID generation, String mode, String ack) {
        return new RuntimeConfiguration.Config(generation, SHA, mode, ack, PUBLIC_CA);
    }
    private static RuntimeConfiguration.Config inspectConfig() {
        var config = config(UUID.randomUUID(), "inspect", ""); ownedRoles.put(config.role(), config); return config;
    }
    private static RuntimeConfiguration.Config apply(RuntimeConfiguration.Config config, String fingerprint) {
        return config(config.generation(), "apply", config.acknowledgement(fingerprint));
    }
    private static RuntimeConfiguration.Outcome execute(String database, RuntimeConfiguration.Config config, FakeGateway gateway) throws Exception {
        try (var connection = owner(database)) {
            gateway.owner = connection; gateway.config = config;
            return RuntimeConfiguration.execute(config, connection, flyway(database), gateway, (role, password) -> {
                require(config.role().equals(role));
                var proof = login(database, role, password); authenticatedConnections++; return proof;
            });
        } finally { gateway.owner = null; }
    }
    private static boolean roleExists(Connection connection, String role) throws Exception {
        try (var query = connection.prepareStatement("select exists(select from pg_roles where rolname=?)")) {
            query.setString(1, role); try (var rows = query.executeQuery()) { require(rows.next()); return rows.getBoolean(1); }
        }
    }
    private static void state(String database, RuntimeConfiguration.Config config, String expected, boolean login, boolean passwordNull) throws Exception {
        try (var connection = owner(database); var query = connection.prepareStatement("select rolcanlogin,rolpassword is null,rolpassword like 'SCRAM-SHA-256$%',shobj_description(oid,'pg_authid'),rolvaliduntil::text,rolconnlimit from pg_authid where rolname=?")) {
            query.setString(1, config.role()); try (var rows = query.executeQuery()) {
                require(rows.next() && rows.getBoolean(1) == login && rows.getBoolean(2) == passwordNull);
                require(passwordNull || rows.getBoolean(3)); require((config.marker() + ":" + expected).equals(rows.getString(4)));
                if ("READY".equals(expected)) require("infinity".equals(rows.getString(5)));
                require(rows.getInt(6) == 16);
            }
        }
    }

    /** No HTTP in the DB transaction and no secret value is retained in returned metadata. */
    private static final class FakeGateway implements RuntimeConfiguration.Gateway {
        private final Map<String, RuntimeConfiguration.Entry> entries = new TreeMap<>();
        private Connection owner; private RuntimeConfiguration.Config config;
        private int puts, revision; private boolean failPartial;
        private String password;
        @Override public Map<String, RuntimeConfiguration.Entry> inspect() throws Exception {
            require(owner != null && owner.getAutoCommit()); return Map.copyOf(entries);
        }
        private void save(RuntimeConfiguration.Change change) {
            require(!change.toString().contains(change.value()));
            require(change.secret() == RuntimeConfiguration.SECRET.equals(change.key()));
            if (change.secret()) { require(change.value().matches("[A-Za-z0-9_-]{48}")); password = change.value(); }
            entries.put(change.key(), new RuntimeConfiguration.Entry("env_synthetic_" + change.key(), change.key(),
                change.secret() ? "sensitive" : "plain", change.secret() ? "secret" : "config", List.of("production"),
                null, change.comment(), change.secret() ? null : change.value(), Integer.toString(++revision)));
        }
        @Override public void put(List<RuntimeConfiguration.Change> changes) throws Exception {
            require(owner != null && owner.getAutoCommit() && authenticatedConnections > 0); puts++;
            try (var query = owner.prepareStatement("select rolcanlogin,rolvaliduntil>clock_timestamp(),rolvaliduntil<=clock_timestamp()+interval '11 minutes',shobj_description(oid,'pg_authid') from pg_roles where rolname=?")) {
                query.setString(1, config.role()); try (var rows = query.executeQuery()) {
                    require(rows.next() && rows.getBoolean(1) && rows.getBoolean(2) && rows.getBoolean(3));
                    require((config.marker() + ":STAGED").equals(rows.getString(4)));
                }
            }
            require(changes.size() == 11); Set<String> keys = new HashSet<>();
            for (var change : changes) require(keys.add(change.key()) && config.marker().equals(change.comment()));
            Set<String> expectedKeys = new HashSet<>(RuntimeConfiguration.values(config).keySet()); expectedKeys.add(RuntimeConfiguration.SECRET);
            require(keys.equals(expectedKeys));
            if (failPartial) {
                failPartial = false;
                for (var change : changes) if (Set.of("DATABASE_USER", RuntimeConfiguration.SECRET).contains(change.key())) save(change);
                throw new IllegalStateException("SYNTHETIC_HTTP_PARTIAL");
            }
            for (var change : changes) save(change);
        }
        @Override public String toString() { return "SyntheticRuntimeGateway[redacted]"; }
    }

    private static UUID seedReservation(String database) throws Exception {
        UUID account = UUID.randomUUID(), tenant = UUID.randomUUID(), amenity = UUID.randomUUID();
        try (var connection = owner(database)) {
            try (var query = connection.prepareStatement("insert into platform.accounts(id,status) values(?,'ACTIVE')")) { query.setObject(1, account); require(query.executeUpdate() == 1); }
            try (var query = connection.prepareStatement("insert into platform.tenants(id,name,time_zone,active) values(?,'SYNTHETIC_RUNTIME_FIXTURE','UTC',true)")) { query.setObject(1, tenant); require(query.executeUpdate() == 1); }
            try (var query = connection.prepareStatement("insert into cp.amenities(id,tenant_id,name,capacity) values(?,?,'SYNTHETIC_RUNTIME_FIXTURE',1)")) { query.setObject(1, amenity); query.setObject(2, tenant); require(query.executeUpdate() == 1); }
            try (var query = connection.prepareStatement("insert into cp.reservations(tenant_id,amenity_id,created_by,kind,status,starts_at,ends_at,occupied_from,occupied_until,rule_snapshot) values(?,?,?,'BLOCK','CONFIRMED',now()+interval '2 days',now()+interval '2 days 1 hour',now()+interval '2 days',now()+interval '2 days 1 hour','{}'::jsonb)")) {
                query.setObject(1, tenant); query.setObject(2, amenity); query.setObject(3, account); require(query.executeUpdate() == 1);
            }
            require(count(connection, "select count(*) from cp.reservations") == 1);
        }
        return tenant;
    }
    private static void positive(String database, RuntimeConfiguration.Config config) throws Exception {
        stage = "positive-inspect"; migrate(database); UUID tenant = seedReservation(database); var gateway = new FakeGateway();
        var inspected = execute(database, config, gateway);
        require(inspected.eligible() && !inspected.applied() && !inspected.alreadyApplied() && "ABSENT".equals(inspected.state()) && inspected.code().isEmpty());
        try (var connection = owner(database)) { require(!roleExists(connection, config.role())); }
        require(gateway.puts == 0 && authenticatedConnections == 0);
        stage = "ack-denied";
        var denied = execute(database, config(config.generation(), "apply", config.acknowledgement("0".repeat(64))), gateway);
        require("RUNTIME_ACK_REQUIRED".equals(denied.code()) && !denied.applied() && gateway.puts == 0);
        try (var connection = owner(database)) { require(!roleExists(connection, config.role())); }
        stage = "positive-apply"; var applied = execute(database, apply(config, inspected.fingerprint()), gateway);
        require(applied.eligible() && applied.applied() && !applied.alreadyApplied() && "READY".equals(applied.state()) && applied.code().isEmpty());
        require(gateway.puts == 1 && authenticatedConnections == 1); state(database, config, "READY", true, false);
        String originalPassword = gateway.password;
        stage = "positive-replay"; var ready = execute(database, config, gateway);
        require(ready.alreadyApplied() && ready.code().isEmpty());
        var replay = execute(database, apply(config, ready.fingerprint()), gateway);
        require(replay.eligible() && replay.alreadyApplied() && !replay.applied() && replay.code().isEmpty());
        require(gateway.puts == 1 && authenticatedConnections == 1 && originalPassword.equals(gateway.password));
        try (var connection = login(database, config.role(), originalPassword)) {
            RuntimeConfiguration.verifyLogin(connection, config.role()); require(count(connection, "select count(*) from cp.reservations") == 0);
            try (var query = connection.createStatement(); var rows = query.executeQuery("select current_user,session_user,pg_has_role(current_user,'cp_api','MEMBER'),pg_has_role(current_user,'cp_worker','MEMBER'),pg_has_role(current_user,'cp_onboarding','MEMBER'),rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication,has_schema_privilege(current_user,'cp','CREATE') or has_schema_privilege(current_user,'platform','CREATE') from pg_roles where rolname=current_user")) {
                require(rows.next() && config.role().equals(rows.getString(1)) && config.role().equals(rows.getString(2))
                    && rows.getBoolean(3) && !rows.getBoolean(4) && !rows.getBoolean(5) && !rows.getBoolean(6) && !rows.getBoolean(7));
            }
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("select set_config('app.tenant_id',?,true)")) { query.setString(1, tenant.toString()); query.execute(); }
            require(count(connection, "select count(*) from cp.reservations") == 1); connection.commit();
            require(count(connection, "select count(*) from cp.reservations") == 0);
        }
        stage = "extra-membership";
        try (var connection = owner(database)) { sql(connection, "GRANT cp_onboarding TO " + config.role()); }
        try {
            var unsafe = execute(database, config, gateway); require("RUNTIME_MEMBERSHIP_UNSAFE".equals(unsafe.code()));
            require(!unsafe.applied() && gateway.puts == 1); state(database, config, "READY", true, false);
        } finally { try (var connection = owner(database)) { sql(connection, "REVOKE cp_onboarding FROM " + config.role()); } }
        require(execute(database, config, gateway).code().isEmpty());
    }
    private static void partial(String database, RuntimeConfiguration.Config config) throws Exception {
        stage = "partial-inspect"; migrate(database); var gateway = new FakeGateway(); gateway.failPartial = true;
        var inspected = execute(database, config, gateway); require(inspected.eligible() && inspected.code().isEmpty());
        stage = "partial-disabled"; var failed = execute(database, apply(config, inspected.fingerprint()), gateway);
        require(!failed.applied() && "DISABLED".equals(failed.state()) && "RUNTIME_PARTIAL_RECONCILE_REQUIRED".equals(failed.code()));
        state(database, config, "DISABLED", false, true); require(gateway.puts == 1);
        String failedPassword = gateway.password; require(failedPassword != null);
        boolean loginDenied = false;
        try (var ignored = login(database, config.role(), failedPassword)) { /* NOLOGIN must reject even if the password was copied externally. */ }
        catch (java.sql.SQLException expected) { loginDenied = true; }
        require(loginDenied);
        stage = "partial-recover"; var recover = execute(database, config, gateway);
        require(recover.eligible() && "DISABLED".equals(recover.state()) && recover.code().isEmpty() && !recover.fingerprint().equals(inspected.fingerprint()));
        var stale = execute(database, apply(config, inspected.fingerprint()), gateway);
        require("RUNTIME_ACK_REQUIRED".equals(stale.code()) && gateway.puts == 1);
        var repaired = execute(database, apply(config, recover.fingerprint()), gateway);
        require(repaired.applied() && repaired.code().isEmpty() && "READY".equals(repaired.state()) && gateway.puts == 2);
        require(!failedPassword.equals(gateway.password)); state(database, config, "READY", true, false);
        try (var connection = owner(database)) { require(count(connection, "select count(*) from pg_roles where rolname like 'cp\\_runtime\\_%' escape '\\'") == 1); }
        var ready = execute(database, config, gateway); var replay = execute(database, apply(config, ready.fingerprint()), gateway);
        require(replay.alreadyApplied() && !replay.applied() && replay.code().isEmpty() && gateway.puts == 2);
    }
    private static void drift(String database, RuntimeConfiguration.Config config) throws Exception {
        stage = "grant-drift"; migrate(database); var gateway = new FakeGateway();
        try (var connection = owner(database)) { sql(connection, "GRANT DELETE ON cp.audit_log TO cp_api"); }
        try {
            var denied = execute(database, config, gateway); require("RUNTIME_GRANTS_DIVERGED".equals(denied.code()) && !denied.applied() && gateway.puts == 0);
            try (var connection = owner(database)) { require(!roleExists(connection, config.role())); }
        } finally { try (var connection = owner(database)) { sql(connection, "REVOKE DELETE ON cp.audit_log FROM cp_api"); } }
        stage = "tls-denied";
        try (var connection = owner(database)) {
            boolean denied = false;
            try { RuntimeConfiguration.requireTls(connection); }
            catch (RuntimeConfiguration.Denied expected) { denied = "RUNTIME_DATABASE_TLS_REQUIRED".equals(expected.code); }
            require(denied);
        }
    }
    private static void dropRole(Connection admin, RuntimeConfiguration.Config config) throws Exception {
        require(ownedRoles.containsKey(config.role()) && config.role().matches("cp_runtime_[a-f0-9]{32}"));
        try (var query = admin.prepareStatement("select shobj_description(oid,'pg_authid') from pg_roles where rolname=?")) {
            query.setString(1, config.role()); try (var rows = query.executeQuery()) {
                if (!rows.next()) return;
                require(rows.getString(1) != null && rows.getString(1).startsWith(config.marker() + ":"));
            }
        }
        sql(admin, "DROP ROLE " + config.role());
    }
    public static void main(String[] args) {
        java.util.logging.LogManager.getLogManager().reset(); LogFactory.setLogCreator(new BootstrapBaseline.QuietLogCreator());
        try {
            require(args.length == 0 && "DISPOSABLE".equals(System.getenv("CP_DB_TEST_OK")));
            host = System.getenv("PGHOST"); port = System.getenv("PGPORT");
            require(Set.of("localhost", "127.0.0.1").contains(host) && port != null && port.matches("[0-9]{1,5}")
                && Integer.parseInt(port) > 0 && Integer.parseInt(port) <= 65535);
            require("condpulse_ci".equals(System.getenv("PGDATABASE")) && USER.equals(System.getenv("PGUSER")) && PASSWORD.equals(System.getenv("PGPASSWORD")));
            String prefix = "condpulse_runtime_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            try (var admin = DriverManager.getConnection(url("condpulse_ci"), USER, PASSWORD)) {
                require(count(admin, "select count(*) from pg_roles where rolname like 'cp\\_runtime\\_%' escape '\\'") == 0);
                try (var query = admin.createStatement(); var rows = query.executeQuery("select current_database(),current_user")) {
                    require(rows.next() && "condpulse_ci".equals(rows.getString(1)) && USER.equals(rows.getString(2)));
                }
                try {
                    for (String suffix : List.of("positive", "partial", "drift")) {
                        String database = prefix + "_" + suffix; databaseName(database);
                        sql(admin, "CREATE DATABASE " + database); createdDatabases.add(database);
                        var config = inspectConfig();
                        try {
                            switch (suffix) { case "positive" -> positive(database, config); case "partial" -> partial(database, config); case "drift" -> drift(database, config); default -> throw new AssertionError(); }
                        } finally { dropRole(admin, config); }
                    }
                } finally {
                    for (var config : ownedRoles.values()) dropRole(admin, config);
                    // All fixture connections have closed; never FORCE, terminate sessions, or drop an unowned database.
                    for (String database : createdDatabases) {
                        databaseName(database); require(database.startsWith(prefix + "_")); sql(admin, "DROP DATABASE " + database);
                    }
                }
            }
            require(authenticatedConnections == 3);
            System.out.println("{\"passed\":true,\"runtimeLogins\":3,\"readyReplay\":true,\"partialRecovery\":true,\"rlsIsolation\":true,\"membershipDriftDenied\":true,\"grantDriftDenied\":true,\"nonTlsDenied\":true}");
        } catch (Throwable ignored) {
            System.out.println("{\"passed\":false,\"code\":\"RUNTIME_HARNESS_FAILED\",\"stage\":\"" + stage + "\"}"); System.exit(1);
        }
    }
}
