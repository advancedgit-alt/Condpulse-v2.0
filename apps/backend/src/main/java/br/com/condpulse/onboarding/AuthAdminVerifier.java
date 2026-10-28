package br.com.condpulse.onboarding;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Read-only Auth proof for the isolated operator CLI; never a Spring bean or API credential. */
public final class AuthAdminVerifier {
    private static final int MAXIMUM_BODY_BYTES = 64 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient client;
    private final URI origin;
    private final String secretKey;
    private final Clock clock;

    record Eligibility(Set<UUID> accounts, Instant checkedAt) {
        Eligibility {
            accounts = Set.copyOf(accounts);
            java.util.Objects.requireNonNull(checkedAt);
        }
        @Override public String toString() { return "AuthAdminVerifier.Eligibility[redacted]"; }
    }

    /** The production factory has no configurable origin, path, method, TLS bypass or redirect. */
    public static AuthAdminVerifier forProject(String projectRef, String secretKey) {
        if (!OnboardingManifest.PROJECT_REF.equals(projectRef)) throw unavailable();
        return new AuthAdminVerifier(HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build(),
            URI.create("https://" + OnboardingManifest.PROJECT_REF + ".supabase.co"), secretKey, Clock.systemUTC());
    }

    /** Package-local injection is only for deterministic protocol tests; the CLI uses forProject. */
    AuthAdminVerifier(HttpClient client, URI origin, String secretKey, Clock clock) {
        if (client == null || origin == null || clock == null || !"https".equals(origin.getScheme())
                || origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                || origin.getFragment() != null || (origin.getPath() != null && !origin.getPath().isEmpty())
                || secretKey == null || secretKey.length() < 32 || secretKey.length() > 4096
                || !secretKey.matches("sb_secret_[A-Za-z0-9_-]+")
                || client.followRedirects() != HttpClient.Redirect.NEVER) throw unavailable();
        this.client = client; this.origin = origin; this.secretKey = secretKey; this.clock = clock;
    }

    // Official endpoint: supabase/auth-js GoTrueAdminApi.getUserById; API-key docs
    // require new sb_secret keys on apikey, not Authorization: Bearer (not a JWT).
    // https://supabase.com/docs/reference/javascript/auth-admin-getuserbyid
    // https://supabase.com/docs/guides/getting-started/api-keys
    public Eligibility verify(List<UUID> accounts) {
        if (accounts == null || accounts.isEmpty() || accounts.size() > 250 || accounts.stream().anyMatch(java.util.Objects::isNull))
            throw ineligible();
        Set<UUID> expected = Set.copyOf(accounts);
        if (expected.size() != accounts.size() || expected.contains(new UUID(0, 0))) throw ineligible();
        Instant checkedAt = clock.instant();
        for (UUID account : accounts) {
            fresh(checkedAt);
            HttpRequest request = HttpRequest.newBuilder(origin.resolve("/auth/v1/admin/users/" + account))
                .timeout(TIMEOUT).header("apikey", secretKey).header("Accept", "application/json").GET().build();
            HttpResponse<byte[]> response;
            CompletableFuture<HttpResponse<byte[]>> pending = null;
            try {
                pending = client.sendAsync(request, ignored -> new BoundedBodySubscriber());
                response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                if (pending != null) pending.cancel(true);
                Thread.currentThread().interrupt(); throw unavailable();
            } catch (Exception ignored) {
                if (pending != null) pending.cancel(true);
                throw unavailable();
            }
            fresh(checkedAt);
            if (response.statusCode() == 404) throw ineligible();
            if (response.statusCode() != 200) throw unavailable();
            String mediaType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            byte[] body = response.body();
            if (!"application/json".equalsIgnoreCase(mediaType) || body == null || body.length == 0 || body.length > MAXIMUM_BODY_BYTES)
                throw unavailable();
            Map<?, ?> user;
            try {
                Object parsed = JSON.readValue(body, Object.class);
                if (!(parsed instanceof Map<?, ?> object)) throw unavailable();
                user = object;
            } catch (Exception ignored) { throw unavailable(); }
            Instant now = clock.instant(); fresh(checkedAt);
            if (!account.toString().equals(user.get("id")) || user.get("deleted_at") != null
                    || !eligibleTimestamp(user.get("email_confirmed_at"), now, true)
                    || !eligibleTimestamp(user.get("banned_until"), now, false)) throw ineligible();
            // Do not retain email, metadata, provider identities or any other response field.
        }
        fresh(checkedAt);
        return new Eligibility(expected, checkedAt);
    }

    private static boolean eligibleTimestamp(Object value, Instant now, boolean required) {
        if (value == null) return !required;
        if (!(value instanceof String timestamp)) return false;
        try { return !Instant.parse(timestamp).isAfter(now); }
        catch (Exception ignored) { return false; }
    }
    private void fresh(Instant checkedAt) {
        Instant now = clock.instant();
        if (now.isBefore(checkedAt) || now.isAfter(checkedAt.plusSeconds(120)))
            throw new IllegalStateException("ONBOARDING_AUTH_PROOF_EXPIRED");
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("ONBOARDING_AUTH_UNAVAILABLE"); }
    private static IllegalStateException ineligible() { return new IllegalStateException("ONBOARDING_AUTH_IDENTITY_INELIGIBLE"); }
    @Override public String toString() { return "AuthAdminVerifier[redacted]"; }

    /** Enforces the byte limit while streaming, before an untrusted body can grow in memory. */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value; subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAXIMUM_BODY_BYTES - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(unavailable()); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { result.completeExceptionally(unavailable()); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
