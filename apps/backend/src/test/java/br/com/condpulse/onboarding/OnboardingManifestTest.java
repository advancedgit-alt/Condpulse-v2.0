package br.com.condpulse.onboarding;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OnboardingManifestTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ACCOUNT = "11000000-0000-4000-8000-000000000001";
    private static final String MANAGER = "11000000-0000-4000-8000-000000000002";
    private static final String UNIT = "33000000-0000-4000-8000-000000000001";
    private static final String OTHER = "99000000-0000-4000-8000-000000000001";

    static String fixture() {
        return """
            {"schemaVersion":1,"projectRef":"rpnrqfrfwywglvyykgrg",
             "operationId":"66000000-0000-4000-8000-000000000001",
             "tenant":{"id":"22000000-0000-4000-8000-000000000001","name":"CONDOMINIO SINTETICO MANIFESTO","timeZone":"America/Sao_Paulo"},
             "accounts":["11000000-0000-4000-8000-000000000001","11000000-0000-4000-8000-000000000002"],
             "units":[{"id":"33000000-0000-4000-8000-000000000001","label":"UNIDADE SINTETICA 101"}],
             "memberships":[{"id":"44000000-0000-4000-8000-000000000001","accountId":"11000000-0000-4000-8000-000000000001",
               "unitId":"33000000-0000-4000-8000-000000000001","displayName":"MORADOR SINTETICO MANIFESTO","role":"RESIDENT",
               "validFrom":"2026-01-01T00:00:00Z","validUntil":null},
               {"id":"44000000-0000-4000-8000-000000000002","accountId":"11000000-0000-4000-8000-000000000002",
                "unitId":null,"displayName":"GESTOR SINTETICO MANIFESTO","role":"MANAGER",
                "validFrom":"2026-01-01T00:00:00Z","validUntil":null}],
             "amenities":[{"id":"55000000-0000-4000-8000-000000000001","name":"AREA SINTETICA MANIFESTO",
               "capacity":10,"bufferMinutes":15,"policyVersion":1,"feeCents":0,"approvalRequired":false,
               "policy":{"schemaVersion":1,"weekDays":[1,2,3,4,5,6,7],"opensAt":"06:00","closesAt":"23:00",
                "minimumLeadMinutes":0,"maximumAdvanceDays":30,"maximumDurationMinutes":180,
                "maximumActivePerUnit":2,"cancellationLeadMinutes":0}}]}
            """;
    }
    private static OnboardingManifest parse(String value) { return OnboardingManifest.parse(value.getBytes(StandardCharsets.UTF_8)); }
    @SuppressWarnings("unchecked") private static Map<String, Object> source() { return JSON.readValue(fixture(), Map.class); }
    @SuppressWarnings("unchecked") private static Map<String, Object> first(Map<String, Object> source, String key) {
        return (Map<String, Object>) ((List<?>) source.get(key)).getFirst();
    }
    private static void rejected(String value) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parse(value));
        assertEquals("INVALID_ONBOARDING_MANIFEST", error.getMessage());
        assertNull(error.getCause());
    }
    private static void rejected(Map<String, Object> value) { rejected(JSON.writeValueAsString(value)); }

    @Test void explicitValidManifestRetainsEveryRuleAndRedactsToString() {
        OnboardingManifest manifest = parse(fixture());
        assertEquals(OnboardingManifest.PROJECT_REF, manifest.projectRef());
        assertEquals(1, manifest.schemaVersion());
        assertEquals(2, manifest.accounts().size()); assertEquals(1, manifest.units().size());
        assertEquals(2, manifest.memberships().size()); assertEquals(1, manifest.amenities().size());
        assertEquals("America/Sao_Paulo", manifest.tenant().timeZone());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), manifest.memberships().getFirst().validFrom());
        assertNull(manifest.memberships().getFirst().validUntil());
        assertTrue(manifest.amenities().getFirst().policyRawJson().contains("\"maximumActivePerUnit\":2"));
        assertTrue(manifest.manifestHash().matches("[a-f0-9]{64}"));
        assertTrue(manifest.fingerprint("cp_onboarding_synthetic").matches("[a-f0-9]{64}"));
        for (Object value : List.of(manifest, manifest.tenant(), manifest.units().getFirst(),
                manifest.memberships().getFirst(), manifest.amenities().getFirst())) {
            assertTrue(value.toString().contains("redacted"));
            assertFalse(value.toString().contains("SINTETICO"));
            assertFalse(value.toString().contains(ACCOUNT));
        }
        assertThrows(UnsupportedOperationException.class, () -> manifest.accounts().clear());
    }

    @Test void keyOrderAndWhitespaceDoNotChangeHashButNamesAndOperatorDo() {
        Map<String, Object> original = source(); List<String> keys = new ArrayList<>(original.keySet());
        java.util.Collections.reverse(keys); Map<String, Object> reversed = new LinkedHashMap<>();
        keys.forEach(key -> reversed.put(key, original.get(key)));
        OnboardingManifest first = parse(fixture()), second = parse(JSON.writeValueAsString(reversed));
        assertEquals(first.manifestHash(), second.manifestHash());
        assertEquals(first.fingerprint("cp_onboarding_synthetic"), second.fingerprint("cp_onboarding_synthetic"));
        assertNotEquals(first.fingerprint("cp_onboarding_synthetic"), first.fingerprint("cp_onboarding_another"));
        @SuppressWarnings("unchecked") Map<String, Object> tenant = (Map<String, Object>) original.get("tenant");
        tenant.put("name", "OUTRO CONDOMINIO SINTETICO");
        assertNotEquals(first.manifestHash(), parse(JSON.writeValueAsString(original)).manifestHash());
        assertThrows(IllegalArgumentException.class, () -> first.fingerprint("invalid\nprincipal"));
    }

    @Test void duplicateKeysTrailingJsonAndImplicitScalarCoercionsAreRejected() {
        rejected(fixture().replace("\"schemaVersion\":1,\"projectRef\"", "\"schemaVersion\":1,\"schemaVersion\":1,\"projectRef\""));
        rejected(fixture().replace("\"capacity\":10", "\"capacity\":10,\"capacity\":11"));
        rejected(fixture() + "{}");
        rejected(fixture().replace("\"capacity\":10", "\"capacity\":\"10\""));
        rejected(fixture().replace("\"capacity\":10", "\"capacity\":10.0"));
        rejected(fixture().replace("\"approvalRequired\":false", "\"approvalRequired\":\"false\""));
        rejected(fixture().replace("\"feeCents\":0", "\"feeCents\":9223372036854775808"));
    }

    @Test void unknownOrMissingFieldsAndAnotherProjectAreRejectedWithoutSourceDetails() {
        Map<String, Object> unknown = source(); unknown.put("user_metadata", Map.of("role", "MANAGER")); rejected(unknown);
        Map<String, Object> missing = source(); missing.remove("memberships"); rejected(missing);
        Map<String, Object> policyUnknown = source();
        @SuppressWarnings("unchecked") Map<String, Object> policy = (Map<String, Object>) first(policyUnknown, "amenities").get("policy");
        policy.put("bypassApproval", true); rejected(policyUnknown);
        rejected(fixture().replace(OnboardingManifest.PROJECT_REF, "bbbbbbbbbbbbbbbbbbbb"));
        rejected(fixture().replace("\"validUntil\":null", "\"validUntil\":\"2025-01-01T00:00:00Z\""));
        rejected(fixture().replace("2026-01-01T00:00:00Z", "2026-01-01T00:00:00.000000001Z"));
    }

    @Test void arbitraryPermissionsPrivilegedRolesAndCrossReferencesAreRejected() {
        for (String role : List.of("SUPPORT", "CONCIERGE", "OWNER", "master_admin", "MANAGER")) {
            Map<String, Object> modified = source(); first(modified, "memberships").put("role", role); rejected(modified);
        }
        Map<String, Object> permissions = source(); first(permissions, "memberships").put("permissions", Map.of("all", true)); rejected(permissions);
        Map<String, Object> foreignAccount = source(); first(foreignAccount, "memberships").put("accountId", OTHER); rejected(foreignAccount);
        Map<String, Object> foreignUnit = source(); first(foreignUnit, "memberships").put("unitId", OTHER); rejected(foreignUnit);
        Map<String, Object> missingUnit = source(); first(missingUnit, "memberships").put("unitId", null); rejected(missingUnit);
        Map<String, Object> uncovered = source(); uncovered.put("accounts", List.of(ACCOUNT, OTHER)); rejected(uncovered);
    }

    @Test void managerIsExplicitSeparateUnitlessMembershipAndDoesNotGrantResidentCapability() {
        OnboardingManifest result = parse(fixture());
        assertEquals(2, result.memberships().size());
        assertEquals("MANAGER", result.memberships().get(1).role()); assertNull(result.memberships().get(1).unitId());
        assertEquals(MANAGER, result.memberships().get(1).accountId().toString());
        Map<String, Object> combined = source();
        @SuppressWarnings("unchecked") Map<String, Object> manager = (Map<String, Object>) ((List<?>) combined.get("memberships")).get(1);
        manager.put("accountId", ACCOUNT); combined.put("accounts", List.of(ACCOUNT));
        assertEquals(2, parse(JSON.writeValueAsString(combined)).memberships().size());
        Map<String, Object> missingManager = source();
        missingManager.put("accounts", List.of(ACCOUNT));
        missingManager.put("memberships", List.of(first(missingManager, "memberships")));
        rejected(missingManager);
    }

    @Test void duplicatesAndBoundedCollectionsAreRejected() {
        Map<String, Object> accounts = source(); accounts.put("accounts", List.of(ACCOUNT, ACCOUNT)); rejected(accounts);
        Map<String, Object> units = source(); units.put("units", List.of(first(units, "units"), first(units, "units"))); rejected(units);
        Map<String, Object> memberships = source(); memberships.put("memberships", List.of(first(memberships, "memberships"), first(memberships, "memberships"))); rejected(memberships);
        Map<String, Object> amenities = source(); amenities.put("amenities", List.of(first(amenities, "amenities"), first(amenities, "amenities"))); rejected(amenities);
        for (String key : List.of("accounts", "units", "memberships", "amenities")) {
            Map<String, Object> empty = source(); empty.put(key, List.of()); rejected(empty);
        }
        Map<String, Object> excessive = source(); excessive.put("accounts", java.util.Collections.nCopies(251, ACCOUNT)); rejected(excessive);
        byte[] oversized = new byte[OnboardingManifest.MAXIMUM_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> OnboardingManifest.parse(oversized));
        assertThrows(IllegalArgumentException.class, () -> OnboardingManifest.parse(null));
    }

    @Test void invalidRuleTimeZoneWhitespaceAndControlCharactersAreRejected() {
        rejected(fixture().replace("America/Sao_Paulo", "Invalid/TimeZone"));
        rejected(fixture().replace("UNIDADE SINTETICA 101", " UNIDADE SINTETICA 101"));
        rejected(fixture().replace("MORADOR SINTETICO MANIFESTO", "MORADOR\\nSINTETICO"));
        rejected(fixture().replace("\"weekDays\":[1,2,3,4,5,6,7]", "\"weekDays\":[1,1]"));
        rejected(fixture().replace("\"opensAt\":\"06:00\"", "\"opensAt\":\"24:00\""));
        rejected(fixture().replace("\"maximumActivePerUnit\":2", "\"maximumActivePerUnit\":0"));
        rejected(fixture().replace("\"bufferMinutes\":15", "\"bufferMinutes\":1441"));
        rejected(fixture().replace(UNIT, "00000000-0000-0000-0000-000000000000"));
    }
}
