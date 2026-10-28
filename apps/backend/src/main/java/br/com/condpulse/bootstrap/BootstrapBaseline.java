package br.com.condpulse.bootstrap;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;
import org.flywaydb.core.api.logging.LogFactory;

/**
 * Comando isolado da API. Inspeciona por padrao; baseline nunca chama migrate.
 * Todas as mensagens sao codigos fixos. Falha apos iniciar o comando Flyway pode
 * significar resultado desconhecido: baselineCreated=false nao prova ausencia.
 */
public final class BootstrapBaseline {
    public static final String PROJECT_REF = "rpnrqfrfwywglvyykgrg";
    public static final long LOCK_KEY = 0x4350444c42415345L;
    private static final String VERSION = "002";
    private static final String CA_PATH = "/run/secrets/db-ca.crt";
    private static final String DISPOSABLE_URL = "jdbc:postgresql://localhost:5432/condpulse_bootstrap_ci";
    private BootstrapBaseline() { }

    public record Outcome(boolean eligible, boolean baselineCreated, String fingerprint, List<String> codes) {
        public Outcome { codes = List.copyOf(codes); }
        public boolean success() { return codes.isEmpty(); }
    }

    /** Deliberadamente nao e record: toString nunca pode revelar a senha/URL. */
    public static final class Config {
        private final String url;
        private final String username;
        private final String password;
        private Config(String url, String username, String password) {
            this.url = url; this.username = username; this.password = password;
        }
        public String projectRef() { return PROJECT_REF; }
        @Override public String toString() { return "BootstrapBaseline.Config[redacted]"; }
    }

    @FunctionalInterface interface Connector { Connection open(Config config) throws SQLException; }
    @FunctionalInterface interface Inspector { BootstrapAttestor.Attestation inspect(Connection connection); }
    @FunctionalInterface interface BaselineAction { void baseline(Config config) throws Exception; }
    @FunctionalInterface interface Runner { Outcome execute(Config config, String mode, String ack); }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out));
    }

    public static int run(String[] args, Map<String, String> env, PrintStream output) {
        // Somente o executavel separado muda os logs; nao ha bootstrap Spring.
        java.util.logging.LogManager.getLogManager().reset();
        LogFactory.setLogCreator(new QuietLogCreator());
        return run(args, env, output, BootstrapBaseline::execute);
    }

    static int run(String[] args, Map<String, String> env, PrintStream output, Runner runner) {
        Outcome result;
        try {
            String mode = mode(args);
            Config config = productionConfig(env);
            result = runner.execute(config, mode, env.get("CP_BASELINE_ACK"));
        } catch (ControlledFailure failure) {
            result = failure(failure.code);
        } catch (Exception ignored) {
            result = failure("BASELINE_COMMAND_FAILED");
        }
        writeOutcome(output, result);
        return result.success() ? 0 : 1;
    }

    static Config productionConfig(Map<String, String> env) {
        if (env == null || !PROJECT_REF.equals(env.get("CP_BASELINE_PROJECT_REF"))) throw controlled("PROJECT_NOT_ALLOWED");
        String url = env.get("DATABASE_URL");
        String user = env.get("DATABASE_USER");
        String password = env.get("DATABASE_PASSWORD");
        if (!configuredPassword(password) || user == null || !validProductionUrl(url, user)) throw controlled("DATABASE_CONFIGURATION_INVALID");
        return new Config(url, user, password);
    }

    /** Sem acesso por argumento/env do CLI. Exclusivo dos testes JDBC no pacote. */
    static Config disposableConfig(String url, String user, String password) {
        if (!"DISPOSABLE".equals(System.getenv("CP_DB_TEST_OK")) || !DISPOSABLE_URL.equals(url)
                || !"postgres".equals(user) || !configuredPassword(password)) throw controlled("DISPOSABLE_DATABASE_GUARD");
        return new Config(url, user, password);
    }

    static Outcome execute(Config config, String mode, String ack) {
        return execute(config, mode, ack, BootstrapBaseline::connect,
            connection -> new BootstrapAttestor().inspect(connection), BootstrapBaseline::officialBaseline);
    }

    static Outcome execute(Config config, String mode, String ack, Connector connector, Inspector inspector, BaselineAction action) {
        if (config == null || !("inspect".equals(mode) || "baseline".equals(mode))) return failure("COMMAND_INVALID");
        boolean attempted = false;
        String fingerprint = "";
        try (Connection guard = connector.open(config)) {
            guard.setReadOnly(true);
            guard.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            guard.setAutoCommit(false);
            boolean locked = tryLock(guard);
            if (!locked) return failure("BASELINE_BUSY");
            try {
                BootstrapAttestor.Attestation attestation = inspector.inspect(guard);
                if (attestation == null || !isFingerprint(attestation.fingerprint())) return failure("ATTESTATION_RESULT_INVALID");
                fingerprint = attestation.fingerprint();
                if (!attestation.eligible() || !attestation.codes().isEmpty()) {
                    // Diagnosticos do atestador nao sao ecoados sem uma allowlist.
                    return new Outcome(false, false, fingerprint, List.of("ATTESTATION_FAILED"));
                }
                if ("inspect".equals(mode)) return new Outcome(true, false, fingerprint, List.of());
                String expected = PROJECT_REF + ":" + VERSION + ":" + fingerprint;
                if (ack == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), ack.getBytes(StandardCharsets.UTF_8))) {
                    return new Outcome(true, false, fingerprint, List.of("BASELINE_ACK_REQUIRED"));
                }
                // Fim do snapshot read-only. O lock e de sessao e continua ativo.
                guard.commit();
                attempted = true;
                action.baseline(config);
                if (!onlyOfficialMarker(guard)) return new Outcome(false, false, fingerprint, List.of("BASELINE_MARKER_INVALID"));
                guard.commit();
                return new Outcome(true, true, fingerprint, List.of());
            } finally {
                releaseLock(guard);
            }
        } catch (Exception ignored) {
            return new Outcome(false, false, fingerprint, List.of(attempted ? "BASELINE_RESULT_UNKNOWN" : "DATABASE_OPERATION_FAILED"));
        }
    }

    private static void officialBaseline(Config config) {
        Flyway.configure()
            .dataSource(new ConfiguredDataSource(config))
            .defaultSchema("platform")
            .schemas("platform", "cp")
            .table("flyway_schema_history")
            .locations("classpath:db/migration")
            .createSchemas(false)
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .baselineVersion(VERSION)
            .baselineDescription("CondPulse ADR-0003 V001/V002")
            .loggers(QuietLogCreator.class.getName())
            .load()
            .baseline();
    }

    private static Connection connect(Config config) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.username);
        properties.setProperty("password", config.password);
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "20");
        properties.setProperty("tcpKeepAlive", "true");
        properties.setProperty("ApplicationName", "condpulse-bootstrap-baseline");
        return DriverManager.getConnection(config.url, properties);
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setQueryTimeout(20);
            statement.setLong(1, LOCK_KEY);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() && rows.getBoolean(1); }
        }
    }

    private static void releaseLock(Connection connection) {
        try {
            connection.rollback();
            try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setQueryTimeout(20);
                statement.setLong(1, LOCK_KEY);
                statement.execute();
            }
            connection.commit();
        } catch (SQLException ignored) {
            // close() em try-with-resources tambem libera o lock da sessao.
        }
    }

    private static boolean onlyOfficialMarker(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select version,type,success from platform.flyway_schema_history order by installed_rank")) {
            statement.setQueryTimeout(20);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return false;
                String version = rows.getString("version");
                boolean marker = version != null && version.matches("0*2") && "BASELINE".equals(rows.getString("type"))
                    && rows.getBoolean("success") && !rows.wasNull();
                return marker && !rows.next();
            }
        }
    }

    private static boolean validProductionUrl(String value, String username) {
        if (value == null || !value.startsWith("jdbc:postgresql://") || value.matches(".*[\\s\\\\].*")) return false;
        try {
            URI uri = URI.create(value.substring(5));
            if (!"postgresql".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || !"/postgres".equals(uri.getRawPath()) || (uri.getPort() != -1 && uri.getPort() != 5432)) return false;
            String host = uri.getHost();
            boolean direct = ("db." + PROJECT_REF + ".supabase.co").equals(host)
                && username.matches("(?:postgres|cp_migrator_[a-z0-9_]+)");
            boolean pooler = host != null && host.matches("aws-\\d+-sa-east-1\\.pooler\\.supabase\\.com")
                && username.matches("(?:postgres|cp_migrator_[a-z0-9_]+)\\." + PROJECT_REF);
            if (!(direct || pooler) || uri.getRawQuery() == null) return false;
            Map<String, String> query = new HashMap<>();
            for (String entry : uri.getRawQuery().split("&", -1)) {
                String[] pair = entry.split("=", -1);
                if (pair.length != 2) return false;
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                if (query.putIfAbsent(key, URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) return false;
            }
            return query.size() == 2 && "verify-full".equals(query.get("sslmode")) && CA_PATH.equals(query.get("sslrootcert"));
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static boolean configuredPassword(String value) {
        return value != null && value.length() >= 16 && !value.matches("(?s).*[\\r\\n\\x00].*")
            && !value.matches("(?i)^(?:SET_|REPLACE_|CHANGE_|PREENCHER|<).*");
    }
    private static String mode(String[] args) {
        if (args == null || args.length == 0) return "inspect";
        if (args.length == 1 && ("inspect".equals(args[0]) || "baseline".equals(args[0]))) return args[0];
        throw controlled("COMMAND_INVALID");
    }
    private static boolean isFingerprint(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static Outcome failure(String code) { return new Outcome(false, false, "", List.of(code)); }
    private static ControlledFailure controlled(String code) { return new ControlledFailure(code); }
    private static final class ControlledFailure extends RuntimeException {
        private final String code;
        private ControlledFailure(String code) { super(null, null, false, false); this.code = code; }
    }
    private static void writeOutcome(PrintStream output, Outcome result) {
        // Todos os campos variaveis sao booleanos, SHA256 ou codigos escolhidos acima.
        String codes = result.codes().stream().map(code -> "\"" + code + "\"").collect(java.util.stream.Collectors.joining(","));
        output.println("{\"eligible\":" + result.eligible() + ",\"baselineCreated\":" + result.baselineCreated()
            + ",\"fingerprint\":\"" + result.fingerprint() + "\",\"codes\":[" + codes + "]}");
    }

    private static final class ConfiguredDataSource implements DataSource {
        private final Config config;
        private ConfiguredDataSource(Config config) { this.config = config; }
        @Override public Connection getConnection() throws SQLException { return connect(config); }
        @Override public Connection getConnection(String username, String password) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        @Override public int getLoginTimeout() { return 5; }
        @Override public Logger getParentLogger() { return Logger.getLogger("condpulse.bootstrap"); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { if (type.isInstance(this)) return type.cast(this); throw new SQLException(); }
        @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
    }

    /** Extensao publica LogCreator do Flyway; nao usa implementacoes internas. */
    public static final class QuietLogCreator implements LogCreator {
        public QuietLogCreator() { }
        @Override public Log createLogger(Class<?> type) {
            return new Log() {
                @Override public void debug(String message) { }
                @Override public void info(String message) { }
                @Override public void warn(String message) { }
                @Override public void error(String message) { }
                @Override public void error(String message, Exception exception) { }
                public void notice(String message) { }
            };
        }
    }
}
