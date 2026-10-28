package br.com.condpulse.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BootstrapAttestorTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void inventoryOrderDoesNotChangeTheFingerprintButDuplicatesDo() {
        Map<String, Object> manifest = BootstrapManifest.load();
        Map<String, Object> snapshot = validSnapshot(manifest);
        var first = BootstrapAttestor.compareSnapshot(manifest, snapshot);
        assertTrue(first.eligible());
        Map<String, Object> shuffled = cloneMap(snapshot);
        for (Object list : map(shuffled.get("catalog")).values()) Collections.reverse((List<?>) list);
        var second = BootstrapAttestor.compareSnapshot(manifest, shuffled);
        assertTrue(second.eligible());
        assertEquals(first.fingerprint(), second.fingerprint());
        list(map(shuffled.get("catalog")).get("policies")).add(
            list(map(shuffled.get("catalog")).get("policies")).getFirst());
        var duplicated = BootstrapAttestor.compareSnapshot(manifest, shuffled);
        assertFalse(duplicated.eligible());
        assertTrue(duplicated.codes().contains("POLICIES_MISMATCH"));
        assertNotEquals(first.fingerprint(), duplicated.fingerprint());
    }

    @Test void rlsAndPolicyDriftCannotPassByKeepingTheSameObjectCounts() {
        Map<String, Object> manifest = BootstrapManifest.load();
        Map<String, Object> snapshot = validSnapshot(manifest);
        map(list(map(snapshot.get("extras")).get("tables")).getFirst()).put("force_rls", false);
        map(list(map(snapshot.get("catalog")).get("policies")).getFirst()).put("qual", "true");
        var result = BootstrapAttestor.compareSnapshot(manifest, snapshot);
        assertFalse(result.eligible());
        assertTrue(result.codes().contains("TABLES_OWNER_RLS_MISMATCH"));
        assertTrue(result.codes().contains("POLICIES_MISMATCH"));
    }

    @Test void elevatedRoleFunctionAndColumnPrivilegesAreRejectedWithoutEchoingValues() {
        Map<String, Object> manifest = BootstrapManifest.load();
        Map<String, Object> snapshot = validSnapshot(manifest);
        Map<String, Object> extras = map(snapshot.get("extras"));
        map(list(extras.get("roles")).getFirst()).put("bypass_rls", true);
        map(list(extras.get("functions")).getFirst()).put("definer", true);
        map(list(extras.get("functionGrants")).getFirst()).put("grantee", "PUBLIC");
        map(list(extras.get("columnGrants")).getFirst()).put("grantee", "SENTINEL_SECRET_NEVER_ECHO");
        list(extras.get("roleMemberships")).add(Map.of("member", "cp_api", "role", "pg_read_all_data"));
        var result = BootstrapAttestor.compareSnapshot(manifest, snapshot);
        assertFalse(result.eligible());
        assertTrue(result.codes().containsAll(List.of("RUNTIME_ROLES_MISMATCH", "FUNCTIONS_MISMATCH",
            "FUNCTION_ACL_MISMATCH", "COLUMN_ACL_MISMATCH", "RUNTIME_ROLE_MEMBERSHIP_MISMATCH")));
        assertFalse(result.toString().contains("SENTINEL_SECRET_NEVER_ECHO"));
        assertTrue(result.fingerprint().matches("[a-f0-9]{64}"));
    }

    @Test void existingFlywayHistoryAndUnexpectedRelationsPreventBaseline() {
        Map<String, Object> manifest = BootstrapManifest.load();
        Map<String, Object> snapshot = validSnapshot(manifest);
        Map<String, Object> extras = map(snapshot.get("extras"));
        extras.put("flywayHistory", 1);
        list(extras.get("unexpectedRelations")).add(Map.of("schema", "cp", "name", "extra_view", "kind", "v"));
        var result = BootstrapAttestor.compareSnapshot(manifest, snapshot);
        assertFalse(result.eligible());
        assertTrue(result.codes().containsAll(List.of("FLYWAY_HISTORY_PRESENT", "UNEXPECTED_RELATIONS")));
    }

    @Test void jdbcFailureReturnsOnlyControlledCodesAndDoesNotReconfigureTheConnection() {
        List<String> invoked = new ArrayList<>();
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                invoked.add(method.getName());
                if (method.getName().equals("createStatement")) throw new SQLException("SENTINEL_DB_PASSWORD");
                throw new AssertionError("Atestador nao deve reconfigurar a conexao");
            });
        var result = new BootstrapAttestor().inspect(connection);
        assertFalse(result.eligible());
        assertEquals(List.of("ATTESTATION_QUERY_FAILED"), result.codes());
        assertEquals(List.of("createStatement"), invoked);
        assertFalse(result.toString().contains("SENTINEL_DB_PASSWORD"));
    }

    private static Map<String, Object> validSnapshot(Map<String, Object> manifest) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("catalog", manifest.get("attestation"));
        snapshot.put("extras", BootstrapAttestor.expectedExtras(manifest));
        return cloneMap(snapshot);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cloneMap(Map<String, Object> source) {
        return JSON.readValue(JSON.writeValueAsString(source), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) { return (List<Object>) value; }
}
