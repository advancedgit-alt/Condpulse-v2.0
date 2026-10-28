package br.com.condpulse.bootstrap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic loopback HTTP only. Never packaged in an image or called by the manual workflow. */
public final class RuntimeVercelHttpHarness {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOKEN = "SYNTHETIC_VERCEL_TOKEN_~+/=NEVER_OUTPUT";
    private static final String PASSWORD = "SYNTHETIC_DATABASE_PASSWORD_32_BYTES_MINIMUM_NEVER_OUTPUT";
    private static final String COMMENT = "condpulse-runtime:" + RuntimeConfiguration.PROJECT
        + ":11111111-2222-4333-8444-555555555555:" + "a".repeat(40);
    private static final String PROJECT_PATH = "/v9/projects/" + RuntimeConfiguration.VERCEL_PROJECT;
    private static final String ENV_PATH = "/v10/projects/" + RuntimeConfiguration.VERCEL_PROJECT + "/env";
    private static final AtomicInteger calls = new AtomicInteger();
    private static final AtomicInteger posts = new AtomicInteger();
    private static final AtomicInteger publicReads = new AtomicInteger();
    private static final Map<String,String> LEGACY = Map.of("PORT","8080","DB_POOL_SIZE","2",
        "CP_JWT_ISSUER","https://"+RuntimeConfiguration.PROJECT+".supabase.co/auth/v1",
        "CP_JWT_JWK_SET_URI","https://"+RuntimeConfiguration.PROJECT+".supabase.co/auth/v1/.well-known/jwks.json",
        "CP_BFF_ORIGIN","https://project-rhvdq.vercel.app","CP_BFF_ENABLED","false","CP_RESERVATIONS_ENABLED","false");
    private static volatile String mode = "valid";
    private static volatile boolean protocolInvalid;

    private static void require(boolean value) { if (!value) throw new AssertionError("SYNTHETIC_HTTP_ASSERTION_FAILED"); }
    private static Map<String, Object> env(String key, String type, String visibility, String value) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", "env_synthetic_" + key); item.put("key", key); item.put("type", type);
        if (visibility != null) item.put("visibility", visibility);
        item.put("value", value); item.put("target", List.of("production")); item.put("gitBranch", null);
        item.put("comment", COMMENT); item.put("updatedAt", 1234567890123L); return item;
    }
    private static Object environments() {
        Map<String, Object> password = env("DATABASE_PASSWORD", "sensitive", "secret", PASSWORD);
        if ("unsafeSecret".equals(mode)) { password.put("type", "plain"); password.put("visibility", "config"); }
        List<Map<String, Object>> values = new ArrayList<>(); values.add(password);
        values.add(env("DATABASE_USER", "plain", "config", "cp_runtime_synthetic." + RuntimeConfiguration.PROJECT));
        values.add(env("DATABASE_URL", "encrypted", null, "SYNTHETIC_OPAQUE_CIPHERTEXT"));
        values.add(env("UNKNOWN_SECRET", "sensitive", "secret", "SYNTHETIC_UNRELATED_SECRET"));
        Map<String, Object> preview = env("DATABASE_PASSWORD", "sensitive", "secret", "SYNTHETIC_PREVIEW_SECRET");
        preview.put("target", List.of("preview")); values.add(preview);
        if ("duplicate".equals(mode)) values.add(password);
        if (mode.startsWith("legacy")) for(String key:List.of("PORT","DB_POOL_SIZE","CP_JWT_ISSUER","CP_JWT_JWK_SET_URI","CP_BFF_ORIGIN","CP_BFF_ENABLED","CP_RESERVATIONS_ENABLED"))
            values.add(env(key,"encrypted",null,"SYNTHETIC_CIPHERTEXT_NOT_A_PUBLIC_CONFIG_VALUE"));
        return Map.of("envs", values);
    }
    private static void response(HttpExchange exchange, int status, byte[] bytes) throws Exception {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private static void json(HttpExchange exchange, int status, Object value) throws Exception {
        response(exchange, status, JSON.writeValueAsBytes(value));
    }
    private static void handle(HttpExchange exchange) {
        try {
            calls.incrementAndGet();
            String path = exchange.getRequestURI().getPath(), query = exchange.getRequestURI().getRawQuery();
            String method = exchange.getRequestMethod();
            if (!TOKEN.equals(exchange.getRequestHeaders().getFirst("Authorization").replace("Bearer ", ""))) protocolInvalid = true;
            if (PROJECT_PATH.equals(path) && "GET".equals(method)) {
                if (!("teamId=" + RuntimeConfiguration.TEAM).equals(query)) protocolInvalid = true;
                json(exchange, 200, Map.of("id", "foreignProject".equals(mode) ? "prj_foreign" : RuntimeConfiguration.VERCEL_PROJECT,
                    "accountId", RuntimeConfiguration.TEAM, "link", Map.of("type", "github", "org", "advancedgit-alt", "repo", "Condpulse-v2.0")));
            } else if (path.startsWith("/v1/projects/"+RuntimeConfiguration.VERCEL_PROJECT+"/env/") && "GET".equals(method)) {
                String id=path.substring(path.lastIndexOf('/')+1), key=id.replace("env_synthetic_","");
                if(!LEGACY.containsKey(key) || !("teamId="+RuntimeConfiguration.TEAM).equals(query)) { protocolInvalid=true;json(exchange,418,Map.of());return; }
                publicReads.incrementAndGet();
                Map<String,Object> clear=env(key,"encrypted",null,LEGACY.get(key));clear.put("decrypted",true);
                if("legacyForeign".equals(mode)) {clear.put("key","DATABASE_PASSWORD");clear.put("value",PASSWORD);}
                json(exchange,200,clear);
            } else if (ENV_PATH.equals(path) && "GET".equals(method)) {
                if (!("teamId=" + RuntimeConfiguration.TEAM + "&decrypt=false").equals(query)) protocolInvalid = true;
                if ("forbidden".equals(mode)) { json(exchange, 403, Map.of("error", PASSWORD)); return; }
                if ("redirect".equals(mode)) {
                    exchange.getResponseHeaders().add("Location", "/must-not-follow"); json(exchange, 302, Map.of("error", PASSWORD)); return;
                }
                if ("oversized".equals(mode)) {
                    response(exchange, 200, ("{\"padding\":\"" + "x".repeat(300 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8)); return;
                }
                if ("duplicateJson".equals(mode)) {
                    response(exchange, 200, "{\"envs\":[],\"envs\":[]}".getBytes(StandardCharsets.UTF_8)); return;
                }
                json(exchange, 200, environments());
            } else if (ENV_PATH.equals(path) && "POST".equals(method)) {
                posts.incrementAndGet();
                if (!("teamId=" + RuntimeConfiguration.TEAM + "&upsert=true").equals(query)) protocolInvalid = true;
                Object parsed = JSON.readValue(exchange.getRequestBody().readNBytes(256 * 1024 + 1), Object.class);
                if (!(parsed instanceof List<?> changes)) { protocolInvalid = true; json(exchange, 400, Map.of()); return; }
                List<Map<String, Object>> created = new ArrayList<>();
                for (Object value : changes) {
                    if (!(value instanceof Map<?, ?> item)) { protocolInvalid = true; continue; }
                    boolean secret = "DATABASE_PASSWORD".equals(item.get("key"));
                    if (!List.of("production").equals(item.get("target")) || item.get("gitBranch") != null
                            || !List.of().equals(item.get("customEnvironmentIds")) || !COMMENT.equals(item.get("comment"))) protocolInvalid = true;
                    if (secret && (!PASSWORD.equals(item.get("value")) || !"sensitive".equals(item.get("type"))
                            || !"secret".equals(item.get("visibility")))) protocolInvalid = true;
                    if (!secret && (!"plain".equals(item.get("type")) || !"config".equals(item.get("visibility")))) protocolInvalid = true;
                    created.add(Map.of("key", item.get("key"), "id", "env_created_synthetic"));
                }
                if ("partial".equals(mode)) { json(exchange, 201, Map.of("created", created, "failed", List.of(Map.of("error", PASSWORD)))); return; }
                if ("missingCreated".equals(mode)) { json(exchange, 201, Map.of("created", List.of(), "failed", List.of())); return; }
                if ("rejected".equals(mode)) { json(exchange, 201, Map.of("created", created, "failed", List.of(), "status", "rejected")); return; }
                json(exchange, 201, Map.of("created", created.size() == 1 ? created.getFirst() : created, "failed", List.of()));
            } else {
                protocolInvalid = true; json(exchange, 418, Map.of("error", "SYNTHETIC_FORBIDDEN_PATH"));
            }
        } catch (Exception ignored) {
            // An oversized response may be cancelled by the client before the synthetic server finishes writing.
            if (!"oversized".equals(mode)) protocolInvalid = true;
        } finally { exchange.close(); }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void denied(Checked action, String code) throws Exception {
        try { action.run(); throw new AssertionError("SYNTHETIC_HTTP_EXPECTED_REJECTION"); }
        catch (IllegalStateException rejected) {
            require(code.equals(rejected.getMessage())); require(!rejected.toString().contains(PASSWORD)); require(!rejected.toString().contains(TOKEN));
        }
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 0);
        PrintStream originalOut = System.out, originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", RuntimeVercelHttpHarness::handle); server.start();
        try (PrintStream quiet = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(quiet); System.setErr(quiet);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
            var origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new RuntimeVercelClient(http, origin, TOKEN);
            var changes = List.of(new RuntimeConfiguration.Change("DATABASE_PASSWORD", PASSWORD, true, COMMENT),
                new RuntimeConfiguration.Change("CP_BFF_ENABLED", "false", false, COMMENT));
            denied(() -> client.put(changes), "RUNTIME_VERCEL_CHANGES_INVALID"); require(calls.get() == 0);
            var entries = client.inspect(); require(entries.size() == 4);
            require(entries.get("DATABASE_PASSWORD").configValue() == null && entries.get("UNKNOWN_SECRET").configValue() == null);
            require(entries.get("DATABASE_URL").configValue() == null);
            require(entries.get("DATABASE_USER").configValue().startsWith("cp_runtime_"));
            require("1234567890123".equals(entries.get("DATABASE_PASSWORD").revision()));
            require(!entries.toString().contains(PASSWORD) && !entries.toString().contains("SYNTHETIC_UNRELATED_SECRET"));
            client.put(changes); client.put(List.of(changes.getFirst())); require(posts.get() == 2);
            int before = posts.get();
            denied(() -> client.put(List.of(new RuntimeConfiguration.Change("CP_BFF_ENABLED", "true", false, COMMENT))), "RUNTIME_VERCEL_ACTIVATION_FORBIDDEN");
            denied(() -> client.put(List.of(new RuntimeConfiguration.Change("CP_BFF_ENCRYPTION_KEY", PASSWORD, true, COMMENT))), "RUNTIME_VERCEL_CHANGES_INVALID");
            require(posts.get() == before);
            for (String broken : List.of("partial", "missingCreated", "rejected")) {
                mode = broken; denied(() -> client.put(changes), "RUNTIME_VERCEL_PARTIAL_WRITE");
            }
            for (String broken : List.of("forbidden", "redirect", "oversized", "duplicateJson", "foreignProject", "unsafeSecret", "duplicate")) {
                mode = broken; int previous = calls.get();
                String code = switch (broken) {
                    case "forbidden" -> "RUNTIME_VERCEL_HTTP_403";
                    case "redirect" -> "RUNTIME_VERCEL_HTTP_302";
                    case "oversized" -> "RUNTIME_VERCEL_UNAVAILABLE";
                    case "foreignProject" -> "RUNTIME_VERCEL_PROJECT_MISMATCH";
                    case "unsafeSecret" -> "RUNTIME_VERCEL_SECRET_TYPE_INVALID";
                    case "duplicate" -> "RUNTIME_VERCEL_AMBIGUOUS_ENV";
                    default -> "RUNTIME_VERCEL_RESPONSE_INVALID";
                };
                denied(client::inspect, code);
                if ("redirect".equals(broken)) require(calls.get() - previous == 2);
                denied(() -> client.put(changes), "RUNTIME_VERCEL_CHANGES_INVALID");
            }
            require(!client.toString().contains(TOKEN)); require(!protocolInvalid); require(captured.size() == 0);
            require(calls.get() == 20 && posts.get() == 5);
            mode="legacy";var legacy=client.inspect();require(legacy.size()==11 && publicReads.get()==7);
            for(var entry:LEGACY.entrySet())require(entry.getValue().equals(legacy.get(entry.getKey()).configValue()));
            require(legacy.get("DATABASE_PASSWORD").configValue()==null);
            mode="legacyForeign";denied(client::inspect,"RUNTIME_VERCEL_PUBLIC_CONFIG_INVALID");
            require(calls.get()==32 && publicReads.get()==8 && !protocolInvalid && captured.size()==0);
            var brokenClose=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(
                RuntimeVercelHttpHarness.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,arguments)-> {
                    if("close".equals(method.getName()))throw new java.sql.SQLException("SYNTHETIC_CLOSE_FAILURE");
                    throw new UnsupportedOperationException();
                });
            var readyOutcome=new RuntimeConfiguration.Outcome(true,true,false,"READY","a".repeat(64),"");
            require(RuntimeConfiguration.closeOwner(brokenClose,readyOutcome)==readyOutcome);
            require("UNKNOWN".equals(RuntimeConfiguration.closeOwner(brokenClose,null).state()));
        } finally {
            server.stop(0); System.setOut(originalOut); System.setErr(originalErr);
        }
        System.out.println("{\"passed\":true,\"httpRequests\":32,\"writeRequests\":5,\"legacyPublicConfigs\":7,\"secretReadbackBlocked\":true,\"fixedTargets\":true,\"partialWriteDenied\":true,\"closeOutcomePreserved\":true}");
    }
}
