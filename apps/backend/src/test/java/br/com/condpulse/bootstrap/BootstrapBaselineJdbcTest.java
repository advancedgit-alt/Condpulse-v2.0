package br.com.condpulse.bootstrap;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Somente banco CI descartavel. Reconstroi o bootstrap das fontes Git; nenhum
 * dump, dado pessoal ou historico Flyway artificial participa das fixtures.
 */
@EnabledIfEnvironmentVariable(named = "CP_JDBC_TEST_OK", matches = "DISPOSABLE")
class BootstrapBaselineJdbcTest {
    /** Migrations revisadas apos o baseline oficial 002; atualizar junto de cada nova migration. */
    private static final List<Integer> POST_BASELINE_VERSIONS = List.of(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41);
    private static final String URL = "jdbc:postgresql://localhost:5432/condpulse_bootstrap_ci";
    private static final String USER = "postgres";
    private static final String PASSWORD = "condpulse_ci_only";
    private static final String PROJECT = "rpnrqfrfwywglvyykgrg";
    private static final String ADMIN_VERSION = "20261027210735";
    private static final String FOUNDATION_VERSION = "20261027225434";
    private static final String ADMIN_HASH = "6ec5e6a7509febe9bde6dda29257ce96ca0c86b28754c95700087a6ddc5d8ffd";
    private static final String FOUNDATION_HASH = "f5c95c7f1abfcc569dccf08be21b2fe896126fd140e6341f9244516330c9df2e";
    private static final BootstrapAttestor ATTESTOR = new BootstrapAttestor();
    private static boolean targetVerified;

    @BeforeAll
    static void requireDisposableTarget() throws Exception {
        assertEquals("DISPOSABLE", System.getenv("CP_DB_TEST_OK"));
        try (var connection = open()) { assertDisposable(connection); targetVerified = true; }
    }

    @BeforeEach
    void restoreBootstrap() throws Exception { reset(); }

    @AfterAll
    static void cleanup() throws Exception {
        if (!targetVerified) return;
        try (var connection = open()) { dropOwnedSchemas(connection); }
    }

    @Test
    void inspectionIsReadOnlyAndCanonicalFingerprintIsStable() throws Exception {
        var first = inspect();
        var second = inspect();
        assertTrue(first.eligible(), () -> "Atestacao inicial: " + first.codes());
        assertTrue(second.eligible());
        assertTrue(first.codes().isEmpty());
        assertTrue(first.fingerprint().matches("[a-f0-9]{64}"));
        assertEquals(first.fingerprint(), second.fingerprint());
        assertFalse(historyExists());
        assertFalse(relationExists("platform.web_sessions"));
        assertNativeHistoryHashes();
    }

    @Test
    void structuralPolicyFunctionAndPrivilegeDriftBlockBaselineBeforeDdl() throws Exception {
        var mutations = List.of(
            "ALTER TABLE cp.units ADD COLUMN ci_drift text",
            "ALTER TABLE cp.units NO FORCE ROW LEVEL SECURITY",
            "GRANT SELECT ON cp.units TO PUBLIC",
            "GRANT SELECT(label) ON cp.units TO cp_worker",
            "CREATE INDEX ci_extra_index ON cp.units(label)",
            "ALTER TABLE cp.units DROP CONSTRAINT units_label_check",
            "CREATE OR REPLACE FUNCTION platform.current_actor_id() RETURNS uuid LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog AS $$ SELECT NULL::uuid $$"
        );
        for (String mutation : mutations) {
            reset();
            String ack = ack(inspect().fingerprint());
            executeSql(mutation);
            var inspection = inspect();
            assertFalse(inspection.eligible(), "Uma alteracao estrutural nao pode ser aceita");
            assertFalse(inspection.codes().isEmpty());
            assertControlledCodes(inspection.codes());
            var outcome = BootstrapBaseline.execute(config(), "baseline", ack);
            assertFalse(outcome.baselineCreated());
            assertFalse(outcome.codes().isEmpty());
            assertFalse(historyExists(), "Drift deve falhar antes da criacao do marcador");
        }
    }

    @Test
    void changedOrExtraNativeMigrationBlocksBaseline() throws Exception {
        String acceptedAck = ack(inspect().fingerprint());
        executeSql("UPDATE supabase_migrations.schema_migrations SET statements=ARRAY['CI_NATIVE_HISTORY_DRIFT'] WHERE version='20261027225434'");
        assertFalse(inspect().eligible());
        assertFalse(BootstrapBaseline.execute(config(), "baseline", acceptedAck).baselineCreated());
        assertFalse(historyExists());
        reset();
        acceptedAck = ack(inspect().fingerprint());
        executeSql("INSERT INTO supabase_migrations.schema_migrations VALUES ('20990101000000','ci_unapproved',ARRAY['SELECT 1'])");
        assertFalse(inspect().eligible());
        assertFalse(BootstrapBaseline.execute(config(), "baseline", acceptedAck).baselineCreated());
        assertFalse(historyExists());
    }

    @Test
    void missingStaleOrOtherProjectAcknowledgementCannotWriteHistory() throws Exception {
        var inspection = inspect();
        assertTrue(inspection.eligible());
        for (String invalidAck : List.of("", "another-project:002:" + inspection.fingerprint(),
                PROJECT + ":003:" + inspection.fingerprint(), PROJECT + ":002:" + "0".repeat(64))) {
            var outcome = BootstrapBaseline.execute(config(), "baseline", invalidAck);
            assertFalse(outcome.baselineCreated());
            assertTrue(outcome.codes().contains("BASELINE_ACK_REQUIRED"));
            assertFalse(historyExists());
        }
        assertThrows(RuntimeException.class, () -> BootstrapBaseline.disposableConfig(
            "jdbc:postgresql://localhost:5432/postgres", USER, PASSWORD));
    }

    @Test
    void officialBaselineWritesOnlyVersionTwoAndSeparateMigrateAppliesReviewedMigrations() throws Exception {
        var inspected = BootstrapBaseline.execute(config(), "inspect", "");
        assertTrue(inspected.eligible());
        assertFalse(inspected.baselineCreated());
        assertFalse(historyExists());
        var baselined = BootstrapBaseline.execute(config(), "baseline", ack(inspected.fingerprint()));
        assertTrue(baselined.baselineCreated(), () -> "Baseline: " + baselined.codes());
        assertTrue(baselined.codes().isEmpty());
        assertOfficialMarkerOnly();
        assertFalse(relationExists("platform.web_sessions"), "Baseline nunca executa V003");

        var repeated = BootstrapBaseline.execute(config(), "baseline", ack(inspected.fingerprint()));
        assertFalse(repeated.baselineCreated());
        assertFalse(repeated.codes().isEmpty());
        assertOfficialMarkerOnly();

        var flyway = Flyway.configure().dataSource(URL, USER, PASSWORD)
            .defaultSchema("platform").schemas("platform", "cp").createSchemas(false)
            .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true)
            .validateMigrationNaming(true).validateOnMigrate(true).load();
        // Antes da primeira migracao, a validacao estrita deve apontar somente as
        // migrations revisadas posteriores ao baseline. Nunca ignorar validacao ou drift aplicado.
        var expected = POST_BASELINE_VERSIONS.stream().map(BigInteger::valueOf).toList();
        var beforeMigration = flyway.validateWithResult();
        assertFalse(beforeMigration.validationSuccessful);
        assertEquals(expected.size(), beforeMigration.invalidMigrations.size());
        assertEquals(expected,
            beforeMigration.invalidMigrations.stream().map(m->new BigInteger(m.version)).sorted().toList());
        for(var unapplied:beforeMigration.invalidMigrations)
            assertEquals(CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED, unapplied.errorDetails.errorCode);
        var pending = flyway.info().pending();
        assertEquals(expected.size(), pending.length);
        assertEquals(expected,
            java.util.Arrays.stream(pending).map(m->new BigInteger(m.getVersion().toString())).sorted().toList());
        assertEquals(expected.size(), flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertTrue(relationExists("platform.web_sessions"));
        assertTrue(relationExists("platform.auth_rate_limits"));
        assertTrue(relationExists("platform.onboarding_runs"));
        assertEquals(1L, scalarLong("SELECT count(*) FROM platform.flyway_schema_history WHERE type='SQL' AND version IN ('003','3') AND success AND checksum IS NOT NULL"));
        assertEquals(0, flyway.migrate().migrationsExecuted, "Replay nao duplica migrations");
    }

    @Test
    void simultaneousExecutorsProduceOneOfficialBaselineMarker() throws Exception {
        String acceptedAck = ack(inspect().fingerprint());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return BootstrapBaseline.execute(config(), "baseline", acceptedAck); });
            var second = executor.submit(() -> { start.await(); return BootstrapBaseline.execute(config(), "baseline", acceptedAck); });
            start.countDown();
            var outcomes = List.of(first.get(45, TimeUnit.SECONDS), second.get(45, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(BootstrapBaseline.Outcome::baselineCreated).count());
            assertEquals(1, outcomes.stream().filter(value -> !value.codes().isEmpty()).count());
            assertOfficialMarkerOnly();
        }
    }

    @Test
    void occupiedSessionLockDoesNotCreateHistoryAndIsReleasedWithItsConnection() throws Exception {
        String acceptedAck = ack(inspect().fingerprint());
        try (var lock = open(); var statement = lock.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, BootstrapBaseline.LOCK_KEY);
            statement.execute();
            var blocked = BootstrapBaseline.execute(config(), "baseline", acceptedAck);
            assertFalse(blocked.baselineCreated());
            assertFalse(blocked.codes().isEmpty());
            assertFalse(historyExists());
        }
        var outcome = BootstrapBaseline.execute(config(), "baseline", acceptedAck);
        assertTrue(outcome.baselineCreated(), () -> "Lock deveria estar liberado: " + outcome.codes());
        assertOfficialMarkerOnly();
    }

    @Test
    void authenticationFailureReturnsOnlyControlledCodes() throws Exception {
        String sentinel = "CI_SQL_SECRET_SENTINEL_NEVER_ECHO_123456";
        var wrongPassword = BootstrapBaseline.disposableConfig(URL, USER, sentinel);
        var failed = BootstrapBaseline.execute(wrongPassword, "inspect", "");
        assertFalse(failed.eligible());
        assertFalse(failed.baselineCreated());
        assertFalse(failed.codes().isEmpty());
        assertControlledCodes(failed.codes());
        assertFalse(failed.toString().contains(sentinel));
        assertFalse(failed.toString().contains("PSQLException"));
        assertFalse(historyExists());
    }

    private static BootstrapBaseline.Config config() {
        return BootstrapBaseline.disposableConfig(URL, USER, PASSWORD);
    }

    private static BootstrapAttestor.Attestation inspect() throws Exception {
        try (var connection = open()) { return ATTESTOR.inspect(connection); }
    }

    private static String ack(String fingerprint) { return PROJECT + ":002:" + fingerprint; }

    private static Connection open() throws Exception {
        var connection = DriverManager.getConnection(URL, USER, PASSWORD);
        try {
            assertDisposable(connection);
            return connection;
        } catch (Exception failure) {
            connection.close();
            throw failure;
        }
    }

    private static void assertDisposable(Connection connection) throws Exception {
        if (!"DISPOSABLE".equals(System.getenv("CP_DB_TEST_OK"))
                || !URL.equals(connection.getMetaData().getURL())
                || !"condpulse_bootstrap_ci".equals(connection.getCatalog())) {
            connection.close();
            throw new IllegalStateException("DISPOSABLE_DATABASE_REQUIRED");
        }
    }

    private static void dropOwnedSchemas(Connection connection) throws Exception {
        assertDisposable(connection);
        try (var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS cp CASCADE; DROP SCHEMA IF EXISTS platform CASCADE; DROP SCHEMA IF EXISTS supabase_migrations CASCADE");
        }
    }

    private static void reset() throws Exception {
        try (var connection = open()) {
            dropOwnedSchemas(connection);
            try (var statement = connection.createStatement()) {
                // Emula apenas a extensao gerenciada preexistente no Supabase.
                statement.execute("CREATE SCHEMA IF NOT EXISTS extensions; CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions");
                statement.execute(resource("/db/migration/V001__foundation.sql"));
                statement.execute(resource("/db/migration/V002__roles_and_rls.sql"));
                statement.execute("CREATE SCHEMA supabase_migrations; CREATE TABLE supabase_migrations.schema_migrations(version text PRIMARY KEY,name text NOT NULL,statements text[] NOT NULL)");
            }
            insertHistory(connection, ADMIN_VERSION, "condpulse_bootstrap_restrict_rls_event_function", adminStatement());
            insertHistory(connection, FOUNDATION_VERSION, "condpulse_foundation_v001_v002_atomic_bootstrap", foundationStatement());
        }
        assertNativeHistoryHashes();
    }

    private static void insertHistory(Connection connection, String version, String name, String statement) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO supabase_migrations.schema_migrations(version,name,statements) VALUES (?,?,?)")) {
            var statements = connection.createArrayOf("text", new String[] { statement });
            try {
                insert.setString(1, version); insert.setString(2, name); insert.setArray(3, statements); insert.executeUpdate();
            } finally { statements.free(); }
        }
    }

    private static void assertNativeHistoryHashes() throws Exception {
        Map<String, String> expected = Map.of(ADMIN_VERSION, ADMIN_HASH, FOUNDATION_VERSION, FOUNDATION_HASH);
        try (var connection = open(); var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT version,encode(sha256(convert_to(to_jsonb(statements)::text,'UTF8')),'hex') AS digest FROM supabase_migrations.schema_migrations")) {
            int count = 0;
            while (rows.next()) { assertEquals(expected.get(rows.getString(1)), rows.getString(2)); count++; }
            assertEquals(2, count);
        }
    }

    private static void assertOfficialMarkerOnly() throws Exception {
        try (var connection = open(); var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT version,type,success,checksum FROM platform.flyway_schema_history ORDER BY installed_rank")) {
            assertTrue(rows.next());
            assertEquals(BigInteger.TWO, new BigInteger(rows.getString("version")));
            assertEquals("BASELINE", rows.getString("type"));
            assertTrue(rows.getBoolean("success"));
            assertNull(rows.getObject("checksum"));
            assertFalse(rows.next());
        }
    }

    private static boolean historyExists() throws Exception { return relationExists("platform.flyway_schema_history"); }

    private static boolean relationExists(String relation) throws Exception {
        try (var connection = open(); var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, relation);
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getBoolean(1); }
        }
    }

    private static long scalarLong(String sql) throws Exception {
        try (var connection = open(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    private static void executeSql(String sql) throws Exception {
        try (var connection = open(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static void assertControlledCodes(List<String> codes) {
        assertTrue(codes.stream().allMatch(code -> code.matches("[A-Z][A-Z0-9_]{1,80}")));
    }

    private static String resource(String name) throws Exception {
        try (InputStream stream = BootstrapBaselineJdbcTest.class.getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("FIXTURE_RESOURCE_MISSING");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String foundationStatement() throws Exception {
        String prefix = String.join("\n", "DO $cp_bootstrap$", "BEGIN",
            " IF EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('cp','platform') AND c.relkind IN ('r','p','v','m','S','f')) THEN RAISE EXCEPTION 'Schemas de negocio nao estao vazios; bootstrap cancelado'; END IF;",
            " EXECUTE $cp_source$");
        String suffix = "$cp_source$;\nEND\n$cp_bootstrap$;";
        return prefix + "\n" + resource("/db/migration/V001__foundation.sql") + "\n\n"
            + resource("/db/migration/V002__roles_and_rls.sql") + "\n\n" + suffix;
    }

    private static String adminStatement() {
        return String.join("\n",
            "-- Hardening do recurso gerenciado, fora dos schemas de negocio controlados por Flyway.",
            "-- Mantem a funcao e seu event trigger; remove somente acesso RPC de usuarios finais.",
            "DO $$", "BEGIN",
            "  IF to_regprocedure('public.rls_auto_enable()') IS NOT NULL THEN",
            "    EXECUTE 'REVOKE EXECUTE ON FUNCTION public.rls_auto_enable() FROM PUBLIC, anon, authenticated';",
            "  END IF;", "END", "$$;");
    }
}
