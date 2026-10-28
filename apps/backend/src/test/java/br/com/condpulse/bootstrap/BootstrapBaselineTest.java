package br.com.condpulse.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BootstrapBaselineTest {
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String SECRET = "SENTINEL_DATABASE_PASSWORD_never_output_123";
    private static final String URL = "jdbc:postgresql://db." + BootstrapBaseline.PROJECT_REF
        + ".supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt";
    private static Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        env.put("CP_BASELINE_PROJECT_REF", BootstrapBaseline.PROJECT_REF);
        env.put("DATABASE_URL", URL); env.put("DATABASE_USER", "postgres"); env.put("DATABASE_PASSWORD", SECRET);
        return env;
    }
    private static BootstrapBaseline.Config config() { return BootstrapBaseline.productionConfig(environment()); }
    private static BootstrapAttestor.Attestation eligible() { return new BootstrapAttestor.Attestation(true, FINGERPRINT, List.of()); }

    private static final class Guard {
        final Connection connection = mock(Connection.class);
        final PreparedStatement lock = mock(PreparedStatement.class);
        final PreparedStatement unlock = mock(PreparedStatement.class);
        final ResultSet locked = mock(ResultSet.class);
        Guard(boolean acquired) throws SQLException {
            when(connection.prepareStatement("select pg_try_advisory_lock(?)")).thenReturn(lock);
            when(connection.prepareStatement("select pg_advisory_unlock(?)")).thenReturn(unlock);
            when(lock.executeQuery()).thenReturn(locked);
            when(locked.next()).thenReturn(true); when(locked.getBoolean(1)).thenReturn(acquired);
        }
        void marker(String version, String type, boolean success, boolean extraRow) throws SQLException {
            PreparedStatement statement = mock(PreparedStatement.class); ResultSet rows = mock(ResultSet.class);
            when(connection.prepareStatement("select version,type,success from platform.flyway_schema_history order by installed_rank")).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(rows);
            when(rows.next()).thenReturn(true, extraRow, false);
            when(rows.getString("version")).thenReturn(version); when(rows.getString("type")).thenReturn(type);
            when(rows.getBoolean("success")).thenReturn(success); when(rows.wasNull()).thenReturn(false);
        }
    }

    @Test void cliDefaultsToInspectAndDoesNotEchoConfiguration() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); AtomicBoolean inspected = new AtomicBoolean();
        int status = BootstrapBaseline.run(new String[0], environment(), new PrintStream(bytes), (config, mode, ack) -> {
            assertEquals("inspect", mode); assertNull(ack); inspected.set(true);
            assertEquals("BootstrapBaseline.Config[redacted]", config.toString());
            return new BootstrapBaseline.Outcome(true, false, FINGERPRINT, List.of());
        });
        assertEquals(0, status); assertTrue(inspected.get());
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\"baselineCreated\":false")); assertTrue(output.contains(FINGERPRINT));
        assertFalse(output.contains(SECRET)); assertFalse(output.contains(URL)); assertFalse(output.contains("postgres"));
    }

    @Test void malformedTargetAndUnknownCommandFailBeforeOpeningAnyConnection() {
        for (String url : List.of(URL.replace("verify-full", "require"), URL + "&sslmode=disable", URL + "&password=" + SECRET,
                URL.replace(":5432", ":6543"), URL.replace(BootstrapBaseline.PROJECT_REF, "bbbbbbbbbbbbbbbbbbbb"),
                URL.replace("db." + BootstrapBaseline.PROJECT_REF + ".supabase.co", "localhost"),
                URL.replace("postgresql://", "postgresql://postgres:" + SECRET + "@"), URL + "#fragment")) {
            Map<String, String> env = environment(); env.put("DATABASE_URL", url);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int status = BootstrapBaseline.run(new String[] {"baseline"}, env, new PrintStream(bytes), (c, m, a) -> { fail("Nao pode conectar"); return null; });
            assertEquals(1, status); assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("DATABASE_CONFIGURATION_INVALID"));
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains(SECRET));
        }
        for (String command : List.of("migrate", "repair", "clean", SECRET)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertEquals(1, BootstrapBaseline.run(new String[] {command}, environment(), new PrintStream(bytes), (c,m,a) -> { fail(); return null; }));
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains(SECRET));
        }
    }

    @Test void onlyTheRecordedProjectAndDdlIdentityAreAcceptedEvenWithDisposableEnvironmentFlag() {
        Map<String, String> env = environment(); env.put("CP_BASELINE_PROJECT_REF", "bbbbbbbbbbbbbbbbbbbb");
        assertThrows(RuntimeException.class, () -> BootstrapBaseline.productionConfig(env));
        for (String user : List.of("cp_runtime_app", "cp_api", "postgres." + BootstrapBaseline.PROJECT_REF)) {
            Map<String,String> candidate = environment(); candidate.put("DATABASE_USER", user);
            assertThrows(RuntimeException.class, () -> BootstrapBaseline.productionConfig(candidate));
        }
        Map<String,String> loopback = environment(); loopback.put("CP_DB_TEST_OK", "DISPOSABLE");
        loopback.put("DATABASE_URL", "jdbc:postgresql://localhost:5432/condpulse_bootstrap_ci");
        assertThrows(RuntimeException.class, () -> BootstrapBaseline.productionConfig(loopback));
        Map<String,String> pooler = environment();
        pooler.put("DATABASE_URL", URL.replace("db." + BootstrapBaseline.PROJECT_REF + ".supabase.co", "aws-0-sa-east-1.pooler.supabase.com"));
        pooler.put("DATABASE_USER", "cp_migrator_owner." + BootstrapBaseline.PROJECT_REF);
        assertNotNull(BootstrapBaseline.productionConfig(pooler));
        pooler.put("DATABASE_USER", "cp_migrator_owner.bbbbbbbbbbbbbbbbbbbb");
        assertThrows(RuntimeException.class, () -> BootstrapBaseline.productionConfig(pooler));
    }

    @Test void inspectIsReadOnlyAndNeverInvokesBaseline() throws Exception {
        Guard guard = new Guard(true); AtomicBoolean changed = new AtomicBoolean();
        BootstrapBaseline.Outcome result = BootstrapBaseline.execute(config(), "inspect", null, c -> guard.connection,
            c -> eligible(), c -> changed.set(true));
        assertTrue(result.success()); assertTrue(result.eligible()); assertFalse(result.baselineCreated()); assertFalse(changed.get());
        verify(guard.connection).setReadOnly(true); verify(guard.connection).setAutoCommit(false);
        verify(guard.connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(guard.unlock).setLong(1, BootstrapBaseline.LOCK_KEY); verify(guard.connection).close();
    }

    @Test void refusalAndMissingOrStaleAckPerformNoDdl() throws Exception {
        for (String ack : new String[] {null, "", BootstrapBaseline.PROJECT_REF + ":003:" + FINGERPRINT,
                BootstrapBaseline.PROJECT_REF + ":002:" + "b".repeat(64)}) {
            Guard guard = new Guard(true); AtomicBoolean changed = new AtomicBoolean();
            BootstrapBaseline.Outcome result = BootstrapBaseline.execute(config(), "baseline", ack, c -> guard.connection,
                c -> eligible(), c -> changed.set(true));
            assertEquals(List.of("BASELINE_ACK_REQUIRED"), result.codes()); assertFalse(changed.get());
        }
        Guard guard = new Guard(true); AtomicBoolean changed = new AtomicBoolean();
        BootstrapBaseline.Outcome refused = BootstrapBaseline.execute(config(), "baseline", "anything", c -> guard.connection,
            c -> new BootstrapAttestor.Attestation(false, FINGERPRINT, List.of("DATABASE_DRIFT")), c -> changed.set(true));
        assertEquals(List.of("ATTESTATION_FAILED"), refused.codes()); assertFalse(changed.get());
    }

    @Test void competingExecutorFailsImmediatelyAndNeverInspectsOrWrites() throws Exception {
        Guard guard = new Guard(false);
        BootstrapBaseline.Outcome result = BootstrapBaseline.execute(config(), "baseline", "anything", c -> guard.connection,
            c -> { fail("Lock ocupado nao permite inspecao"); return null; }, c -> fail("Lock ocupado nao permite DDL"));
        assertEquals(List.of("BASELINE_BUSY"), result.codes()); verify(guard.connection).close();
        verify(guard.unlock, never()).execute();
    }

    @Test void fingerprintMustBeAnExactSha256BeforeAnyWrite() throws Exception {
        for (String value : new String[] {null, "", "z".repeat(64), "A".repeat(64), FINGERPRINT + "\n"}) {
            Guard guard = new Guard(true); AtomicBoolean changed = new AtomicBoolean();
            BootstrapBaseline.Outcome result = BootstrapBaseline.execute(config(), "baseline", "anything", c -> guard.connection,
                c -> new BootstrapAttestor.Attestation(true, value, List.of()), c -> changed.set(true));
            assertEquals(List.of("ATTESTATION_RESULT_INVALID"), result.codes()); assertFalse(changed.get());
        }
    }

    @Test void explicitAckAllowsOnlyBaselineAndRequiresOneVerifiedOfficialMarker() throws Exception {
        Guard guard = new Guard(true); guard.marker("2", "BASELINE", true, false); AtomicBoolean changed = new AtomicBoolean();
        String ack = BootstrapBaseline.PROJECT_REF + ":002:" + FINGERPRINT;
        BootstrapBaseline.Outcome result = BootstrapBaseline.execute(config(), "baseline", ack, c -> guard.connection,
            c -> eligible(), c -> changed.set(true));
        assertTrue(changed.get()); assertTrue(result.baselineCreated()); assertTrue(result.success());
        Guard invalid = new Guard(true); invalid.marker("3", "SQL", true, false);
        BootstrapBaseline.Outcome unverified = BootstrapBaseline.execute(config(), "baseline", ack, c -> invalid.connection,
            c -> eligible(), c -> { });
        assertFalse(unverified.success()); assertFalse(unverified.baselineCreated());
        assertEquals(List.of("BASELINE_MARKER_INVALID"), unverified.codes());
    }

    @Test void failuresAfterStartingFlywayAreUnknownAndDoNotEchoExceptionText() throws Exception {
        Guard guard = new Guard(true);
        BootstrapBaseline.Outcome result = BootstrapBaseline.execute(config(), "baseline", BootstrapBaseline.PROJECT_REF + ":002:" + FINGERPRINT,
            c -> guard.connection, c -> eligible(), c -> { throw new SQLException(SECRET); });
        assertEquals(List.of("BASELINE_RESULT_UNKNOWN"), result.codes()); assertFalse(result.baselineCreated());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertEquals(1, BootstrapBaseline.run(new String[] {"baseline"}, environment(), new PrintStream(bytes), (c,m,a) -> { throw new RuntimeException(SECRET); }));
        assertFalse(bytes.toString(StandardCharsets.UTF_8).contains(SECRET));
    }
}
