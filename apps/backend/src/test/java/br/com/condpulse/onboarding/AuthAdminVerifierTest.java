package br.com.condpulse.onboarding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthAdminVerifierTest {
    private static final String KEY = "sb_secret_SYNTHETIC_AUTH_VERIFIER_ONLY_1234567890";
    private static final URI ORIGIN = URI.create("https://auth.synthetic.invalid");
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final UUID ACCOUNT = UUID.fromString("11000000-0000-4000-8000-000000000001");
    private static final String USER = "{\"id\":\"" + ACCOUNT + "\",\"email_confirmed_at\":\"2026-01-01T00:00:00Z\"}";

    private static HttpClient client() {
        HttpClient client = mock(HttpClient.class); when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER); return client;
    }
    @SuppressWarnings("unchecked") private static void response(HttpClient client, int status, String contentType, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(contentType == null ? Map.of() : Map.of("Content-Type", List.of(contentType)), (key, value) -> true));
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(CompletableFuture.completedFuture(response));
    }
    private static AuthAdminVerifier verifier(HttpClient client) {
        return new AuthAdminVerifier(client, ORIGIN, KEY, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static void failure(String code, Runnable call) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, call::run);
        assertEquals(code, failure.getMessage()); assertNull(failure.getCause()); assertFalse(failure.toString().contains(KEY));
    }

    @Test void readsOnlyExactAdminGetWithSecretOnApikeyAndNoBearerOrBody() {
        HttpClient client = client(); response(client, 200, "application/json; charset=utf-8", USER);
        AuthAdminVerifier verifier = verifier(client); var proof = verifier.verify(List.of(ACCOUNT));
        assertEquals(java.util.Set.of(ACCOUNT), proof.accounts()); assertEquals(NOW, proof.checkedAt());
        assertEquals("AuthAdminVerifier[redacted]", verifier.toString());
        assertEquals("AuthAdminVerifier.Eligibility[redacted]", proof.toString());
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(request.capture(), any());
        assertEquals("GET", request.getValue().method());
        assertEquals(ORIGIN + "/auth/v1/admin/users/" + ACCOUNT, request.getValue().uri().toString());
        assertEquals(KEY, request.getValue().headers().firstValue("apikey").orElseThrow());
        assertTrue(request.getValue().headers().firstValue("Authorization").isEmpty());
        assertTrue(request.getValue().bodyPublisher().isEmpty()); assertEquals(Duration.ofSeconds(5), request.getValue().timeout().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> proof.accounts().clear());
    }

    @Test void rejectsUnknownProjectLegacyOrPublicKeysAndUnsafeTestOrigins() {
        failure("ONBOARDING_AUTH_UNAVAILABLE", () -> AuthAdminVerifier.forProject("bbbbbbbbbbbbbbbbbbbb", KEY));
        for (String key : List.of("eyJsynthetic.jwt.legacy", "sb_publishable_synthetic_1234567890", "sb_secret_short", KEY + "\n"))
            failure("ONBOARDING_AUTH_UNAVAILABLE", () -> AuthAdminVerifier.forProject(OnboardingManifest.PROJECT_REF, key));
        for (String origin : List.of("http://auth.synthetic.invalid", "https://user@auth.synthetic.invalid", "https://auth.synthetic.invalid/", "https://auth.synthetic.invalid?query=1"))
            failure("ONBOARDING_AUTH_UNAVAILABLE", () -> new AuthAdminVerifier(client(), URI.create(origin), KEY, Clock.systemUTC()));
        HttpClient redirecting = client(); when(redirecting.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
        failure("ONBOARDING_AUTH_UNAVAILABLE", () -> verifier(redirecting));
    }

    @Test void omitsDeletedAndBanFieldsSafelyButRejectsIneligibleIdentityOrUntrustedMetadata() {
        for (String body : List.of(USER.replace(ACCOUNT.toString(), UUID.randomUUID().toString()),
                USER.replace("2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"),
                USER.replace("\"email_confirmed_at\":\"2026-01-01T00:00:00Z\"", "\"email_confirmed_at\":null,\"user_metadata\":{\"email_confirmed_at\":\"2026-01-01T00:00:00Z\"}"),
                USER.substring(0, USER.length()-1) + ",\"deleted_at\":\"2026-01-01T00:00:00Z\"}",
                USER.substring(0, USER.length()-1) + ",\"banned_until\":\"2027-01-01T00:00:00Z\"}",
                USER.substring(0, USER.length()-1) + ",\"banned_until\":123}")) {
            HttpClient client = client(); response(client, 200, "application/json", body);
            failure("ONBOARDING_AUTH_IDENTITY_INELIGIBLE", () -> verifier(client).verify(List.of(ACCOUNT)));
        }
        HttpClient client = client(); response(client, 200, "application/json", USER.substring(0, USER.length()-1)
            + ",\"deleted_at\":null,\"banned_until\":\"2026-01-01T00:00:00Z\",\"user_metadata\":{\"role\":\"ADMIN\"}}");
        assertEquals(java.util.Set.of(ACCOUNT), verifier(client).verify(List.of(ACCOUNT)).accounts());
    }

    @Test void redirectsAuthFailuresUnavailableAndInvalidBodiesNeverLeakProviderMaterial() {
        for (int status : List.of(301, 302, 401, 403, 429, 500)) {
            HttpClient client = client(); response(client, status, "application/json", KEY);
            failure("ONBOARDING_AUTH_UNAVAILABLE", () -> verifier(client).verify(List.of(ACCOUNT)));
            verify(client, times(1)).sendAsync(any(), any());
        }
        HttpClient missing = client(); response(missing, 404, "application/json", KEY);
        failure("ONBOARDING_AUTH_IDENTITY_INELIGIBLE", () -> verifier(missing).verify(List.of(ACCOUNT)));
        for (String body : List.of(KEY, USER + "{}", USER.replace("\"id\":", "\"id\":\"bad\",\"id\":"), " ".repeat(65537))) {
            HttpClient client = client(); response(client, 200, "application/json", body);
            failure("ONBOARDING_AUTH_UNAVAILABLE", () -> verifier(client).verify(List.of(ACCOUNT)));
        }
        HttpClient html = client(); response(html, 200, "text/html", USER);
        failure("ONBOARDING_AUTH_UNAVAILABLE", () -> verifier(html).verify(List.of(ACCOUNT)));
    }

    @Test void unavailableTransportAndProofOlderThanTwoMinutesFailClosed() {
        HttpClient unavailable = client(); when(unavailable.sendAsync(any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException(KEY)));
        failure("ONBOARDING_AUTH_UNAVAILABLE", () -> verifier(unavailable).verify(List.of(ACCOUNT)));
        HttpClient client = client(); response(client, 200, "application/json", USER);
        Clock advancing = new Clock() {
            private int calls;
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return calls++ < 2 ? NOW : NOW.plusSeconds(121); }
        };
        failure("ONBOARDING_AUTH_PROOF_EXPIRED", () -> new AuthAdminVerifier(client, ORIGIN, KEY, advancing).verify(List.of(ACCOUNT)));
    }

    @Test void streamingBodyLimitCancelsBeforeRetainingOversizedPayload() {
        var subscriber = new AuthAdminVerifier.BoundedBodySubscriber(); Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[65536])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        verify(subscription).cancel();
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
