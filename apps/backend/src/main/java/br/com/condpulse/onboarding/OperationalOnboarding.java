package br.com.condpulse.onboarding;

import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Isolated manual CLI. It never starts Spring, a server, migrations or an Auth mutation. */
public final class OperationalOnboarding {
    private static final String CA = "/run/secrets/db-ca.crt";
    private static final Set<String> CONTROLLED = Set.of("ONBOARDING_BUSY", "ONBOARDING_OPERATOR_NOT_AUTHORIZED",
        "ONBOARDING_AUTH_IDENTITY_INELIGIBLE", "ONBOARDING_AUTH_UNAVAILABLE", "ONBOARDING_AUTH_PROOF_EXPIRED",
        "ONBOARDING_MEMBERSHIP_EXPIRED", "ONBOARDING_OPERATION_REUSED", "ONBOARDING_ACCOUNT_NOT_ACTIVE",
        "ONBOARDING_TENANT_ALREADY_EXISTS", "ONBOARDING_STATE_DIVERGED", "ONBOARDING_AUDIT_NOT_WRITTEN");
    private OperationalOnboarding() { }

    public static final class Config {
        private final String url, username, password, databaseActor, authSecret;
        private Config(String url, String username, String password, String databaseActor, String authSecret) {
            this.url = url; this.username = username; this.password = password;
            this.databaseActor = databaseActor; this.authSecret = authSecret;
        }
        String databaseActor() { return databaseActor; }
        @Override public String toString() { return "OperationalOnboarding.Config[redacted]"; }
    }

    public record Outcome(boolean eligible, boolean applied, boolean alreadyApplied, String fingerprint,
            String writeOutcome, OnboardingStore.Result counts, List<String> codes) {
        public Outcome { codes = List.copyOf(codes); }
        boolean success() { return codes.isEmpty(); }
    }

    @FunctionalInterface interface Connector { Connection open(Config config) throws Exception; }
    @FunctionalInterface interface IdentityVerifier { AuthAdminVerifier.Eligibility verify(Config config, OnboardingManifest manifest); }
    @FunctionalInterface interface Executor { Outcome execute(Config config, OnboardingManifest manifest, String mode, String ack); }

    public static void main(String[] args) {
        java.util.logging.LogManager.getLogManager().reset();
        System.exit(run(args, System.getenv(), System.out));
    }

    public static int run(String[] args, Map<String, String> env, PrintStream output) {
        return run(args, env, output, OperationalOnboarding::execute);
    }

    static int run(String[] args, Map<String, String> env, PrintStream output, Executor executor) {
        Outcome outcome;
        try {
            String mode = args != null && args.length == 1 ? "inspect" : args != null && args.length == 2 ? args[0] : "";
            if (!("inspect".equals(mode) || "apply".equals(mode))) throw new IllegalArgumentException();
            Config config = productionConfig(env);
            Path input = Path.of(args[args.length - 1]);
            if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || Files.size(input) > OnboardingManifest.MAXIMUM_BYTES)
                throw new IllegalArgumentException();
            byte[] bytes;
            try (var stream = Files.newInputStream(input)) { bytes = stream.readNBytes(OnboardingManifest.MAXIMUM_BYTES + 1); }
            OnboardingManifest manifest = OnboardingManifest.parse(bytes);
            outcome = executor.execute(config, manifest, mode, env.get("CP_ONBOARDING_ACK"));
        } catch (Exception ignored) { outcome = failure("ONBOARDING_CONFIGURATION_OR_MANIFEST_INVALID", "NOT_ATTEMPTED", ""); }
        write(output, outcome);
        return outcome.success() ? 0 : 1;
    }

    static Config productionConfig(Map<String, String> env) {
        if (env == null || !OnboardingManifest.PROJECT_REF.equals(env.get("CP_ONBOARDING_PROJECT_REF"))) throw new IllegalArgumentException();
        String user = env.get("DATABASE_USER"), password = env.get("DATABASE_PASSWORD"), secret = env.get("CP_ONBOARDING_AUTH_SECRET_KEY");
        if (user == null || password == null || password.length() < 16 || password.length() > 4096
                || password.codePoints().anyMatch(Character::isISOControl)
                || secret == null || !secret.matches("sb_secret_[A-Za-z0-9_-]{24,4096}")) throw new IllegalArgumentException();
        String actor = user.endsWith("." + OnboardingManifest.PROJECT_REF)
            ? user.substring(0, user.length() - OnboardingManifest.PROJECT_REF.length() - 1) : user;
        if (!actor.matches("cp_onboarding_[a-z0-9_]{1,40}")) throw new IllegalArgumentException();
        String url = env.get("DATABASE_URL");
        if (!validUrl(url, user, actor)) throw new IllegalArgumentException();
        return new Config(url, user, password, actor, secret);
    }

    private static boolean validUrl(String value, String user, String actor) {
        try {
            if (value == null || !value.startsWith("jdbc:postgresql://")) return false;
            URI uri = URI.create(value.substring(5));
            if (!"postgresql".equals(uri.getScheme()) || uri.getPort() != 5432 || !"/postgres".equals(uri.getPath())
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() == null) return false;
            String direct = "db." + OnboardingManifest.PROJECT_REF + ".supabase.co";
            boolean host = direct.equals(uri.getHost()) ? user.equals(actor)
                : uri.getHost() != null && uri.getHost().matches("aws-[0-9]+-sa-east-1\\.pooler\\.supabase\\.com")
                    && user.equals(actor + "." + OnboardingManifest.PROJECT_REF);
            if (!host) return false;
            Map<String, String> parameters = new HashMap<>();
            for (String item : uri.getRawQuery().split("&", -1)) {
                String[] pair = item.split("=", -1);
                if (pair.length != 2) return false;
                String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8), data = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                if (parameters.put(name, data) != null) return false;
            }
            return parameters.equals(Map.of("sslmode", "verify-full", "sslrootcert", CA));
        } catch (Exception ignored) { return false; }
    }

    static Outcome execute(Config config, OnboardingManifest manifest, String mode, String ack) {
        return execute(config, manifest, mode, ack, OperationalOnboarding::connect,
            (configuration, plan) -> AuthAdminVerifier.forProject(plan.projectRef(), configuration.authSecret).verify(plan.accounts()));
    }

    static Outcome execute(Config config, OnboardingManifest manifest, String mode, String ack,
            Connector connector, IdentityVerifier identities) {
        String fingerprint = manifest.fingerprint(config.databaseActor);
        boolean apply = "apply".equals(mode), attempted = false;
        if (!(apply || "inspect".equals(mode))) return failure("ONBOARDING_COMMAND_INVALID", "NOT_ATTEMPTED", fingerprint);
        String expected = manifest.projectRef() + ":" + fingerprint;
        if (apply && (ack == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), ack.getBytes(StandardCharsets.UTF_8))))
            return failure("ONBOARDING_ACK_REQUIRED", "NOT_ATTEMPTED", fingerprint);
        try {
            // All provider requests complete BEFORE opening the business transaction.
            var proof = identities.verify(config, manifest);
            try (Connection connection = connector.open(config)) {
                attempted = apply;
                var counts = new OnboardingStore().execute(connection, manifest, config.databaseActor, apply, proof);
                return new Outcome(true, apply, counts.alreadyApplied(), fingerprint,
                    apply ? "COMMITTED" : "NOT_ATTEMPTED", counts, List.of());
            }
        } catch (IllegalStateException error) {
            String code = CONTROLLED.contains(error.getMessage()) ? error.getMessage() : "ONBOARDING_OPERATION_FAILED";
            return failure(code, attempted ? "UNKNOWN" : "NOT_ATTEMPTED", fingerprint);
        } catch (Exception ignored) {
            // A lost connection near COMMIT may have committed. Inspect the receipt, do not infer absence.
            return failure("ONBOARDING_DATABASE_OPERATION_FAILED", attempted ? "UNKNOWN" : "NOT_ATTEMPTED", fingerprint);
        }
    }

    private static Connection connect(Config config) throws Exception {
        Properties properties = new Properties(); properties.setProperty("user", config.username); properties.setProperty("password", config.password);
        properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "20");
        properties.setProperty("ApplicationName", "condpulse-operational-onboarding");
        return DriverManager.getConnection(config.url, properties);
    }

    private static Outcome failure(String code, String state, String fingerprint) {
        return new Outcome(false, false, false, fingerprint, state, null, List.of(code));
    }

    private static void write(PrintStream output, Outcome result) {
        String counts = result.counts == null ? "null" : "{\"accountsToCreate\":" + result.counts.accountsToCreate()
            + ",\"units\":" + result.counts.units() + ",\"memberships\":" + result.counts.memberships() + ",\"amenities\":" + result.counts.amenities() + "}";
        String codes = result.codes.isEmpty() ? "[]" : "[\"" + result.codes.getFirst() + "\"]";
        output.println("{\"eligible\":" + result.eligible + ",\"applied\":" + result.applied
            + ",\"alreadyApplied\":" + result.alreadyApplied + ",\"fingerprint\":\"" + result.fingerprint
            + "\",\"writeOutcome\":\"" + result.writeOutcome + "\",\"counts\":" + counts + ",\"codes\":" + codes + "}");
    }
}
