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
    private static List<String> SOURCES;
    private static int latest;
    private static String host, port;
    private static void require(boolean ok) { if (!ok) throw new AssertionError("DISPOSABLE_GUARD_OR_ASSERTION_FAILED"); }
    private static void databaseName(String value) { require(value != null && value.matches("condpulse_dbops_[a-f0-9]{12}_(positive|normal|checksum|above|gap|resume|owners)")); }
    /** Todas as migrations versionadas do JAR revisado, em ordem; a ultima define o alvo do guard. */
    private static List<String> sources() throws Exception {
        var location = DatabaseOperationsHarness.class.getResource("/db/migration");
        require(location != null && "file".equals(location.getProtocol()));
        try (var files = Files.list(Path.of(location.toURI()))) {
            var names = files.map(path -> path.getFileName().toString()).sorted().toList();
            require(!names.isEmpty() && names.stream().allMatch(name -> name.matches("V[0-9]{3}__[a-z0-9_]+\\.sql")));
            for (int index = 0; index < names.size(); index++) require(names.get(index).startsWith(String.format("V%03d__", index + 1)));
            return names;
        }
    }
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
            .locations(location).baselineOnMigrate(false).baselineVersion("002").cleanDisabled(true).skipDefaultCallbacks(true)
            .validateMigrationNaming(true).validateOnMigrate(true).outOfOrder(false).target(target)
            .loggers(BootstrapBaseline.QuietLogCreator.class.getName()).load();
    }
    private static void legacy(String database) throws Exception {
        // Recreate only the reviewed V001/V002 legacy schema in a new disposable DB.
        try (var c = open(database)) { sql(c, source(SOURCES.get(0))); sql(c, source(SOURCES.get(1))); }
        // Only the public Flyway API creates the baseline marker.
        flyway(database, "classpath:db/migration", "005").baseline();
    }
    private static String head() { return String.valueOf(latest); }
    private static int execute(String database, Flyway flyway, int from, int to) throws Exception {
        try (var c = open(database)) { return DatabaseOperationsMigrateGuard.execute(c, flyway, from, to); }
    }
    /** Recusa pelas regras do guard (IllegalStateException), nunca por outro erro. */
    private static void rejected(String database, Flyway flyway, int from, int to) throws Exception {
        boolean denied = false;
        try { execute(database, flyway, from, to); } catch (IllegalStateException expected) { denied = true; }
        require(denied);
    }
    private static void copySources(Path directory) throws Exception {
        Files.createDirectories(directory);
        for (String name : SOURCES) Files.writeString(directory.resolve(name), source(name), StandardCharsets.UTF_8);
    }
    private static void positive(String database) throws Exception {
        legacy(database); var flyway = flyway(database, "classpath:db/migration", head());
        // Faixa que nao chega a ultima migration do JAR e recusada antes de escrever.
        rejected(database, flyway(database, "classpath:db/migration", String.valueOf(latest - 1)), 3, latest - 1);
        require(execute(database, flyway, 3, latest) == latest - 2);
        flyway.validate(); require(execute(database, flyway, 3, latest) == 0);
        require(relation(database, "platform.web_sessions") && relation(database, "platform.auth_rate_limits")
            && relation(database, "platform.onboarding_runs"));
    }
    private static void noBaseline(String database) throws Exception {
        try (var c = open(database)) { sql(c, "create schema platform; create schema cp"); }
        require(flyway(database, "classpath:db/migration", "002").migrate().migrationsExecuted == 2);
        rejected(database, flyway(database, "classpath:db/migration", head()), 3, latest);
        require(!relation(database, "platform.web_sessions"));
    }
    /** Estado real da homologacao: baseline + 003-005; faixa 006-ATE aplica o resto, repete com zero e 007-ATE e recusada. */
    private static void resume(String database) throws Exception {
        legacy(database); require(flyway(database, "classpath:db/migration", "5").migrate().migrationsExecuted == 3);
        var flyway = flyway(database, "classpath:db/migration", head());
        rejected(database, flyway, 7, latest);
        require(execute(database, flyway, 6, latest) == latest - 5);
        require(execute(database, flyway, 6, latest) == 0);
        // ACK alem da ultima migration do JAR, mesmo com o banco em dia, e recusado.
        rejected(database, flyway(database, "classpath:db/migration", head()), 6, latest + 1);
    }
    private static void gap(String database) throws Exception {
        // Faixa que pula versoes ainda nao aplicadas (004 sem 003) e recusada sem escrever.
        legacy(database);
        rejected(database, flyway(database, "classpath:db/migration", head()), 4, latest);
        require(!relation(database, "platform.web_sessions"));
    }
    private static void checksum(String database, Path directory) throws Exception {
        legacy(database); require(flyway(database, "classpath:db/migration", "003").migrate().migrationsExecuted == 1);
        copySources(directory);
        Files.writeString(directory.resolve(SOURCES.get(2)), source(SOURCES.get(2)) + "\nSELECT 42;\n", StandardCharsets.UTF_8);
        rejected(database, flyway(database, "filesystem:" + directory, head()), 4, latest);
        require(!relation(database, "platform.auth_rate_limits") && !relation(database, "platform.onboarding_runs"));
        // Original source remains valid, showing the negative changed only a disposable copy.
        var original = flyway(database, "classpath:db/migration", head()).validateWithResult();
        require(original.invalidMigrations.size() == latest - 3);
        for (var invalid : original.invalidMigrations)
            require(invalid.errorDetails.errorCode == org.flywaydb.core.api.CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED);
    }
    private static void above(String database, Path directory) throws Exception {
        legacy(database); require(execute(database, flyway(database, "classpath:db/migration", head()), 3, latest) == latest - 2);
        copySources(directory);
        Files.writeString(directory.resolve(String.format("V%03d__synthetic_above_head.sql", latest + 1)), "SELECT 42;\n", StandardCharsets.UTF_8);
        require(flyway(database, "filesystem:" + directory, String.valueOf(latest + 1)).migrate().migrationsExecuted == 1);
        rejected(database, flyway(database, "classpath:db/migration", head()), 3, latest);
        try (var c = open(database); var statement = c.createStatement();
                var rows = statement.executeQuery("select count(*) from platform.flyway_schema_history where type='SQL' and success")) {
            require(rows.next() && rows.getInt(1) == latest - 1);
        }
    }
    /**
     * Dono de funcao (ADR-0012): depois da migracao completa, platform.demo_provision pertence a cp_demo e a repeticao e aceita;
     * vinculo so de administracao em cp_demo (postgres do Supabase) e aceito; cp_demo com LOGIN, como membro de outra role, com membro INHERIT ou SET, com membro de runtime, funcao de outro dono ou SECURITY DEFINER fora de cp_demo sao recusados.
     * As alteracoes de role sao do cluster: cada negativo e desfeito antes do seguinte.
     */
    private static void owners(String database) throws Exception {
        legacy(database);
        var flyway = flyway(database, "classpath:db/migration", head());
        require(execute(database, flyway, 3, latest) == latest - 2);
        require(execute(database, flyway, 3, latest) == 0);
        try (var c = open(database)) {
            try (var s = c.createStatement(); var rows = s.executeQuery("select pg_get_userbyid(proowner),prosecdef from pg_proc where proname='demo_provision'")) {
                require(rows.next() && "cp_demo".equals(rows.getString(1)) && rows.getBoolean(2));
            }
            // Positivo: vínculo só de administração (o que o postgres do Supabase recebe ao criar cp_demo) é aceito.
            sql(c, "create role cp_demo_fixture nologin createrole; grant cp_demo to cp_demo_fixture with admin true, inherit false, set false");
            try { require(execute(database, flyway, 3, latest) == 0); } finally { sql(c, "revoke cp_demo from cp_demo_fixture; drop role cp_demo_fixture"); }
            for (String[] negative : new String[][] {
                    {"alter role cp_demo login", "alter role cp_demo nologin"},
                    {"create role cp_demo_fixture nologin; grant cp_demo_fixture to cp_demo", "revoke cp_demo_fixture from cp_demo; drop role cp_demo_fixture"},
                    {"create role cp_demo_fixture nologin; grant cp_demo to cp_demo_fixture with inherit true", "revoke cp_demo from cp_demo_fixture; drop role cp_demo_fixture"},
                    {"create role cp_demo_fixture nologin; grant cp_demo to cp_demo_fixture with set true", "revoke cp_demo from cp_demo_fixture; drop role cp_demo_fixture"},
                    {"grant cp_demo to cp_api with inherit false, set false", "revoke cp_demo from cp_api"},
                    {"create role cp_runtime_fixture nologin; grant cp_demo to cp_runtime_fixture with inherit false, set false", "revoke cp_demo from cp_runtime_fixture; drop role cp_runtime_fixture"},
                    {"create role cp_demo_fixture nologin; grant usage on schema platform to cp_demo_fixture; alter function platform.tenant_is_demo(uuid) owner to cp_demo_fixture",
                     "alter function platform.tenant_is_demo(uuid) owner to cp_demo; revoke usage on schema platform from cp_demo_fixture; drop role cp_demo_fixture"},
                    {"create function platform.synthetic_definer() returns int language sql security definer as 'select 1'", "drop function platform.synthetic_definer()"}}) {
                sql(c, negative[0]);
                try { rejected(database, flyway, 3, latest); } finally { sql(c, negative[1]); }
            }
        }
        require(execute(database, flyway, 3, latest) == 0);
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
        SOURCES = sources(); latest = SOURCES.size();
        require(latest >= 5);
        Path fixture = Path.of(args[0]).toAbsolutePath().normalize();
        require(Files.isDirectory(fixture) && fixture.getFileName().toString().startsWith("condpulse-dbops-jdbc-"));
        String prefix = "condpulse_dbops_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<String> created = new ArrayList<>();
        try (var admin = DriverManager.getConnection(url("condpulse_ci"), USER, PASSWORD)) {
            try (var statement = admin.createStatement(); var rows = statement.executeQuery("select current_database(),current_user")) {
                require(rows.next() && "condpulse_ci".equals(rows.getString(1)) && USER.equals(rows.getString(2)));
            }
            try {
                for (String suffix : List.of("positive", "normal", "checksum", "above", "gap", "resume", "owners")) {
                    String database = prefix + "_" + suffix; databaseName(database);
                    sql(admin, "CREATE DATABASE \"" + database + "\""); created.add(database);
                }
                positive(prefix + "_positive"); noBaseline(prefix + "_normal");
                checksum(prefix + "_checksum", fixture.resolve("checksum")); above(prefix + "_above", fixture.resolve("above"));
                gap(prefix + "_gap"); resume(prefix + "_resume"); owners(prefix + "_owners");
            } finally {
                for (String database : created.reversed()) {
                    databaseName(database); require(database.startsWith(prefix + "_"));
                    // Every fixture connection is closed by try-with-resources; never FORCE or terminate other sessions.
                    sql(admin, "DROP DATABASE \"" + database + "\"");
                }
            }
        }
        System.out.println("{\"passed\":true,\"positive\":" + (latest - 2) + ",\"replay\":0,\"missingBaselineDenied\":true,\"checksumDriftDenied\":true,"
            + "\"aboveHeadDenied\":true,\"gapDenied\":true,\"shortRangeDenied\":true,\"resumeFrom006\":" + (latest - 5) + ",\"demoOwnerAccepted\":true,\"foreignOwnerDenied\":true}");
    }
}
