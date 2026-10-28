import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Pure runtime guard: no network, Spring, database connection or credential logging. */
public final class VercelRuntimePreflight {
    private static final String PROJECT = "rpnrqfrfwywglvyykgrg";
    private static final String ISSUER = "https://" + PROJECT + ".supabase.co/auth/v1";
    private static final String CERT = "/tmp/condpulse-db-ca.crt";

    private VercelRuntimePreflight() {}

    public static void main(String[] args) {
        try {
            require(args.length == 0, "VERCEL_API_ARGUMENTS_FORBIDDEN");
            validate(System.getenv());
        } catch (Rejected error) {
            System.err.println(error.code);
            System.exit(1);
        } catch (RuntimeException error) {
            System.err.println("VERCEL_API_PREFLIGHT_INVALID");
            System.exit(1);
        }
    }

    static void validate(Map<String, String> env) {
        require("api".equals(env.getOrDefault("SPRING_PROFILES_ACTIVE", "api")), "VERCEL_API_PROFILE_EXACT");
        for (String key : env.keySet()) {
            require(!key.startsWith("CP_MIGRATION_") && !key.startsWith("CP_ONBOARDING_"), "VERCEL_API_PRIVILEGED_ENV_FORBIDDEN");
        }
        for (String key : new String[] {"SUPABASE_SERVICE_ROLE_KEY", "CP_SUPABASE_SECRET_KEY", "SUPABASE_SECRET_KEY"}) {
            require(value(env, key).isEmpty(), "VERCEL_API_PRIVILEGED_ENV_FORBIDDEN");
        }
        validateDatabase(value(env, "DATABASE_URL"), value(env, "DATABASE_USER"));
        require(configured(value(env, "DATABASE_PASSWORD")), "VERCEL_API_DATABASE_PASSWORD_REQUIRED");
        require(ISSUER.equals(value(env, "CP_JWT_ISSUER")), "VERCEL_API_ISSUER_EXACT");
        require((ISSUER + "/.well-known/jwks.json").equals(value(env, "CP_JWT_JWK_SET_URI")), "VERCEL_API_JWKS_EXACT");
        require(secureOrigin(value(env, "CP_BFF_ORIGIN")), "VERCEL_API_BFF_ORIGIN_REQUIRED");
        boolean bff = flag(env, "CP_BFF_ENABLED");
        flag(env, "CP_RESERVATIONS_ENABLED");
        if (bff) {
            require(value(env, "CP_SUPABASE_PUBLISHABLE_KEY").matches("sb_publishable_[A-Za-z0-9_-]{16,}"), "VERCEL_API_PUBLISHABLE_KEY_REQUIRED");
            byte[] cipher = canonicalKey(value(env, "CP_BFF_ENCRYPTION_KEY"), "VERCEL_API_ENCRYPTION_KEY_INVALID");
            byte[] rate = canonicalKey(value(env, "CP_BFF_RATE_LIMIT_KEY"), "VERCEL_API_RATE_KEY_INVALID");
            require(!Arrays.equals(cipher, rate), "VERCEL_API_KEYS_MUST_DIFFER");
        }
        int pool = integer(env.getOrDefault("DB_POOL_SIZE", "2"), "VERCEL_API_POOL_LIMIT");
        require(pool >= 1 && pool <= 2, "VERCEL_API_POOL_LIMIT");
        require("8080".equals(env.getOrDefault("PORT", "8080")), "VERCEL_API_PORT_EXACT");
    }

    private static void validateDatabase(String jdbc, String user) {
        require(jdbc.startsWith("jdbc:postgresql://") && !jdbc.matches(".*[\\s\\\\].*"), "VERCEL_API_DATABASE_TARGET_INVALID");
        URI uri;
        try { uri = URI.create(jdbc.substring(5)); }
        catch (IllegalArgumentException error) { throw new Rejected("VERCEL_API_DATABASE_TARGET_INVALID"); }
        require("postgresql".equals(uri.getScheme()) && uri.getRawUserInfo() == null && uri.getRawFragment() == null
            && uri.getPort() == 5432 && "/postgres".equals(uri.getRawPath()), "VERCEL_API_DATABASE_TARGET_INVALID");
        String host = uri.getHost();
        boolean direct = ("db." + PROJECT + ".supabase.co").equals(host);
        boolean session = host != null && host.matches("aws-[0-9]+-sa-east-1\\.pooler\\.supabase\\.com");
        require(direct || session, "VERCEL_API_DATABASE_PROJECT_EXACT");
        require(direct ? user.matches("cp_runtime_[a-z0-9_]+") : user.matches("cp_runtime_[a-z0-9_]+\\." + PROJECT),
            "VERCEL_API_RUNTIME_IDENTITY_REQUIRED");
        String query = uri.getRawQuery();
        require(query != null, "VERCEL_API_DATABASE_TLS_REQUIRED");
        Map<String, String> parameters = new HashMap<>();
        for (String parameter : query.split("&", -1)) {
            String[] pair = parameter.split("=", -1);
            require(pair.length == 2 && ("sslmode".equals(pair[0]) || "sslrootcert".equals(pair[0]))
                && !parameters.containsKey(pair[0]), "VERCEL_API_DATABASE_TLS_REQUIRED");
            try { parameters.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException error) { throw new Rejected("VERCEL_API_DATABASE_TLS_REQUIRED"); }
        }
        require(parameters.size() == 2 && "verify-full".equals(parameters.get("sslmode")) && CERT.equals(parameters.get("sslrootcert")),
            "VERCEL_API_DATABASE_TLS_REQUIRED");
    }

    private static boolean secureOrigin(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && "".equals(uri.getRawPath()) && uri.getPort() == -1
                && host != null && host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}")
                && !host.matches(".*(?:^|\\.)(?:localhost|local|invalid|internal)$");
        } catch (IllegalArgumentException error) { return false; }
    }

    private static byte[] canonicalKey(String value, String code) {
        require(value.matches("[A-Za-z0-9+/]{43}="), code);
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException error) { throw new Rejected(code); }
        require(bytes.length == 32 && Base64.getEncoder().encodeToString(bytes).equals(value), code);
        require(Arrays.stream(toUnsigned(bytes)).distinct().count() >= 16, code);
        return bytes;
    }

    private static int[] toUnsigned(byte[] bytes) {
        int[] result = new int[bytes.length];
        for (int index = 0; index < bytes.length; index++) result[index] = Byte.toUnsignedInt(bytes[index]);
        return result;
    }

    private static boolean configured(String value) {
        return value.length() >= 16 && value.chars().noneMatch(c -> c == '\r' || c == '\n' || c == 0)
            && !value.matches("(?i)^(?:SET_|REPLACE_|CHANGE_|PREENCHER|<).*");
    }

    private static boolean flag(Map<String, String> env, String key) {
        String value = env.getOrDefault(key, "false");
        require("false".equals(value) || "true".equals(value), "VERCEL_API_FEATURE_FLAG_INVALID");
        return "true".equals(value);
    }

    private static int integer(String value, String code) {
        try { require(value.matches("[1-2]"), code); return Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new Rejected(code); }
    }

    private static String value(Map<String, String> env, String key) { return env.getOrDefault(key, ""); }
    private static void require(boolean valid, String code) { if (!valid) throw new Rejected(code); }
    private static final class Rejected extends IllegalArgumentException {
        private final String code;
        private Rejected(String code) { super(code); this.code = code; }
    }
}
