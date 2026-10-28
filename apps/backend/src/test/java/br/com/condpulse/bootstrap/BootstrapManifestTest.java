package br.com.condpulse.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BootstrapManifestTest {
    @Test void canonicalInventoriesIgnoreRowAndKeyOrderButPreserveMultiplicity() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("roles", List.of("cp_worker", "cp_api"));
        first.put("columns", List.of(Map.of("name", "b", "nullable", true), Map.of("name", "a", "nullable", false)));
        Map<String, Object> reordered = Map.of("columns", List.of(Map.of("nullable", false, "name", "a"), Map.of("nullable", true, "name", "b")),
            "roles", new String[] { "cp_api", "cp_worker" });
        assertEquals(BootstrapManifest.canonical(first), BootstrapManifest.canonical(reordered));
        assertNotEquals(BootstrapManifest.canonical(List.of("a", "a")), BootstrapManifest.canonical(List.of("a")));
        assertNotEquals(BootstrapManifest.canonical(List.of("a", "b")), BootstrapManifest.canonical(List.of("a,b")));
    }

    @Test void canonicalStringsCannotAliasStructureOrLoseSurrogates() {
        assertEquals("{\"quote\\\"\\n\":\"slash\\\\\\u0000\"}",
            BootstrapManifest.canonical(Map.of("quote\"\n", "slash\\\0")));
        assertNotEquals(BootstrapManifest.canonical("null"), BootstrapManifest.canonical(null));
        assertNotEquals(BootstrapManifest.sha256(BootstrapManifest.canonical("\ud800")),
            BootstrapManifest.sha256(BootstrapManifest.canonical("\ud801")));
        assertNotEquals(BootstrapManifest.canonical(Map.of("a", List.of("b"))), BootstrapManifest.canonical("{a:[b]}"));
    }

    @Test void numericAndUnsupportedTypesHaveDeterministicBehavior() {
        assertEquals("1", BootstrapManifest.canonical(new BigDecimal("1.000")));
        assertEquals(BootstrapManifest.canonical(1), BootstrapManifest.canonical(1L));
        assertThrows(IllegalArgumentException.class, () -> BootstrapManifest.canonical(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> BootstrapManifest.canonical(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> BootstrapManifest.canonical(Map.of(1, "value")));
        assertThrows(IllegalArgumentException.class, () -> BootstrapManifest.canonical(new Object()));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", BootstrapManifest.sha256(""));
    }

    @Test void immutableEmbeddedSourceAndExecutedLfAreBothVerified() {
        assertEquals(List.of(), BootstrapManifest.sourceCodes(BootstrapManifest.load()));
    }

    @Test void wrongTargetAndSourceRecordsReturnOnlyControlledCodes() {
        String sentinel = "SENTINEL_SECRET_must_not_be_returned";
        Map<String, Object> manifest = BootstrapManifest.load();
        manifest.put("projectRef", sentinel);
        manifest.put("canonicalGitSourceSha256", List.of(Map.of("name", sentinel)));
        List<String> codes = BootstrapManifest.sourceCodes(manifest);
        assertTrue(codes.contains("MANIFEST_METADATA_MISMATCH"));
        assertTrue(codes.contains("MANIFEST_SOURCE_RECORDS_MISMATCH"));
        assertFalse(String.join(" ", codes).contains(sentinel));
        assertEquals(List.of("MANIFEST_METADATA_MISMATCH"), BootstrapManifest.sourceCodes(null));
    }

    @Test void extraOrAlteredSourceCannotBeHiddenByArrayOrder() {
        Map<String, Object> manifest = BootstrapManifest.load();
        @SuppressWarnings("unchecked") List<Map<String, Object>> sources = (List<Map<String, Object>>) manifest.get("canonicalGitSourceSha256");
        List<Map<String, Object>> modified = new ArrayList<>(sources);
        Map<String, Object> source = new LinkedHashMap<>(modified.get(0));
        source.put("bytes", 9511);
        modified.set(0, source);
        manifest.put("canonicalGitSourceSha256", modified);
        assertTrue(BootstrapManifest.sourceCodes(manifest).contains("MANIFEST_SOURCE_RECORDS_MISMATCH"));
        manifest = BootstrapManifest.load();
        manifest.put("executedSourceSha256", List.of());
        assertTrue(BootstrapManifest.sourceCodes(manifest).contains("MANIFEST_EXECUTED_SOURCE_MISMATCH"));
    }
}
