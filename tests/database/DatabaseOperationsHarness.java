package br.com.condpulse.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.logging.LogFactory;

/** CI fixtures only. Not included in production classes, images or the manual workflow. */
public final class DatabaseOperationsHarness {
    private static final String USER = "postgres", PASSWORD = "condpulse_ci_only";
    private static final List<String> SOURCES = List.of("V001__foundation.sql", "V002__roles_and_rls.sql",
        "V003__durable_bff_sessions.sql", "V004__bff_rate_limits.sql", "V005__operational_onboarding.sql");
    private static String host, port;
    private static void require(boolean ok) { if (!ok) throw new AssertionError("DISPOSABLE_GUARD_OR_ASSERTION_FAILED"); }
    private static void databaseName(String value) { require(value != null && value.matches("condpulse_dbops_[a-f0-9]{12}_(positive|normal|checksum|above)")); }
    private static String url(String database) { return "jdbc:postgresql://" + host + ":" + port + "/" + database; }
    private static Connection open(String database) throws Exception { databaseName(database); return DriverManager.getConnection(url(database), USER, PASSWORD); }
    private static String source(String name) throws Exception {
        require(SOURCES.contains(name));
        try (var stream = DatabaseOperationsHarness.class.getResourceAsStream("/db/migration/" + name)) {
            require(stream != null); return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static void sql(Connection c, String sql) throws Exception {
        try (var statement = c.createStatement()) { statement.setQueryTimeout(15); statement.execute(sql); }
    }
    private static boolean relation(String database, String name) throws Exception {
        try (var c = open(database); var statement = c.prepareStatement("select to_regclass(?) is not null")) {
            statement.setString(1, name); try (var rows = statement.executeQuery()) { require(rows.next()); return rows.getBoolean(1); }
        }
    }
    private static Flyway flyway(String database, String location, String target) {
        databaseName(database);
        return Flyway.configure().dataSource(url(database), USER, PASSWORD)
            .defaultSchema("platform").schemas("platform", "cp").createSchemas(false)
            .locations(location).baselineOnMigrate(false).baselineVersion("002").cleanDisabled(true)
            .validateMigrationNaming(true).validateOnMigrate(true).outOfOrder(false).target(target)
            .loggers(BootstrapBaseline.QuietLogCreator.class.getName()).load();
    }
    private static void legacy(String database) throws Exception {
        // Recreate only the reviewed V001/V002 legacy schema in a new disposable DB.
        try (var c = open(database)) { sql(c, source(SOURCES.get(0))); sql(c, source(SOURCES.get(1))); }
        // Only the public Flyway API creates the baseline marker.
        flyway(database, "classpath:db/migration", "005").baseline();
    }
    private static int execute(String database, Flyway flyway) throws Exception {
        try (var c = open(database)) { return DatabaseOperationsMigrateGuard.execute(c, flyway); }
    }
    private static void rejected(String database, Flyway flyway) throws Exception {
        boolean denied = false;
        try { execute(database, flyway); } catch (Exception expected) { denied = true; }
        require(denied);
    }
    private static void copySources(Path directory) throws Exception {
        Files.createDirectories(directory);
        for (String name : SOURCES) Files.writeString(directory.resolve(name), source(name), StandardCharsets.UTF_8);
    }
    private static void positive(String database) throws Exception {
        legacy(database); var flyway = flyway(database, "classpath:db/migration", "005");
        require(execute(database, flyway) == 3);
        flyway.validate(); require(execute(database, flyway) == 0);
        require(relation(database, "platform.web_sessions") && relation(database, "platform.auth_rate_limits")
            && relation(database, "platform.onboarding_runs"));
    }
    private static void noBaseline(String database) throws Exception {
        try (var c = open(database)) { sql(c, "create schema platform; create schema cp"); }
        require(flyway(database, "classpath:db/migration", "002").migrate().migrationsExecuted == 2);
        rejected(database, flyway(database, "classpath:db/migration", "005"));
        require(!relation(database, "platform.web_sessions"));
    }
    private static void checksum(String database, Path directory) throws Exception {
        legacy(database); require(flyway(database, "classpath:db/migration", "003").migrate().migrationsExecuted == 1);
        copySources(directory);
        Files.writeString(directory.resolve(SOURCES.get(2)), source(SOURCES.get(2)) + "\nSELECT 42;\n", StandardCharsets.UTF_8);
        rejected(database, flyway(database, "filesystem:" + directory, "005"));
        require(!relation(database, "platform.auth_rate_limits") && !relation(database, "platform.onboarding_runs"));
        // Original source remains valid, showing the negative changed only a disposable copy.
        var original = flyway(database, "classpath:db/migration", "005").validateWithResult();
        require(original.invalidMigrations.size() == 2);
        for (var invalid : original.invalidMigrations)
            require(invalid.errorDetails.errorCode == org.flywaydb.core.api.CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED);
    }
    private static void above(String database, Path directory) throws Exception {
        legacy(database); require(execute(database, flyway(database, "classpath:db/migration", "005")) == 3);
        copySources(directory); Files.writeString(directory.resolve("V006__synthetic_above_head.sql"), "SELECT 42;\n", StandardCharsets.UTF_8);
        require(flyway(database, "filesystem:" + directory, "006").migrate().migrationsExecuted == 1);
        rejected(database, flyway(database, "classpath:db/migration", "005"));
        try (var c = open(database); var statement = c.createStatement();
                var rows = statement.executeQuery("select count(*) from platform.flyway_schema_history where type='SQL' and success")) {
            require(rows.next() && rows.getInt(1) == 4);
        }
    }
    public static void main(String[] args) throws Exception {
        java.util.logging.LogManager.getLogManager().reset();
        LogFactory.setLogCreator(new BootstrapBaseline.QuietLogCreator());
        require(args.length == 1 && "DISPOSABLE".equals(System.getenv("CP_DB_TEST_OK")));
        host = System.getenv("PGHOST"); port = System.getenv("PGPORT");
        require(host != null && Set.of("localhost", "127.0.0.1").contains(host));
        require(port != null && port.matches("[0-9]{1,5}") && Integer.parseInt(port) > 0 && Integer.parseInt(port) <= 65535);
        require("condpulse_ci".equals(System.getenv("PGDATABASE")) && USER.equals(System.getenv("PGUSER"))
            && PASSWORD.equals(System.getenv("PGPASSWORD")));
        Path fixture = Path.of(args[0]).toAbsolutePath().normalize();
        require(Files.isDirectory(fixture) && fixture.getFileName().toString().startsWith("condpulse-dbops-jdbc-"));
        String prefix = "condpulse_dbops_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<String> created = new ArrayList<>();
        try (var admin = DriverManager.getConnection(url("condpulse_ci"), USER, PASSWORD)) {
            try (var statement = admin.createStatement(); var rows = statement.executeQuery("select current_database(),current_user")) {
                require(rows.next() && "condpulse_ci".equals(rows.getString(1)) && USER.equals(rows.getString(2)));
            }
            try {
                for (String suffix : List.of("positive", "normal", "checksum", "above")) {
                    String database = prefix + "_" + suffix; databaseName(database);
                    sql(admin, "CREATE DATABASE \"" + database + "\""); created.add(database);
                }
                positive(prefix + "_positive"); noBaseline(prefix + "_normal");
                checksum(prefix + "_checksum", fixture.resolve("checksum")); above(prefix + "_above", fixture.resolve("above"));
            } finally {
                for (String database : created.reversed()) {
                    databaseName(database); require(database.startsWith(prefix + "_"));
                    // Every fixture connection is closed by try-with-resources; never FORCE or terminate other sessions.
                    sql(admin, "DROP DATABASE \"" + database + "\"");
                }
            }
        }
        System.out.println("{\"passed\":true,\"positive\":3,\"replay\":0,\"missingBaselineDenied\":true,\"checksumDriftDenied\":true,\"aboveHeadDenied\":true}");
    }
}
