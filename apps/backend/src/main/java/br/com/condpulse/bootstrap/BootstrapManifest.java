package br.com.condpulse.bootstrap;

import java.io.InputStream;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/** Fonte imutavel do bootstrap; erros nunca incluem valores, caminhos ou SQL. */
public final class BootstrapManifest {
    private static final String PROJECT = "rpnrqfrfwywglvyykgrg";
    private static final String SOURCE_COMMIT = "b933891c784de8cc88c5886768bd52b7b6134c30";
    private static final int MANIFEST_LENGTH = 64970;
    private static final String MANIFEST_HASH = "45fd75474bbbc505d6cea3232b19b19c03f82c6f2a8e5bc57bc65dbb00559d5e";
    private static final List<Source> SOURCES = List.of(
        new Source("V001__foundation.sql", "5c37836466ac173716a520677a23e3e0856e2756", 9510,
            "3c733f81a3cc0eda27f5f139b351848e02b3674d2241799fa84f0b676acc2402",
            "4db4bab3b11d93951d154a17c629b810063ed5029617d48ba6454d6cf5c654b4"),
        new Source("V002__roles_and_rls.sql", "474e69d51038cbfd323487a5aa6f67e560cddc8a", 3987,
            "37235ddb43f981b98675c0ab3a4b0c5740b995edb60993bcde70c841cdefc998",
            "d897dc09b4be141bdeb60e248ad2840665b91a09eb4b74a958ebd27e3bdf31d8")
    );

    private BootstrapManifest() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> load() {
        try (InputStream input = BootstrapManifest.class.getResourceAsStream("/bootstrap/foundation-manifest.json")) {
            if (input == null) throw new IllegalStateException();
            byte[] bytes = input.readNBytes(MANIFEST_LENGTH + 1);
            // O inventario esperado tambem faz parte da atestacao imutavel.
            if (bytes.length != MANIFEST_LENGTH || !MANIFEST_HASH.equals(digest(bytes))) throw new IllegalStateException();
            Map<String, Object> result = JsonMapper.builder().build().readValue(bytes, Map.class);
            if (result == null) throw new IllegalStateException();
            return result;
        } catch (Exception ignored) {
            throw new IllegalStateException("BOOTSTRAP_MANIFEST_UNAVAILABLE");
        }
    }

    /** Objetos ordenados por chave; colecoes sao multisets e preservam duplicatas. */
    public static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof String string) return quote(string);
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger) return value.toString();
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("CANONICAL_NUMBER_UNSUPPORTED");
            return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("CANONICAL_KEY_UNSUPPORTED");
                sorted.put(key, entry.getValue());
            }
            List<String> fields = new ArrayList<>();
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                fields.add(quote(entry.getKey()) + ":" + canonical(entry.getValue()));
            }
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Collection<?> collection) {
            List<String> elements = collection.stream().map(BootstrapManifest::canonical).sorted().toList();
            return "[" + String.join(",", elements) + "]";
        }
        if (value.getClass().isArray()) {
            List<Object> elements = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) elements.add(Array.get(value, index));
            return canonical(elements);
        }
        throw new IllegalArgumentException("CANONICAL_VALUE_UNSUPPORTED");
    }

    public static String sha256(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Nunca utiliza nomes/caminhos vindos do manifesto para abrir recursos. */
    public static List<String> sourceCodes(Map<String, Object> manifest) {
        Set<String> codes = new LinkedHashSet<>();
        if (manifest == null) return List.of("MANIFEST_METADATA_MISMATCH");
        if (!PROJECT.equals(manifest.get("projectRef")) || !"sa-east-1".equals(manifest.get("region"))
                || !SOURCE_COMMIT.equals(manifest.get("sourceCommit"))
                || !"20261027225434".equals(manifest.get("supabaseMigration"))
                || !"postgres".equals(manifest.get("owner"))
                || !"002".equals(manifest.get("futureFlywayBaselineVersion"))
                || !Boolean.FALSE.equals(manifest.get("baselineExecuted"))
                || !same(manifest.get("appVersions"), List.of("001", "002"))) {
            codes.add("MANIFEST_METADATA_MISMATCH");
        }
        List<Map<String, Object>> canonicalSources = new ArrayList<>();
        List<Map<String, Object>> executedSources = new ArrayList<>();
        for (Source source : SOURCES) {
            canonicalSources.add(Map.of("name", source.name(), "gitBlob", source.blob(),
                "sha256", source.canonicalHash(), "bytes", source.length()));
            executedSources.add(Map.of("name", source.name(), "sha256", source.executedHash()));
            try (InputStream input = BootstrapManifest.class.getResourceAsStream("/db/migration/" + source.name())) {
                if (input == null) { codes.add("MIGRATION_RESOURCE_UNAVAILABLE"); continue; }
                byte[] bytes = input.readNBytes(source.length() + 2);
                if (bytes.length != source.length() || !source.canonicalHash().equals(digest(bytes))) {
                    codes.add("MIGRATION_SOURCE_HASH_MISMATCH");
                    continue;
                }
                byte[] executed = Arrays.copyOf(bytes, bytes.length + 1);
                executed[bytes.length] = '\n';
                if (!source.executedHash().equals(digest(executed))) codes.add("MIGRATION_EXECUTED_HASH_MISMATCH");
            } catch (Exception ignored) {
                codes.add("MIGRATION_RESOURCE_UNAVAILABLE");
            }
        }
        if (!same(manifest.get("canonicalGitSourceSha256"), canonicalSources)) codes.add("MANIFEST_SOURCE_RECORDS_MISMATCH");
        if (!same(manifest.get("executedSourceSha256"), executedSources)
                || !same(manifest.get("sha256"), executedSources)) codes.add("MANIFEST_EXECUTED_SOURCE_MISMATCH");
        return List.copyOf(codes);
    }

    private static boolean same(Object first, Object second) {
        try { return canonical(first).equals(canonical(second)); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ignored) { throw new IllegalStateException("HASH_UNAVAILABLE"); }
    }

    private static String quote(String string) {
        StringBuilder output = new StringBuilder("\"");
        for (int index = 0; index < string.length(); index++) {
            char character = string.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        output.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", (int) character));
                    } else output.append(character);
                }
            }
        }
        return output.append('"').toString();
    }

    private record Source(String name, String blob, int length, String canonicalHash, String executedHash) { }
}
