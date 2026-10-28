package br.com.condpulse.bootstrap;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Atesta o bootstrap usando somente catalogos e o historico nativo de DDL. */
public final class BootstrapAttestor {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> CATALOG = List.of("acl", "columns", "history", "indexes", "policies", "constraints");
    private static final Map<String, String> CATALOG_CODES = Map.of(
        "acl", "TABLE_ACL_MISMATCH", "columns", "COLUMNS_MISMATCH", "history", "NATIVE_HISTORY_MISMATCH",
        "indexes", "INDEXES_MISMATCH", "policies", "POLICIES_MISMATCH", "constraints", "CONSTRAINTS_MISMATCH");
    private static final Map<String, String> EXTRA_CODES = Map.ofEntries(
        Map.entry("server", "DATABASE_SESSION_MISMATCH"),
        Map.entry("tables", "TABLES_OWNER_RLS_MISMATCH"),
        Map.entry("schemas", "SCHEMA_OWNER_MISMATCH"),
        Map.entry("schemaGrants", "SCHEMA_ACL_MISMATCH"),
        Map.entry("functions", "FUNCTIONS_MISMATCH"),
        Map.entry("functionGrants", "FUNCTION_ACL_MISMATCH"),
        Map.entry("columnGrants", "COLUMN_ACL_MISMATCH"),
        Map.entry("roles", "RUNTIME_ROLES_MISMATCH"),
        Map.entry("roleMemberships", "RUNTIME_ROLE_MEMBERSHIP_MISMATCH"),
        Map.entry("flywayHistory", "FLYWAY_HISTORY_PRESENT"),
        Map.entry("unexpectedRelations", "UNEXPECTED_RELATIONS"),
        Map.entry("unexpectedTypes", "UNEXPECTED_TYPES"),
        Map.entry("triggerViolations", "TRIGGER_MISMATCH"),
        Map.entry("columnFlags", "COLUMN_FLAGS_MISMATCH"),
        Map.entry("invalidConstraints", "INVALID_CONSTRAINTS"),
        Map.entry("invalidIndexes", "INVALID_INDEXES"),
        Map.entry("inheritance", "UNEXPECTED_INHERITANCE"),
        Map.entry("defaultPrivileges", "DEFAULT_PRIVILEGES_MISMATCH"));

    public BootstrapAttestor() { }

    public record Attestation(boolean eligible, String fingerprint, List<String> codes) {
        public Attestation { codes = List.copyOf(codes); }
    }

    /** A conexao/transacao/lock sao responsabilidade do executor; nada e alterado aqui. */
    @SuppressWarnings("unchecked")
    public Attestation inspect(Connection connection) {
        Map<String, Object> manifest;
        try {
            manifest = BootstrapManifest.load();
            List<String> sourceCodes = BootstrapManifest.sourceCodes(manifest);
            if (!sourceCodes.isEmpty()) return result(manifest, Map.of(), sourceCodes);
        } catch (Exception ignored) {
            return result(Map.of(), Map.of(), List.of("ATTESTATION_SOURCE_FAILED"));
        }
        try {
            String sql;
            try (InputStream input = BootstrapAttestor.class.getResourceAsStream("/bootstrap/attestation-snapshot.sql")) {
                if (input == null) throw new IllegalStateException();
                byte[] bytes = input.readNBytes(131_073);
                if (bytes.length == 0 || bytes.length > 131_072) throw new IllegalStateException();
                sql = new String(bytes, StandardCharsets.UTF_8);
            }
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(30);
                statement.setMaxRows(1);
                try (ResultSet rows = statement.executeQuery(sql)) {
                    if (!rows.next()) throw new IllegalStateException();
                    String payload = rows.getString(1);
                    if (payload == null || payload.length() > 1_048_576) throw new IllegalStateException();
                    Map<String, Object> snapshot = JSON.readValue(payload, Map.class);
                    if (snapshot == null || rows.next()) throw new IllegalStateException();
                    return compareSnapshot(manifest, snapshot);
                }
            }
        } catch (Exception ignored) {
            return result(manifest, Map.of(), List.of("ATTESTATION_QUERY_FAILED"));
        }
    }

    static Attestation compareSnapshot(Map<String, Object> manifest, Map<String, Object> snapshot) {
        Set<String> codes = new LinkedHashSet<>();
        Map<String, Object> expectedCatalog = map(manifest.get("attestation"));
        Map<String, Object> observedCatalog = map(snapshot.get("catalog"));
        if (!expectedCatalog.keySet().equals(Set.copyOf(CATALOG))
                || !observedCatalog.keySet().equals(Set.copyOf(CATALOG))) codes.add("CATALOG_SHAPE_MISMATCH");
        for (String key : CATALOG) {
            if (!same(expectedCatalog.get(key), observedCatalog.get(key))) codes.add(CATALOG_CODES.get(key));
        }
        Map<String, Object> expectedExtras = expectedExtras(manifest);
        Map<String, Object> observedExtras = map(snapshot.get("extras"));
        if (!snapshot.keySet().equals(Set.of("catalog", "extras"))
                || !expectedExtras.keySet().equals(observedExtras.keySet())) codes.add("SNAPSHOT_SHAPE_MISMATCH");
        for (String key : expectedExtras.keySet()) {
            if (!same(expectedExtras.get(key), observedExtras.get(key))) codes.add(EXTRA_CODES.get(key));
        }
        return result(manifest, snapshot, List.copyOf(codes));
    }

    private static Attestation result(Map<String, Object> manifest, Map<String, Object> snapshot, List<String> codes) {
        String fingerprint = BootstrapManifest.sha256(BootstrapManifest.canonical(row(
            "manifestSource", manifest.get("canonicalGitSourceSha256"),
            "nativeMigration", manifest.get("supabaseMigration"), "snapshot", snapshot, "codes", codes)));
        return new Attestation(codes.isEmpty(), fingerprint, codes);
    }

    static Map<String, Object> expectedExtras(Map<String, Object> manifest) {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("server", row("version", "170011", "actor", "postgres", "replication", "origin"));
        List<Object> tables = new ArrayList<>();
        Object acl = map(manifest.get("attestation")).get("acl");
        if (acl instanceof List<?> entries) for (Object entry : entries) {
            Map<String, Object> table = map(entry);
            tables.add(row("schema", table.get("schema"), "table", table.get("table"),
                "owner", "postgres", "rls", true, "force_rls", true));
        }
        expected.put("tables", tables);
        expected.put("schemas", List.of(row("schema", "cp", "owner", "postgres"), row("schema", "platform", "owner", "postgres")));
        List<Object> schemaGrants = new ArrayList<>();
        for (String schema : List.of("cp", "platform")) {
            schemaGrants.add(row("schema", schema, "grantee", "postgres", "grantor", "postgres", "privilege", "CREATE", "grantable", false));
            schemaGrants.add(row("schema", schema, "grantee", "postgres", "grantor", "postgres", "privilege", "USAGE", "grantable", false));
            for (String role : List.of("cp_api", "cp_worker")) {
                schemaGrants.add(row("schema", schema, "grantee", role, "grantor", "postgres", "privilege", "USAGE", "grantable", false));
            }
        }
        expected.put("schemaGrants", schemaGrants);
        List<Object> functions = new ArrayList<>();
        List<Object> functionGrants = new ArrayList<>();
        for (String subject : List.of("tenant", "actor")) {
            String name = "current_" + subject + "_id";
            functions.add(row("schema", "platform", "name", name, "owner", "postgres", "kind", "f", "arguments", "",
                "returns", "uuid", "language", "sql", "volatility", "s", "definer", false,
                "set_returning", false, "strict", false, "leakproof", false, "config", List.of("search_path=pg_catalog"),
                "body", "SELECT nullif(current_setting('app." + subject + "_id',true),'')::uuid"));
            for (String role : List.of("postgres", "cp_api", "cp_worker")) {
                functionGrants.add(row("schema", "platform", "name", name, "grantee", role, "grantor", "postgres",
                    "privilege", "EXECUTE", "grantable", false));
            }
        }
        expected.put("functions", functions);
        expected.put("functionGrants", functionGrants);
        List<Object> columnGrants = new ArrayList<>();
        for (String column : List.of("id", "active")) {
            columnGrants.add(row("schema", "platform", "table", "tenants", "column", column, "grantee", "cp_worker",
                "grantor", "postgres", "privilege", "SELECT", "grantable", false));
        }
        expected.put("columnGrants", columnGrants);
        List<Object> roles = new ArrayList<>();
        for (String role : List.of("cp_api", "cp_worker")) {
            roles.add(row("name", role, "login", false, "superuser", false, "bypass_rls", false,
                "createdb", false, "createrole", false, "replication", false, "inherit", true, "config", null));
        }
        expected.put("roles", roles);
        expected.put("flywayHistory", 0);
        for (String empty : List.of("roleMemberships", "unexpectedRelations", "unexpectedTypes", "triggerViolations",
                "columnFlags", "invalidConstraints", "invalidIndexes", "inheritance", "defaultPrivileges")) expected.put(empty, List.of());
        return expected;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }

    private static boolean same(Object first, Object second) {
        try { return BootstrapManifest.canonical(first).equals(BootstrapManifest.canonical(second)); }
        catch (Exception ignored) { return false; }
    }
}
