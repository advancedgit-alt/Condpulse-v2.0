package br.com.condpulse.onboarding;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import br.com.condpulse.reservations.domain.ReservationPolicy;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Explicit operational input only. It never supplies a subject, role or rule by default. */
public final class OnboardingManifest {
    public static final String PROJECT_REF = "rpnrqfrfwywglvyykgrg";
    public static final int MAXIMUM_BYTES = 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> POLICY_FIELDS = Set.of("schemaVersion", "weekDays", "opensAt", "closesAt",
        "minimumLeadMinutes", "maximumAdvanceDays", "maximumDurationMinutes", "maximumActivePerUnit", "cancellationLeadMinutes");

    public record Tenant(UUID id, String name, String timeZone) {
        @Override public String toString() { return "OnboardingManifest.Tenant[redacted]"; }
    }
    public record Unit(UUID id, String label) {
        @Override public String toString() { return "OnboardingManifest.Unit[redacted]"; }
    }
    public record Membership(UUID id, UUID accountId, UUID unitId, String displayName, String role,
            Instant validFrom, Instant validUntil) {
        @Override public String toString() { return "OnboardingManifest.Membership[redacted]"; }
    }
    public record Amenity(UUID id, String name, int capacity, int bufferMinutes, int policyVersion,
            long feeCents, boolean approvalRequired, String policyRawJson) {
        @Override public String toString() { return "OnboardingManifest.Amenity[redacted]"; }
    }

    private final UUID operationId;
    private final Tenant tenant;
    private final List<UUID> accounts;
    private final List<Unit> units;
    private final List<Membership> memberships;
    private final List<Amenity> amenities;
    private final String manifestHash;

    private OnboardingManifest(UUID operationId, Tenant tenant, List<UUID> accounts, List<Unit> units,
            List<Membership> memberships, List<Amenity> amenities, String manifestHash) {
        this.operationId = operationId; this.tenant = tenant;
        this.accounts = List.copyOf(accounts); this.units = List.copyOf(units);
        this.memberships = List.copyOf(memberships); this.amenities = List.copyOf(amenities);
        this.manifestHash = manifestHash;
    }

    public String projectRef() { return PROJECT_REF; }
    public int schemaVersion() { return 1; }
    public UUID operationId() { return operationId; }
    public Tenant tenant() { return tenant; }
    public List<UUID> accounts() { return accounts; }
    public List<Unit> units() { return units; }
    public List<Membership> memberships() { return memberships; }
    public List<Amenity> amenities() { return amenities; }
    public String manifestHash() { return manifestHash; }
    public String fingerprint(String databaseActor) {
        if (databaseActor == null || !databaseActor.matches("[a-z_][a-z0-9_]{0,62}")) throw invalid();
        return sha256(PROJECT_REF + "\n" + databaseActor + "\n" + manifestHash);
    }
    @Override public String toString() { return "OnboardingManifest[redacted]"; }

    /** Reject duplicate keys, trailing documents, coercions, unknown fields and unsafe references. */
    public static OnboardingManifest parse(byte[] input) {
        try {
            require(input != null && input.length > 0 && input.length <= MAXIMUM_BYTES);
            Map<String, Object> source = object(JSON.readValue(input, Object.class), Set.of("schemaVersion", "projectRef",
                "operationId", "tenant", "accounts", "units", "memberships", "amenities"));
            require(integer(source.get("schemaVersion"), 1, 1) == 1 && PROJECT_REF.equals(source.get("projectRef")));
            UUID operation = uuid(source.get("operationId"));
            Map<String, Object> rawTenant = object(source.get("tenant"), Set.of("id", "name", "timeZone"));
            String zone = text(rawTenant.get("timeZone"), 1, 80);
            require(ZoneId.getAvailableZoneIds().contains(zone));
            ZoneId.of(zone);
            Tenant tenant = new Tenant(uuid(rawTenant.get("id")), text(rawTenant.get("name"), 1, 160), zone);

            List<UUID> accounts = new ArrayList<>();
            Set<UUID> accountIds = new HashSet<>();
            for (Object value : array(source.get("accounts"), 1, 250)) {
                UUID id = uuid(value); require(accountIds.add(id)); accounts.add(id);
            }
            List<Unit> units = new ArrayList<>(); Set<UUID> unitIds = new HashSet<>(); Set<String> labels = new HashSet<>();
            for (Object value : array(source.get("units"), 1, 250)) {
                Map<String, Object> raw = object(value, Set.of("id", "label"));
                Unit unit = new Unit(uuid(raw.get("id")), text(raw.get("label"), 1, 40));
                require(unitIds.add(unit.id()) && labels.add(unit.label())); units.add(unit);
            }
            List<Membership> memberships = new ArrayList<>(); Set<UUID> membershipIds = new HashSet<>();
            Set<String> membershipKeys = new HashSet<>(); Set<UUID> coveredAccounts = new HashSet<>(), coveredUnits = new HashSet<>();
            for (Object value : array(source.get("memberships"), 1, 500)) {
                Map<String, Object> raw = object(value, Set.of("id", "accountId", "unitId", "displayName", "role", "validFrom", "validUntil"));
                UUID id = uuid(raw.get("id")), account = uuid(raw.get("accountId"));
                UUID unit = raw.get("unitId") == null ? null : uuid(raw.get("unitId"));
                String role = text(raw.get("role"), 1, 20);
                require(Set.of("RESIDENT", "RESPONSIBLE", "MANAGER").contains(role));
                require("MANAGER".equals(role) ? unit == null : unit != null);
                require(accountIds.contains(account) && (unit == null || unitIds.contains(unit)));
                Instant from = instant(raw.get("validFrom"));
                Instant until = raw.get("validUntil") == null ? null : instant(raw.get("validUntil"));
                require(until == null || until.isAfter(from));
                Membership membership = new Membership(id, account, unit, text(raw.get("displayName"), 1, 160), role, from, until);
                require(membershipIds.add(id) && membershipKeys.add(account + ":" + unit + ":" + role));
                memberships.add(membership); coveredAccounts.add(account); if (unit != null) coveredUnits.add(unit);
            }
            require(coveredAccounts.equals(accountIds) && coveredUnits.equals(unitIds)
                && memberships.stream().anyMatch(membership -> "MANAGER".equals(membership.role())));
            List<Amenity> amenities = new ArrayList<>(); Set<UUID> amenityIds = new HashSet<>();
            for (Object value : array(source.get("amenities"), 1, 100)) {
                Map<String, Object> raw = object(value, Set.of("id", "name", "capacity", "bufferMinutes", "policyVersion",
                    "feeCents", "approvalRequired", "policy"));
                UUID id = uuid(raw.get("id")); require(amenityIds.add(id));
                Map<String, Object> policy = object(raw.get("policy"), POLICY_FIELDS);
                validatePolicy(policy);
                require(raw.get("approvalRequired") instanceof Boolean);
                amenities.add(new Amenity(id, text(raw.get("name"), 1, 160), integer(raw.get("capacity"), 1, Integer.MAX_VALUE),
                    integer(raw.get("bufferMinutes"), 0, 1440), integer(raw.get("policyVersion"), 1, Integer.MAX_VALUE),
                    integral(raw.get("feeCents"), 0, Long.MAX_VALUE), (Boolean) raw.get("approvalRequired"), canonical(policy)));
            }
            return new OnboardingManifest(operation, tenant, accounts, units, memberships, amenities, sha256(canonical(source)));
        } catch (Exception ignored) {
            // The parse cause can contain the original source and must never escape.
            throw invalid();
        }
    }

    private static void validatePolicy(Map<String, Object> policy) {
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (Object value : array(policy.get("weekDays"), 1, 7))
            require(days.add(DayOfWeek.of(integer(value, 1, 7))));
        String opens = text(policy.get("opensAt"), 5, 5), closes = text(policy.get("closesAt"), 5, 5);
        require(opens.matches("[0-2][0-9]:[0-5][0-9]") && closes.matches("[0-2][0-9]:[0-5][0-9]"));
        new ReservationPolicy(integer(policy.get("schemaVersion"), 1, 1), days, LocalTime.parse(opens), LocalTime.parse(closes),
            integer(policy.get("minimumLeadMinutes"), 0, 525600), integer(policy.get("maximumAdvanceDays"), 1, 366),
            integer(policy.get("maximumDurationMinutes"), 1, 1440), integer(policy.get("maximumActivePerUnit"), 1, 100),
            integer(policy.get("cancellationLeadMinutes"), 0, 525600));
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value, Set<String> fields) {
        require(value instanceof Map<?, ?>);
        Map<?, ?> map = (Map<?, ?>) value; require(map.keySet().equals(fields));
        return (Map<String, Object>) map;
    }
    private static List<?> array(Object value, int minimum, int maximum) {
        require(value instanceof List<?>);
        List<?> list = (List<?>) value; require(list.size() >= minimum && list.size() <= maximum); return list;
    }
    private static String text(Object value, int minimum, int maximum) {
        require(value instanceof String); String result = (String) value;
        int length = result.codePointCount(0, result.length());
        require(length >= minimum && length <= maximum && result.equals(result.strip())
            && result.codePoints().noneMatch(Character::isISOControl));
        return result;
    }
    private static UUID uuid(Object value) {
        String text = text(value, 36, 36); UUID id = UUID.fromString(text);
        require(id.toString().equals(text) && !id.equals(new UUID(0, 0))); return id;
    }
    private static Instant instant(Object value) {
        Instant result = Instant.parse(text(value, 20, 40));
        require(result.getNano() % 1000 == 0 && !result.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
            && !result.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")));
        return result;
    }
    private static int integer(Object value, int minimum, int maximum) {
        return (int) integral(value, minimum, maximum);
    }
    private static long integral(Object value, long minimum, long maximum) {
        require(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger);
        BigInteger integer = new BigInteger(value.toString());
        require(integer.compareTo(BigInteger.valueOf(minimum)) >= 0 && integer.compareTo(BigInteger.valueOf(maximum)) <= 0);
        return integer.longValueExact();
    }
    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return JSON.writeValueAsString(value);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(OnboardingManifest::canonical).toList()) + "]";
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> ordered = new TreeMap<>();
            map.forEach((key, item) -> ordered.put((String) key, item));
            List<String> entries = new ArrayList<>();
            ordered.forEach((key, item) -> entries.add(JSON.writeValueAsString(key) + ":" + canonical(item)));
            return "{" + String.join(",", entries) + "}";
        }
        throw invalid();
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ignored) { throw invalid(); }
    }
    private static void require(boolean condition) { if (!condition) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_ONBOARDING_MANIFEST"); }
}
