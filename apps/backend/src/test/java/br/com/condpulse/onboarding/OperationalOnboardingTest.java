package br.com.condpulse.onboarding;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OperationalOnboardingTest {
    @TempDir Path temporary;
    private static final String PASSWORD = "SENTINEL_DATABASE_PASSWORD_MUST_NEVER_BE_OUTPUT";
    private static final String SECRET = "sb_secret_SENTINEL_ADMIN_SECRET_MUST_NEVER_BE_OUTPUT";
    private static final String URL = "jdbc:postgresql://db.rpnrqfrfwywglvyykgrg.supabase.co:5432/postgres?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt";
    private static Map<String, String> environment() {
        var values = new HashMap<String, String>();
        values.put("CP_ONBOARDING_PROJECT_REF", OnboardingManifest.PROJECT_REF);
        values.put("DATABASE_URL", URL); values.put("DATABASE_USER", "cp_onboarding_synthetic");
        values.put("DATABASE_PASSWORD", PASSWORD); values.put("CP_ONBOARDING_AUTH_SECRET_KEY", SECRET);
        return values;
    }
    private OnboardingManifest manifest() { return OnboardingManifest.parse(OnboardingManifestTest.fixture().getBytes(StandardCharsets.UTF_8)); }

    @Test void fileOnlyDefaultsToInspectionAndOutputContainsNoManifestOrCredentials() throws Exception {
        Path input = temporary.resolve("synthetic-private-manifest.json"); Files.writeString(input, OnboardingManifestTest.fixture());
        var output = new ByteArrayOutputStream();
        int status = OperationalOnboarding.run(new String[] {input.toString()}, environment(), new PrintStream(output), (config, manifest, mode, ack) -> {
            assertEquals("inspect", mode); assertNull(ack);
            assertEquals("OperationalOnboarding.Config[redacted]", config.toString());
            return new OperationalOnboarding.Outcome(true, false, false, manifest.fingerprint(config.databaseActor()),
                "NOT_ATTEMPTED", new OnboardingStore.Result(false, 2, 1, 2, 1), List.of());
        });
        assertEquals(0, status); String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"eligible\":true")); assertTrue(text.contains("\"applied\":false"));
        for (String forbidden : List.of(PASSWORD, SECRET, URL, input.toString(), "CONDOMINIO SINTETICO", "11000000-0000"))
            assertFalse(text.contains(forbidden));
    }

    @Test void anotherProjectUnsafeJdbcAndRuntimeIdentityFailBeforeAnyExecution() throws Exception {
        Path input = temporary.resolve("synthetic.json"); Files.writeString(input, OnboardingManifestTest.fixture());
        for (String url : List.of(URL.replace("verify-full", "require"), URL + "&sslmode=disable", URL + "&options=-crole=postgres",
                URL.replace(":5432", ":6543"), URL.replace("rpnrqfrfwywglvyykgrg", "bbbbbbbbbbbbbbbbbbbb"),
                URL.replace("db.rpnrqfrfwywglvyykgrg.supabase.co", "127.0.0.1"), URL + "#fragment",
                URL.replace("postgresql://", "postgresql://user:password@"))) {
            Map<String, String> env = environment(); env.put("DATABASE_URL", url);
            var output = new ByteArrayOutputStream();
            assertEquals(1, OperationalOnboarding.run(new String[] {input.toString()}, env, new PrintStream(output),
                (c, m, mode, a) -> { fail("Must not execute"); return null; }));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains(PASSWORD));
        }
        for (String user : List.of("postgres", "cp_ci_login", "cp_runtime_app", "cp_onboarding", "cp_api")) {
            Map<String, String> env = environment(); env.put("DATABASE_USER", user);
            assertThrows(IllegalArgumentException.class, () -> OperationalOnboarding.productionConfig(env));
        }
        Map<String, String> wrongProject = environment(); wrongProject.put("CP_ONBOARDING_PROJECT_REF", "bbbbbbbbbbbbbbbbbbbb");
        assertThrows(IllegalArgumentException.class, () -> OperationalOnboarding.productionConfig(wrongProject));
    }

    @Test void applyRequiresExactProjectAndOperatorBoundFingerprintBeforeAuthOrDatabase() {
        var plan = manifest(); var config = OperationalOnboarding.productionConfig(environment());
        for (String ack : new String[] {null, "", plan.fingerprint("cp_onboarding_another"),
                "bbbbbbbbbbbbbbbbbbbb:" + plan.fingerprint(config.databaseActor())}) {
            var result = OperationalOnboarding.execute(config, plan, "apply", ack,
                c -> { fail("Must not connect"); return null; }, (c, p) -> { fail("Must not call Auth"); return null; });
            assertEquals(List.of("ONBOARDING_ACK_REQUIRED"), result.codes());
            assertEquals("NOT_ATTEMPTED", result.writeOutcome());
        }
    }

    @Test void authFailureIsSanitizedAndHappensBeforeOpeningTheBusinessTransaction() {
        var plan = manifest(); var config = OperationalOnboarding.productionConfig(environment());
        var result = OperationalOnboarding.execute(config, plan, "inspect", null,
            c -> { fail("Auth must finish before database access"); return null; },
            (c, p) -> { throw new IllegalStateException("ONBOARDING_AUTH_UNAVAILABLE"); });
        assertEquals(List.of("ONBOARDING_AUTH_UNAVAILABLE"), result.codes());
        assertFalse(result.applied()); assertEquals("NOT_ATTEMPTED", result.writeOutcome());
    }
}
