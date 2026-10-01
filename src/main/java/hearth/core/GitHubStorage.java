package hearth.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/** The creator's GitHub access stays local; guests receive separate repository deploy keys. */
public final class GitHubStorage {
    private static final Gson JSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String ownerToken;

    public GitHubStorage(String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank() || ownerToken.contains("\n") || ownerToken.contains("\r")) {
            throw new IllegalArgumentException("Sign in to GitHub to create or manage worlds");
        }
        this.ownerToken = ownerToken;
    }

    public record Repository(String owner, String name, String webUrl) {
        public Repository {
            if (owner == null || name == null || !owner.matches("[A-Za-z0-9_-]+")
                    || !name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid world repository");
        }

        public String sshUrl() {
            return "ssh://git@ssh.github.com:443/" + owner + "/" + name + ".git";
        }

        String apiPath() {
            return "/repos/" + owner + "/" + name;
        }
    }

    public Repository createRepository(String worldName) throws IOException, InterruptedException {
        return createRepository(worldName, false);
    }

    public Repository createRepository(String worldName, boolean rulesOnly) throws IOException, InterruptedException {
        String name = (rulesOnly ? "hearth-rules-" : "hearth-world-") + UUID.randomUUID();
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("private", true);
        body.addProperty("auto_init", false);
        body.addProperty("description", "Hearth shared Minecraft world: " + worldName);
        JsonObject result = request("POST", "/user/repos", body, 201);
        if (!result.get("private").getAsBoolean()) throw new IOException("GitHub did not create a private world repository");
        return new Repository(result.getAsJsonObject("owner").get("login").getAsString(),
                result.get("name").getAsString(), result.get("html_url").getAsString());
    }

    public GitSsh.Access createAccess(Repository repository, String participantName) throws Exception {
        return createAccess(repository, participantName, false);
    }

    public GitSsh.Access createAccess(Repository repository, String participantName, boolean readOnly) throws Exception {
        GitSsh.Access key = GitSsh.generate();
        JsonObject body = new JsonObject();
        body.addProperty("title", "Hearth: " + participantName);
        body.addProperty("key", key.authorizedKey());
        body.addProperty("read_only", readOnly);
        JsonObject result = request("POST", repository.apiPath() + "/keys", body, 201);
        return new GitSsh.Access(key.privateKey(), key.publicKey(), result.get("id").getAsLong());
    }

    public void revokeAccess(Repository repository, long deployKeyId) throws IOException, InterruptedException {
        if (deployKeyId <= 0) throw new IllegalArgumentException("Invalid world access identifier");
        request("DELETE", repository.apiPath() + "/keys/" + deployKeyId, null, 204);
    }

    public long restoreAccess(Repository repository, String publicKey, String name, boolean readOnly) throws Exception {
        String authorized = GitSsh.authorizedKey(publicKey);
        for (int page = 1; page <= 11; page++) {
            var keys = request("GET", repository.apiPath() + "/keys?per_page=100&page=" + page, null, 200).getAsJsonArray("keys");
            for (var existing : keys) {
                var key = existing.getAsJsonObject();
                if (key.get("key").getAsString().equals(authorized)) {
                    if (key.get("read_only").getAsBoolean() != readOnly) throw new IOException("The restored key has unexpected permissions");
                    return key.get("id").getAsLong();
                }
            }
            if (keys.size() < 100) break;
            if (page == 11) throw new IOException("Too many repository keys to safely restore access");
        }
        JsonObject body = new JsonObject();
        body.addProperty("title", "Hearth: " + name);
        body.addProperty("key", authorized);
        body.addProperty("read_only", readOnly);
        return request("POST", repository.apiPath() + "/keys", body, 201).get("id").getAsLong();
    }

    private JsonObject request(String method, String path, JsonObject body, int expected)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://api.github.com" + path))
                .timeout(Duration.ofSeconds(40))
                .header("Authorization", "Bearer " + ownerToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "Hearth-Minecraft");
        if (body != null) builder.header("Content-Type", "application/json");
        var response = HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
        if (method.equals("DELETE") && expected == 204 && response.statusCode() == 404) return new JsonObject();
        if (response.statusCode() != expected) {
            throw new IOException("GitHub storage request failed (HTTP " + response.statusCode()
                    + "). Check the creator's sign-in and repository permissions.");
        }
        if (response.body().isBlank()) return new JsonObject();
        try {
            var parsed = JsonParser.parseString(response.body());
            if (parsed.isJsonArray()) { var object = new JsonObject(); object.add("keys", parsed); return object; }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("GitHub returned an invalid storage response", e);
        }
    }
}
