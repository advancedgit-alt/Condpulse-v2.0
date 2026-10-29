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
public final class ProductionOperationsHarness {
    private static final String USER = "postgres", PASSWORD = "condpulse_ci_only";
    private static List<String> SOURCES;
    private static int latest;
    private static String host, port;
    private static void require(boolean ok) { if (!ok) throw new AssertionError("DISPOSABLE_GUARD_OR_ASSERTION_FAILED"); }
    private static void databaseName(String value) { require(value != null && value.matches("condpulse_prodops_[a-f0-9]{12}_(fresh|gap|legacy|checksum|above)")); }
    private static String url(String database) { return "jdbc:postgresql://" + host + ":" + port + "/" + database; }
    private static Connection open(String database) throws Exception { databaseName(database); return DriverManager.getConnection(url(database), USER, PASSWORD); }
    /** Todas as migrations versionadas do JAR revisado, em ordem; a ultima define o alvo do guard. */
    private static List<String> sources() throws Exception {
        var location = ProductionOperationsHarness.class.getResource("/db/migration");
        require(location != null && "file".equals(location.getProtocol()));
        try (var files = Files.list(Path.of(location.toURI()))) {
            var names = files.map(path -> path.getFileName().toString()).sorted().toList();
            require(!names.isEmpty() && names.stream().allMatch(name -> name.matches("V[0-9]{3}__[a-z0-9_]+\\.sql")));
            for (int index = 0; index < names.size(); index++) require(names.get(index).startsWith(String.format("V%03d__", index + 1)));
            return names;
        }
    }
    private static String source(String name) throws Exception {
        require(SOURCES.contains(name));
        try (var stream = ProductionOperationsHarness.class.getResourceAsStream("/db/migration/" + name)) {
            require(stream != null); return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static boolean relation(String database, String name) throws Exception {
        try (var c = open(database); var statement = c.prepareStatement("select to_regclass(?) is not null")) {
            statement.setString(1, name); try (var rows = statement.executeQuery()) { require(rows.next()); return rows.getBoolean(1); }
        }
    }
    private static Flyway flyway(String database, String location, int target) {
        databaseName(database);
        return Flyway.configure().dataSource(url(database), USER, PASSWORD)
            .defaultSchema("platform").schemas("platform", "cp").createSchemas(true)
            .locations(location).baselineOnMigrate(false).cleanDisabled(true).skipDefaultCallbacks(true)
            .validateMigrationNaming(true).validateOnMigrate(true).outOfOrder(false).target(String.valueOf(target))
            .loggers(BootstrapBaseline.QuietLogCreator.class.getName()).load();
    }
    private static int migrate(String database, Flyway flyway, int from, int to) throws Exception {
        try (var c = open(database)) { return ProductionDatabaseGuard.migrate(c, flyway, from, to); }
    }
    /** Recusa pelas regras do guard (IllegalStateException), nunca por outro erro. */
    private static void rejected(String database, Flyway flyway, int from, int to) throws Exception {
        boolean denied = false;
        try { migrate(database, flyway, from, to); } catch (IllegalStateException expected) { denied = true; }
        require(denied);
    }
    private static void copySources(Path directory) throws Exception {
        Files.createDirectories(directory);
        for (String name : SOURCES) Files.writeString(directory.resolve(name), source(name), StandardCharsets.UTF_8);
    }
    /** Banco novo: faixa curta recusada, migracao completa, repeticao com zero e inspecao coerente. */
    private static void fresh(String database) throws Exception {
        try (var c = open(database)) {
            require(ProductionDatabaseGuard.inspect(c).equals("{\"inspected\":true,\"actor\":\"postgres\",\"ssl\":false,\"schemas\":0,\"tables\":0,\"appliedVersions\":0,\"lastVersion\":0}"));
        }
        rejected(database, flyway(database, "classpath:db/migration", latest - 1), 1, latest - 1);
        require(!relation(database, "platform.flyway_schema_history"));
        var flyway = flyway(database, "classpath:db/migration", latest);
        require(migrate(database, flyway, 1, latest) == latest);
        require(migrate(database, flyway, 1, latest) == 0);
        require(relation(database, "platform.web_sessions") && relation(database, "platform.onboarding_runs"));
        try (var c = open(database)) {
            String inspected = ProductionDatabaseGuard.inspect(c);
            require(inspected.contains("\"appliedVersions\":" + latest + ",") && inspected.endsWith("\"lastVersion\":" + latest + "}"));
        }
    }
    /** Faixa que comeca depois do que ja foi aplicado e recusada sem escrever. */
    private static void gap(String database) throws Exception {
        rejected(database, flyway(database, "classpath:db/migration", latest), 2, latest);
        require(!relation(database, "platform.flyway_schema_history") && !relation(database, "platform.web_sessions"));
    }
    /** Historico com baseline legado (homologacao) nao e banco novo: recusado. */
    private static void legacy(String database) throws Exception {
        try (var c = open(database); var statement = c.createStatement()) {
            statement.execute(source(SOURCES.get(0))); statement.execute(source(SOURCES.get(1)));
        }
        Flyway.configure().dataSource(url(database), USER, PASSWORD).defaultSchema("platform").schemas("platform", "cp").createSchemas(false)
            .locations("classpath:db/migration").baselineVersion("002").cleanDisabled(true)
            .loggers(BootstrapBaseline.QuietLogCreator.class.getName()).load().baseline();
        rejected(database, flyway(database, "classpath:db/migration", latest), 3, latest);
        require(!relation(database, "platform.web_sessions"));
    }
    private static void checksum(String database, Path directory) throws Exception {
        require(flyway(database, "classpath:db/migration", 1).migrate().migrationsExecuted == 1);
        copySources(directory);
        Files.writeString(directory.resolve(SOURCES.get(0)), source(SOURCES.get(0)) + "\nSELECT 42;\n", StandardCharsets.UTF_8);
        rejected(database, flyway(database, "filesystem:" + directory, latest), 2, latest);
        require(!relation(database, "platform.web_sessions"));
    }
    private static void above(String database, Path directory) throws Exception {
        require(migrate(database, flyway(database, "classpath:db/migration", latest), 1, latest) == latest);
        copySources(directory);
        Files.writeString(directory.resolve(String.format("V%03d__synthetic_above_head.sql", latest + 1)), "SELECT 42;\n", StandardCharsets.UTF_8);
        require(flyway(database, "filesystem:" + directory, latest + 1).migrate().migrationsExecuted == 1);
        rejected(database, flyway(database, "classpath:db/migration", latest), 1, latest);
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
        require(Files.isDirectory(fixture) && fixture.getFileName().toString().startsWith("condpulse-prodops-jdbc-"));
        String prefix = "condpulse_prodops_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<String> created = new ArrayList<>();
        try (var admin = DriverManager.getConnection(url("condpulse_ci"), USER, PASSWORD)) {
            try (var statement = admin.createStatement(); var rows = statement.executeQuery("select current_database(),current_user")) {
                require(rows.next() && "condpulse_ci".equals(rows.getString(1)) && USER.equals(rows.getString(2)));
            }
            try {
                for (String suffix : List.of("fresh", "gap", "legacy", "checksum", "above")) {
                    String database = prefix + "_" + suffix; databaseName(database);
                    try (var statement = admin.createStatement()) { statement.execute("CREATE DATABASE \"" + database + "\""); }
                    created.add(database);
                }
                fresh(prefix + "_fresh"); gap(prefix + "_gap"); legacy(prefix + "_legacy");
                checksum(prefix + "_checksum", fixture.resolve("checksum")); above(prefix + "_above", fixture.resolve("above"));
            } finally {
                for (String database : created.reversed()) {
                    databaseName(database); require(database.startsWith(prefix + "_"));
                    try (var statement = admin.createStatement()) { statement.execute("DROP DATABASE \"" + database + "\""); }
                }
            }
        }
        System.out.println("{\"passed\":true,\"fresh\":" + latest + ",\"replay\":0,\"shortRangeDenied\":true,\"gapDenied\":true,"
            + "\"legacyBaselineDenied\":true,\"checksumDriftDenied\":true,\"aboveHeadDenied\":true}");
    }
}
