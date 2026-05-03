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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final UserSettingsRepository userSettingsRepository;
    private final String githubApiBaseUrl;
    private final String githubWebBaseUrl;

    public GithubRepositoryService(
            UserSettingsRepository userSettingsRepository,
            @ConfigProperty(name = "paper-monitor.auth.github.api-base-url", defaultValue = DEFAULT_API_BASE_URL) String githubApiBaseUrl,
            @ConfigProperty(name = "paper-monitor.auth.github.web-base-url", defaultValue = DEFAULT_WEB_BASE_URL) String githubWebBaseUrl
    ) {
        this.userSettingsRepository = userSettingsRepository;
        this.githubApiBaseUrl = trimTrailingSlash(githubApiBaseUrl, DEFAULT_API_BASE_URL);
        this.githubWebBaseUrl = trimTrailingSlash(githubWebBaseUrl, DEFAULT_WEB_BASE_URL);
    }

    @Transactional
    public void createRepositoryForLogicalFeed(AppUser actor, LogicalFeed logicalFeed, String repoName, boolean privateRepo, String branch) throws IOException {
        if (actor == null) {
            throw new IllegalArgumentException("A signed-in user is required");
        }
        if (logicalFeed == null) {
            throw new IllegalArgumentException("A logical feed is required");
        }
        if (logicalFeed.hasGithubRepo()) {
            throw new IllegalArgumentException("This paper feed already has a GitHub repository configured");
        }
        if (!actor.hasGithubLogin()) {
            throw new IllegalArgumentException("Sign in with GitHub first so Paper Monitor can create repositories on your behalf");
        }
        UserSettings settings = requireGithubSettings(actor);
        String normalizedRepoName = normalizeRepositoryName(repoName);
        String normalizedBranch = normalizeBranch(branch);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", normalizedRepoName);
        payload.put("description", "Paper Monitor mirror for " + logicalFeed.name);
        payload.put("private", privateRepo);
        payload.put("auto_init", false);

        Map<String, Object> response = postJson(
                settings.githubAccessToken,
                githubApiBaseUrl + "/user/repos",
                JsonCodec.stringify(payload),
                "GitHub repository creation failed");

        String owner = nestedString(response, "owner", "login");
        String createdRepoName = stringValue(response.get("name"));
        if (owner == null || createdRepoName == null) {
            throw new IOException("GitHub repository creation response was missing owner or repository name");
        }

        logicalFeed.githubRepoOwner = owner;
        logicalFeed.githubRepoName = createdRepoName;
        logicalFeed.githubRepoBranch = normalizedBranch;
        logicalFeed.githubSyncUser = actor;
        logicalFeed.githubLastPushedCommit = null;
        logicalFeed.githubLastPushedAt = null;
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
        AppUser syncUser = logicalFeed.githubSyncUser;
        if (syncUser == null) {
            throw new IOException("The GitHub sync user is missing for this paper feed");
        }
        UserSettings settings = requireGithubSettings(syncUser);
        push(repoPath, settings.githubAccessToken, remoteUrl(logicalFeed), normalizeBranch(logicalFeed.githubRepoBranch));
        logicalFeed.githubLastPushedCommit = headCommit;
        logicalFeed.githubLastPushedAt = Instant.now();
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

    private Map<String, Object> postJson(String accessToken, String url, String body, String defaultMessage) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
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
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    private UserSettings requireGithubSettings(AppUser user) {
        UserSettings settings = userSettingsRepository.findByUser(user).orElse(null);
        if (settings == null || !settings.hasGithubAccessToken()) {
            throw new IllegalArgumentException("Sign in with GitHub first so Paper Monitor can access your repositories");
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

    private String normalizeRequired(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
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

    private String normalizeRepositoryName(String value) {
        String normalized = normalizeRequired(value, "Repository name is required")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Repository name must contain at least one letter or digit");
        }
        return normalized;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }

    private String trimTrailingSlash(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
