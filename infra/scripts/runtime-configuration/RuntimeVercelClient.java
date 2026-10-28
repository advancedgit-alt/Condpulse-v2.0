package br.com.condpulse.bootstrap;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Isolated official Vercel API transport. Never retrieves a decrypted secret. */
public final class RuntimeVercelClient implements RuntimeConfiguration.Gateway {
    private static final URI PRODUCTION_ORIGIN = URI.create("https://api.vercel.com");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY = 256 * 1024;
    private static final Set<String> CONFIG_KEYS = Set.of("DATABASE_USER", "DATABASE_URL", "CP_VERCEL_DB_CA_BASE64",
        "PORT", "DB_POOL_SIZE", "CP_JWT_ISSUER", "CP_JWT_JWK_SET_URI", "CP_BFF_ORIGIN",
        "CP_BFF_ENABLED", "CP_RESERVATIONS_ENABLED");
    // Only these seven pre-existing, non-secret UI settings may use the single-variable endpoint.
    // The collection endpoint's deprecated decrypt=false does not prove plaintext for legacy encrypted rows.
    private static final Set<String> PUBLIC_LEGACY_KEYS = Set.of("PORT", "DB_POOL_SIZE", "CP_JWT_ISSUER",
        "CP_JWT_JWK_SET_URI", "CP_BFF_ORIGIN", "CP_BFF_ENABLED", "CP_RESERVATIONS_ENABLED");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient client;
    private final URI origin;
    private final String token;
    private boolean projectVerified;

    /** No caller-controlled origin, redirect, proxy, insecure TLS or retry option. */
    public RuntimeVercelClient(String token) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build(), PRODUCTION_ORIGIN, token);
    }

    /** Package-local loopback transport injection for the isolated HTTP harness, never a CLI flag. */
    RuntimeVercelClient(HttpClient client, URI origin, String token) {
        boolean production = PRODUCTION_ORIGIN.equals(origin);
        boolean loopback = origin != null && "127.0.0.1".equals(origin.getHost()) && origin.getPort() > 0
            && ("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()));
        require(client != null && client.followRedirects() == HttpClient.Redirect.NEVER && origin != null
            && (production || loopback) && origin.getRawUserInfo() == null && origin.getRawQuery() == null
            && origin.getRawFragment() == null && "".equals(origin.getRawPath())
            && token != null && token.matches("[!-~]{16,4096}"), "RUNTIME_VERCEL_CLIENT_INVALID");
        this.client = client; this.origin = origin; this.token = token;
    }

    @Override public Map<String, RuntimeConfiguration.Entry> inspect() throws Exception {
        projectVerified = false;
        // Only this exact project endpoint is used: never enumerate a team or any other project.
        Map<?, ?> project = object(request("GET", "/v9/projects/" + RuntimeConfiguration.VERCEL_PROJECT
            + "?teamId=" + RuntimeConfiguration.TEAM, null));
        Map<?, ?> link = object(project.get("link"));
        require(RuntimeConfiguration.VERCEL_PROJECT.equals(project.get("id"))
            && RuntimeConfiguration.TEAM.equals(project.get("accountId"))
            && "github".equals(link.get("type")) && "advancedgit-alt".equals(link.get("org"))
            && "Condpulse-v2.0".equals(link.get("repo")), "RUNTIME_VERCEL_PROJECT_MISMATCH");
        Object response = request("GET", envPath() + "&decrypt=false", null);
        List<?> values;
        if (response instanceof List<?> list) values = list;
        else values = list(object(response).get("envs"));
        require(values.size() <= 1000, "RUNTIME_VERCEL_RESPONSE_INVALID");
        Map<String, RuntimeConfiguration.Entry> result = new HashMap<>();
        for (Object value : values) {
            Map<?, ?> env = object(value);
            List<String> targets = strings(env.get("target"));
            if (!targets.contains("production")) continue;
            String key = text(env.get("key"), true), type = text(env.get("type"), true);
            String visibility = text(env.get("visibility"), false);
            if ("DATABASE_PASSWORD".equals(key)) {
                require(("sensitive".equals(type) || "secret".equals(type))
                    && !"config".equals(visibility) && !Boolean.TRUE.equals(env.get("decrypted")),
                    "RUNTIME_VERCEL_SECRET_TYPE_INVALID");
            }
            String configValue = null;
            if (CONFIG_KEYS.contains(key) && !"secret".equals(visibility)
                    && !Set.of("sensitive", "secret").contains(type)
                    && ("plain".equals(type) || Boolean.TRUE.equals(env.get("decrypted")))) {
                configValue = text(env.get("value"), false);
            }
            if (PUBLIC_LEGACY_KEYS.contains(key) && configValue == null) {
                require("encrypted".equals(type) && !"secret".equals(visibility)
                    && targets.equals(List.of("production")) && env.get("gitBranch") == null,
                    "RUNTIME_VERCEL_PUBLIC_CONFIG_INVALID");
                String id = text(env.get("id"), true);
                require(id.matches("[A-Za-z0-9_-]{1,256}"), "RUNTIME_VERCEL_RESPONSE_INVALID");
                // This is deliberately NOT a project-wide decrypt=true request. Secret IDs never enter this path.
                Map<?, ?> clear = object(request("GET", "/v1/projects/" + RuntimeConfiguration.VERCEL_PROJECT
                    + "/env/" + id + "?teamId=" + RuntimeConfiguration.TEAM, null));
                require(key.equals(clear.get("key")) && id.equals(clear.get("id"))
                    && "encrypted".equals(clear.get("type")) && !"secret".equals(clear.get("visibility"))
                    && Boolean.TRUE.equals(clear.get("decrypted"))
                    && strings(clear.get("target")).equals(List.of("production")) && clear.get("gitBranch") == null
                    && revision(env.get("updatedAt")).equals(revision(clear.get("updatedAt"))),
                    "RUNTIME_VERCEL_PUBLIC_CONFIG_INVALID");
                configValue = text(clear.get("value"), true);
            }
            // In particular, no env.get("value") is performed for passwords, BFF keys or unknown names.
            String revision = revision(env.get("updatedAt"));
            if (CONFIG_KEYS.contains(key) || "DATABASE_PASSWORD".equals(key))
                require(!env.containsKey("customEnvironmentIds") || list(env.get("customEnvironmentIds")).isEmpty(),
                    "RUNTIME_VERCEL_ENV_TARGET_INVALID");
            var entry = new RuntimeConfiguration.Entry(text(env.get("id"), true), key, type, visibility,
                List.copyOf(targets), text(env.get("gitBranch"), false), text(env.get("comment"), false), configValue, revision);
            require(result.put(key, entry) == null, "RUNTIME_VERCEL_AMBIGUOUS_ENV");
        }
        projectVerified = true;
        return Map.copyOf(result);
    }

    @Override public void put(List<RuntimeConfiguration.Change> changes) throws Exception {
        require(projectVerified && changes != null && !changes.isEmpty() && changes.size() <= CONFIG_KEYS.size() + 1,
            "RUNTIME_VERCEL_CHANGES_INVALID");
        Set<String> expected = new HashSet<>(); List<Map<String, Object>> body = new ArrayList<>();
        for (var change : changes) {
            require(change != null && expected.add(change.key())
                && (CONFIG_KEYS.contains(change.key()) || "DATABASE_PASSWORD".equals(change.key()))
                && change.value() != null && !change.value().isEmpty() && change.value().length() <= 128 * 1024
                && change.value().chars().noneMatch(c -> c == 0 || c == '\r' || c == '\n')
                && change.secret() == "DATABASE_PASSWORD".equals(change.key())
                && change.comment() != null && change.comment().matches("condpulse-runtime:"
                    + RuntimeConfiguration.PROJECT + ":[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}:[a-f0-9]{40}"),
                "RUNTIME_VERCEL_CHANGES_INVALID");
            if ("CP_BFF_ENABLED".equals(change.key()) || "CP_RESERVATIONS_ENABLED".equals(change.key()))
                require("false".equals(change.value()), "RUNTIME_VERCEL_ACTIVATION_FORBIDDEN");
            Map<String, Object> item = new HashMap<>();
            item.put("key", change.key()); item.put("value", change.value());
            // All ten Config values are explicitly public, so plain avoids ambiguous legacy ciphertext readback.
            item.put("type", change.secret() ? "sensitive" : "plain");
            item.put("visibility", change.secret() ? "secret" : "config");
            item.put("target", List.of("production")); item.put("comment", change.comment());
            item.put("customEnvironmentIds", List.of()); item.put("gitBranch", null); body.add(item);
        }
        // Official v10 bulk response is created + failed; HTTP success alone is not a complete write.
        // https://vercel.com/docs/rest-api/projects/create-one-or-more-environment-variables
        // https://vercel.com/docs/environment-variables/sensitive-environment-variables
        Map<?, ?> response = object(request("POST", envPath() + "&upsert=true", body));
        require(!response.containsKey("error") && !response.containsKey("errors")
            && (!response.containsKey("status") || "success".equals(response.get("status")))
            && response.get("failed") instanceof List<?> failed && failed.isEmpty(), "RUNTIME_VERCEL_PARTIAL_WRITE");
        Object created = response.get("created");
        List<?> createdValues = created instanceof List<?> many ? many : created instanceof Map<?, ?> one ? List.of(one) : List.of();
        Set<String> actual = new HashSet<>();
        for (Object value : createdValues) {
            Map<?, ?> env = object(value);
            require(!env.containsKey("error") && !env.containsKey("errors")
                && (!env.containsKey("status") || "success".equals(env.get("status")))
                && actual.add(text(env.get("key"), true)),
                "RUNTIME_VERCEL_PARTIAL_WRITE");
        }
        require(actual.equals(expected), "RUNTIME_VERCEL_PARTIAL_WRITE");
    }

    private String envPath() { return "/v10/projects/" + RuntimeConfiguration.VERCEL_PROJECT + "/env?teamId=" + RuntimeConfiguration.TEAM; }

    private Object request(String method, String path, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(TIMEOUT)
            .header("Authorization", "Bearer " + token).header("Accept", "application/json");
        if ("GET".equals(method)) builder.GET();
        else if ("POST".equals(method)) builder.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        else throw failure("RUNTIME_VERCEL_METHOD_FORBIDDEN");
        CompletableFuture<HttpResponse<byte[]>> pending = null; HttpResponse<byte[]> response;
        try {
            pending = client.sendAsync(builder.build(), ignored -> new BoundedBodySubscriber());
            response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            if (pending != null) pending.cancel(true); Thread.currentThread().interrupt();
            throw failure("RUNTIME_VERCEL_UNAVAILABLE");
        } catch (Exception ignored) {
            if (pending != null) pending.cancel(true); throw failure("RUNTIME_VERCEL_UNAVAILABLE");
        }
        require(response.statusCode() >= 200 && response.statusCode() <= 299,
            "RUNTIME_VERCEL_HTTP_" + response.statusCode());
        require("application/json".equalsIgnoreCase(response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim()),
            "RUNTIME_VERCEL_RESPONSE_INVALID");
        try { return JSON.readValue(response.body(), Object.class); }
        catch (Exception ignored) { throw failure("RUNTIME_VERCEL_RESPONSE_INVALID"); }
    }

    private static Map<?, ?> object(Object value) {
        require(value instanceof Map<?, ?>, "RUNTIME_VERCEL_RESPONSE_INVALID"); return (Map<?, ?>) value;
    }
    private static List<?> list(Object value) {
        require(value instanceof List<?>, "RUNTIME_VERCEL_RESPONSE_INVALID"); return (List<?>) value;
    }
    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : list(value)) result.add(text(item, true)); return result;
    }
    private static String text(Object value, boolean required) {
        if (value == null && !required) return null;
        require(value instanceof String && (!required || !((String) value).isEmpty()), "RUNTIME_VERCEL_RESPONSE_INVALID");
        return (String) value;
    }
    private static String revision(Object value) {
        if (value == null) return "";
        String revision = value instanceof Number ? value.toString() : text(value, true);
        require(revision.matches("[0-9]{1,20}"), "RUNTIME_VERCEL_RESPONSE_INVALID"); return revision;
    }
    private static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
    @Override public String toString() { return "RuntimeVercelClient[redacted]"; }

    /** Limits bytes during streaming, and the enclosing future also bounds total body time. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value; value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(failure("RUNTIME_VERCEL_RESPONSE_INVALID")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { result.completeExceptionally(failure("RUNTIME_VERCEL_UNAVAILABLE")); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
