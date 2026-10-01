package hearth.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** Public native OAuth client: no application secret is distributed in the mod. */
public final class GitHubLogin {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String clientId;

    public GitHubLogin(String clientId) {
        if (clientId == null || !clientId.matches("[A-Za-z0-9_]{10,100}")) {
            throw new IllegalArgumentException("Hearth GitHub sign-in is not configured");
        }
        this.clientId = clientId;
    }

    public record Device(String deviceCode, String userCode, long expiresAt, int intervalSeconds) {
        @Override public String toString() { return "Device[credentials=<redacted>]"; }
    }

    public record Tokens(String accessToken, String refreshToken, long expiresAt, String login) {
        public boolean expired() { return expiresAt > 0 && System.currentTimeMillis() >= expiresAt - 60_000; }
        @Override public String toString() { return "Tokens[login=" + login + ", credentials=<redacted>]"; }
    }

    public record Poll(Tokens tokens, int intervalSeconds) {}

    public Device start() throws IOException, InterruptedException {
        JsonObject result = post("/login/device/code", Map.of("client_id", clientId, "scope", "repo offline_access"));
        if (!"https://github.com/login/device".equals(string(result, "verification_uri"))) {
            throw new IOException("Unexpected GitHub verification address");
        }
        String userCode = string(result, "user_code");
        if (!userCode.matches("[A-Z0-9]{4}-[A-Z0-9]{4}")) throw new IOException("Invalid GitHub verification code");
        int interval = result.get("interval").getAsInt();
        int expiry = result.get("expires_in").getAsInt();
        if (interval < 1 || interval > 60 || expiry < 1 || expiry > 3600) throw new IOException("Invalid GitHub sign-in timeout");
        return new Device(string(result, "device_code"), userCode, System.currentTimeMillis() + expiry * 1000L, interval);
    }

    /** Caller schedules each poll after the previous interval, and can cancel by abandoning the device. */
    public Poll poll(Device device, int interval) throws IOException, InterruptedException {
        if (System.currentTimeMillis() >= device.expiresAt()) throw new IOException("GitHub sign-in expired; try again");
        JsonObject result = post("/login/oauth/access_token", Map.of("client_id", clientId,
                "device_code", device.deviceCode(), "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
        String error = string(result, "error");
        if ("authorization_pending".equals(error)) return new Poll(null, interval);
        if ("slow_down".equals(error)) return new Poll(null, interval + 5);
        if (error != null) throw new IOException("GitHub sign-in was declined or expired; try again");
        return new Poll(tokens(result), interval);
    }

    public Tokens refresh(Tokens previous) throws IOException, InterruptedException {
        if (previous.refreshToken() == null) throw new IOException("Sign in to GitHub again");
        return tokens(post("/login/oauth/access_token", Map.of("client_id", clientId,
                "refresh_token", previous.refreshToken(), "grant_type", "refresh_token")));
    }

    private Tokens tokens(JsonObject result) throws IOException, InterruptedException {
        String token = string(result, "access_token");
        String scope = string(result, "scope");
        if (token == null || token.isBlank() || scope == null || !Arrays.asList(scope.split("[, ]+")).contains("repo")) {
            throw new IOException("GitHub did not grant permission to create private worlds");
        }
        var request = HttpRequest.newBuilder(URI.create("https://api.github.com/user"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json").header("User-Agent", "Hearth-Minecraft").build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Could not verify the GitHub account");
        String login = string(parse(response.body()), "login");
        if (login == null || !login.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid GitHub account response");
        long expiry = result.has("expires_in") ? System.currentTimeMillis() + result.get("expires_in").getAsLong() * 1000L : 0;
        return new Tokens(token, string(result, "refresh_token"), expiry, login);
    }

    private static JsonObject post(String path, Map<String, String> fields) throws IOException, InterruptedException {
        String body = fields.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create("https://github.com" + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Hearth-Minecraft").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("GitHub sign-in request failed (HTTP " + response.statusCode() + ")");
        return parse(response.body());
    }

    private static JsonObject parse(String text) throws IOException {
        try { return JsonParser.parseString(text).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Invalid GitHub sign-in response"); }
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : null;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
