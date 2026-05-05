package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.UserSettingsRepository;

@ApplicationScoped
public class GithubRepositoryService {

    private static final String DEFAULT_API_BASE_URL = "https://api.github.com";
    private static final String DEFAULT_WEB_BASE_URL = "https://github.com";
    private static final String DEFAULT_APP_URL = "https://github.com/apps/paper-monitor";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final UserSettingsRepository userSettingsRepository;
    private final String githubApiBaseUrl;
    private final String githubWebBaseUrl;
    private final String githubAppUrl;
    private final String githubAppId;
    private final String githubPrivateKeyPem;

    public GithubRepositoryService(
            UserSettingsRepository userSettingsRepository,
            @ConfigProperty(name = "paper-monitor.auth.github.api-base-url", defaultValue = DEFAULT_API_BASE_URL) String githubApiBaseUrl,
            @ConfigProperty(name = "paper-monitor.auth.github.web-base-url", defaultValue = DEFAULT_WEB_BASE_URL) String githubWebBaseUrl,
            @ConfigProperty(name = "paper-monitor.auth.github.app-url", defaultValue = DEFAULT_APP_URL) String githubAppUrl,
            @ConfigProperty(name = "paper-monitor.auth.github.app-id", defaultValue = "") String githubAppId,
            @ConfigProperty(name = "paper-monitor.auth.github.private-key", defaultValue = "") String githubPrivateKeyPem
    ) {
        this.userSettingsRepository = userSettingsRepository;
        this.githubApiBaseUrl = trimTrailingSlash(githubApiBaseUrl, DEFAULT_API_BASE_URL);
        this.githubWebBaseUrl = trimTrailingSlash(githubWebBaseUrl, DEFAULT_WEB_BASE_URL);
        this.githubAppUrl = githubAppUrl == null || githubAppUrl.isBlank() ? DEFAULT_APP_URL : githubAppUrl.trim();
        this.githubAppId = githubAppId == null ? "" : githubAppId.trim();
        this.githubPrivateKeyPem = githubPrivateKeyPem == null ? "" : githubPrivateKeyPem.trim();
    }

    public boolean isInstallationAuthConfigured() {
        return !githubAppId.isBlank() && !githubPrivateKeyPem.isBlank();
    }

    public String appInstallUrl() {
        return githubAppUrl.endsWith("/") ? githubAppUrl + "installations/new" : githubAppUrl + "/installations/new";
    }

    @Transactional
    public List<GithubRepositoryChoice> accessibleRepositories(AppUser actor) throws IOException {
        if (actor == null || !actor.hasGithubLogin()) {
            return List.of();
        }
        UserSettings settings = requireGithubSettings(actor);
        if (!isInstallationAuthConfigured()) {
            throw new IOException("GitHub App installation auth is not configured on the server");
        }
        List<Map<String, Object>> installations = userInstallations(settings.githubAccessToken);
        Map<String, GithubRepositoryChoice> choices = new LinkedHashMap<>();
        for (Map<String, Object> installation : installations) {
            Long installationId = longValue(installation.get("id"));
            if (installationId == null) {
                continue;
            }
            String accountLogin = nestedString(installation, "account", "login");
            String accountType = nestedString(installation, "account", "type");
            InstallationToken token = createInstallationAccessToken(installationId, null);
            for (Map<String, Object> repo : installationRepositories(token.token())) {
                Long repoId = longValue(repo.get("id"));
                String owner = nestedString(repo, "owner", "login");
                String name = stringValue(repo.get("name"));
                String fullName = stringValue(repo.get("full_name"));
                String htmlUrl = stringValue(repo.get("html_url"));
                if (repoId == null || owner == null || name == null || fullName == null) {
                    continue;
                }
                choices.put(fullName.toLowerCase(Locale.ROOT), new GithubRepositoryChoice(
                        installationId,
                        repoId,
                        owner,
                        name,
                        fullName,
                        htmlUrl,
                        accountLogin == null ? owner : accountLogin,
                        accountType == null ? "User" : accountType));
            }
        }
        return choices.values().stream()
                .sorted(Comparator.comparing(GithubRepositoryChoice::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public void connectRepositoryToLogicalFeed(AppUser actor, LogicalFeed logicalFeed, String repositorySelection, String branch) throws IOException {
        if (actor == null) {
            throw new IllegalArgumentException("A signed-in user is required");
        }
        if (logicalFeed == null) {
            throw new IllegalArgumentException("A logical feed is required");
        }
        GithubRepositoryChoice choice = accessibleRepositories(actor).stream()
                .filter((item) -> item.selectionValue().equals(repositorySelection))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Select a GitHub repository that is accessible through your app installation"));
        logicalFeed.githubInstallationId = choice.installationId();
        logicalFeed.githubRepoId = choice.repoId();
        logicalFeed.githubRepoOwner = choice.owner();
        logicalFeed.githubRepoName = choice.name();
        logicalFeed.githubRepoBranch = normalizeBranch(branch);
        logicalFeed.githubSyncUser = actor;
        logicalFeed.githubLastPushedCommit = null;
        logicalFeed.githubLastPushedAt = null;
        logicalFeed.githubSyncError = null;
        logicalFeed.githubRepoUrl = choice.htmlUrl();
    }

    @Transactional
    public void pushIfConfigured(LogicalFeed logicalFeed, Path repoPath, String headCommit) throws IOException {
        if (logicalFeed == null || !logicalFeed.hasGithubRepo()) {
            return;
        }
        logicalFeed.githubSyncError = null;
        logicalFeed.githubRepoUrl = repoWebUrl(logicalFeed);
        if (headCommit == null || headCommit.isBlank() || headCommit.equals(logicalFeed.githubLastPushedCommit)) {
            return;
        }
        if (!isInstallationAuthConfigured()) {
            throw new IOException("GitHub App installation auth is not configured on the server");
        }
        if (logicalFeed.githubInstallationId == null) {
            throw new IOException("The GitHub installation is not configured for this paper feed");
        }
        InstallationToken installationToken = createInstallationAccessToken(logicalFeed.githubInstallationId, logicalFeed.githubRepoId);
        push(repoPath, installationToken.token(), remoteUrl(logicalFeed), normalizeBranch(logicalFeed.githubRepoBranch));
        logicalFeed.githubLastPushedCommit = headCommit;
        logicalFeed.githubLastPushedAt = Instant.now();
    }

    private List<Map<String, Object>> userInstallations(String accessToken) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiBaseUrl + "/user/installations?per_page=100"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        Map<String, Object> payload = sendObject(request, "Failed to list GitHub app installations");
        Object installations = payload.get("installations");
        if (!(installations instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                items.add(normalizeMap(map));
            }
        }
        return items;
    }

    private InstallationToken createInstallationAccessToken(Long installationId, Long repoId) throws IOException {
        if (installationId == null) {
            throw new IOException("GitHub installation id is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (repoId != null) {
            body.put("repository_ids", List.of(repoId));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiBaseUrl + "/app/installations/" + installationId + "/access_tokens"))
                .header("Authorization", "Bearer " + appJwt())
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.stringify(body)))
                .build();
        Map<String, Object> response = sendObject(request, "Failed to create a GitHub installation access token");
        String token = stringValue(response.get("token"));
        if (token == null) {
            throw new IOException("GitHub installation token response did not include a token");
        }
        return new InstallationToken(token);
    }

    private List<Map<String, Object>> installationRepositories(String installationToken) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiBaseUrl + "/installation/repositories?per_page=100"))
                .header("Authorization", "Bearer " + installationToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        Map<String, Object> payload = sendObject(request, "Failed to list repositories for the GitHub app installation");
        Object repositories = payload.get("repositories");
        if (!(repositories instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                items.add(normalizeMap(map));
            }
        }
        return items;
    }

    private void push(Path repoPath, String accessToken, String remoteUrl, String branch) throws IOException {
        String authHeader = "AUTHORIZATION: basic " + Base64.getEncoder()
                .encodeToString(("x-access-token:" + accessToken).getBytes(StandardCharsets.UTF_8));
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("http.extraheader=" + authHeader);
        command.add("push");
        command.add("--force");
        command.add(remoteUrl);
        command.add("HEAD:refs/heads/" + branch);
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(repoPath.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new IOException("Git is required on the server to push GitHub mirrors", e);
        }
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("GitHub push failed: " + output.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("GitHub push interrupted", e);
        }
    }

    private String appJwt() throws IOException {
        String header = base64Url(JsonCodec.stringify(Map.of("alg", "RS256", "typ", "JWT")));
        long now = Instant.now().getEpochSecond();
        String payload = base64Url(JsonCodec.stringify(Map.of(
                "iat", now - 60,
                "exp", now + 540,
                "iss", githubAppId)));
        String signingInput = header + "." + payload;
        byte[] signature = sign(signingInput.getBytes(StandardCharsets.UTF_8), parsePrivateKey(githubPrivateKeyPem));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private byte[] sign(byte[] data, PrivateKey privateKey) throws IOException {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new IOException("Failed to sign the GitHub App JWT", e);
        }
    }

    private PrivateKey parsePrivateKey(String pem) throws IOException {
        String normalized = pem == null ? "" : pem.replace("\\n", "\n").trim();
        if (normalized.isBlank()) {
            throw new IOException("GitHub App private key is not configured");
        }
        try {
            byte[] keyBytes;
            if (normalized.contains("BEGIN RSA PRIVATE KEY")) {
                keyBytes = wrapPkcs1InPkcs8(decodePem(normalized, "RSA PRIVATE KEY"));
            } else {
                keyBytes = decodePem(normalized, "PRIVATE KEY");
            }
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IOException("Failed to parse the GitHub App private key", e);
        }
    }

    private byte[] decodePem(String pem, String label) {
        String content = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(content);
    }

    private byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] rsaAlgorithmIdentifier = new byte[] {
                0x30, 0x0d,
                0x06, 0x09,
                0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        byte[] version = new byte[] {0x02, 0x01, 0x00};
        byte[] privateKeyOctetString = encodeDer(0x04, pkcs1);
        byte[] sequenceContent = concat(version, rsaAlgorithmIdentifier, privateKeyOctetString);
        return encodeDer(0x30, sequenceContent);
    }

    private byte[] encodeDer(int tag, byte[] content) {
        byte[] length = encodeDerLength(content.length);
        byte[] encoded = new byte[1 + length.length + content.length];
        encoded[0] = (byte) tag;
        System.arraycopy(length, 0, encoded, 1, length.length);
        System.arraycopy(content, 0, encoded, 1 + length.length, content.length);
        return encoded;
    }

    private byte[] encodeDerLength(int length) {
        if (length < 128) {
            return new byte[] {(byte) length};
        }
        int size = 0;
        int value = length;
        while (value > 0) {
            size += 1;
            value >>= 8;
        }
        byte[] encoded = new byte[1 + size];
        encoded[0] = (byte) (0x80 | size);
        for (int index = size; index > 0; index--) {
            encoded[index] = (byte) (length & 0xff);
            length >>= 8;
        }
        return encoded;
    }

    private byte[] concat(byte[]... chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    private String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> sendObject(HttpRequest request, String defaultMessage) throws IOException {
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(defaultMessage, e);
        }
        String responseBody = new String(response.body(), StandardCharsets.UTF_8).trim();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(responseBody.isEmpty() ? defaultMessage + " (HTTP " + response.statusCode() + ")" : responseBody);
        }
        Object parsed = JsonCodec.parse(responseBody);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException(defaultMessage + ": invalid JSON payload");
        }
        return normalizeMap(map);
    }

    private UserSettings requireGithubSettings(AppUser user) {
        UserSettings settings = userSettingsRepository.findByUser(user).orElse(null);
        if (settings == null || !settings.hasGithubAccessToken()) {
            throw new IllegalArgumentException("Sign in with GitHub first so Paper Monitor can inspect your GitHub App installations");
        }
        return settings;
    }

    private String remoteUrl(LogicalFeed logicalFeed) {
        return githubWebBaseUrl + "/" + logicalFeed.githubRepoOwner + "/" + logicalFeed.githubRepoName + ".git";
    }

    public String repoWebUrl(LogicalFeed logicalFeed) {
        if (logicalFeed == null || !logicalFeed.hasGithubRepo()) {
            return null;
        }
        return githubWebBaseUrl + "/" + logicalFeed.githubRepoOwner + "/" + logicalFeed.githubRepoName;
    }

    private String nestedString(Map<String, Object> payload, String objectKey, String fieldKey) {
        Object nested = payload.get(objectKey);
        if (!(nested instanceof Map<?, ?> map)) {
            return null;
        }
        return stringValue(map.get(fieldKey));
    }

    private Map<String, Object> normalizeMap(Map<?, ?> map) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeBranch(String value) {
        String normalized = normalize(value);
        return normalized == null ? "main" : normalized;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String trimTrailingSlash(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private record InstallationToken(String token) {
    }

    public record GithubRepositoryChoice(
            Long installationId,
            Long repoId,
            String owner,
            String name,
            String fullName,
            String htmlUrl,
            String installationAccountLogin,
            String installationAccountType
    ) {
        public String selectionValue() {
            return installationId + ":" + repoId;
        }
    }
}
