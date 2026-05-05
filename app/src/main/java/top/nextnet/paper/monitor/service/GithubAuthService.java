package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.GithubLoginRequest;
import top.nextnet.paper.monitor.repo.GithubLoginRequestRepository;

@ApplicationScoped
public class GithubAuthService {

    private static final String DEFAULT_WEB_BASE_URL = "https://github.com";
    private static final String DEFAULT_API_BASE_URL = "https://api.github.com";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final GithubLoginRequestRepository githubLoginRequestRepository;
    private final AuthService authService;
    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final boolean enabled;
    private final String baseUrl;
    private final String githubWebBaseUrl;
    private final String githubApiBaseUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public GithubAuthService(
            GithubLoginRequestRepository githubLoginRequestRepository,
            AuthService authService,
            @ConfigProperty(name = "paper-monitor.auth.github.client-id", defaultValue = "") String clientId,
            @ConfigProperty(name = "paper-monitor.auth.github.client-secret", defaultValue = "") String clientSecret,
            @ConfigProperty(name = "paper-monitor.auth.github.scopes", defaultValue = "read:user user:email") String scopes,
            @ConfigProperty(name = "paper-monitor.auth.github.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "paper-monitor.auth.github.web-base-url", defaultValue = DEFAULT_WEB_BASE_URL) String githubWebBaseUrl,
            @ConfigProperty(name = "paper-monitor.auth.github.api-base-url", defaultValue = DEFAULT_API_BASE_URL) String githubApiBaseUrl,
            @ConfigProperty(name = "paper-monitor.base-url", defaultValue = "http://localhost:8080") String baseUrl
    ) {
        this.githubLoginRequestRepository = githubLoginRequestRepository;
        this.authService = authService;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.scopes = scopes == null ? "read:user user:email" : scopes.trim();
        this.enabled = enabled;
        this.githubWebBaseUrl = trimTrailingSlash(githubWebBaseUrl, DEFAULT_WEB_BASE_URL);
        this.githubApiBaseUrl = trimTrailingSlash(githubApiBaseUrl, DEFAULT_API_BASE_URL);
        this.baseUrl = trimTrailingSlash(baseUrl, "http://localhost:8080");
    }

    public boolean isEnabled() {
        return enabled && !clientId.isBlank() && !clientSecret.isBlank();
    }

    @Transactional
    public URI startLogin(String returnTo) throws IOException {
        if (!isEnabled()) {
            throw new IOException("GitHub login is not configured");
        }
        githubLoginRequestRepository.deleteOlderThan(Instant.now().minus(30, ChronoUnit.MINUTES));
        GithubLoginRequest loginRequest = new GithubLoginRequest();
        loginRequest.state = randomToken(24);
        loginRequest.returnTo = sanitizeReturnTo(returnTo);
        githubLoginRequestRepository.persist(loginRequest);
        String authorizationUrl = githubWebBaseUrl
                + "/login/oauth/authorize?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(callbackUrl())
                + "&scope=" + encode(scopes)
                + "&state=" + encode(loginRequest.state);
        return URI.create(authorizationUrl);
    }

    @Transactional
    public GithubLoginResult finishLogin(String state, String code) throws IOException {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            throw new IOException("GitHub callback is missing the authorization code or state");
        }
        GithubLoginRequest loginRequest = githubLoginRequestRepository.findByState(state)
                .orElseThrow(() -> new IOException("Unknown GitHub login state"));
        githubLoginRequestRepository.delete(loginRequest);

        Map<String, Object> tokenResponse = exchangeCode(code);
        String accessToken = stringValue(tokenResponse.get("access_token"));
        if (accessToken == null) {
            throw new IOException("GitHub token response did not include an access token");
        }

        Map<String, Object> userInfo = userInfo(accessToken);
        String githubUserId = stringValue(userInfo.get("id"));
        if (githubUserId == null || githubUserId.isBlank()) {
            throw new IOException("GitHub user profile did not include an id");
        }
        VerifiedEmail verifiedEmail = selectVerifiedEmail(userEmails(accessToken), stringValue(userInfo.get("email")));
        AppUser user = authService.upsertGithubUser(
                githubUserId,
                stringValue(userInfo.get("login")),
                firstNonBlank(stringValue(userInfo.get("name")), stringValue(userInfo.get("login"))),
                verifiedEmail.email());
        authService.storeGithubAccessToken(user, accessToken);
        return new GithubLoginResult(user, sanitizeReturnTo(loginRequest.returnTo));
    }

    private Map<String, Object> exchangeCode(String code) throws IOException {
        String body = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(callbackUrl());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubWebBaseUrl + "/login/oauth/access_token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return sendObject(request, "GitHub token exchange failed");
    }

    private Map<String, Object> userInfo(String accessToken) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiBaseUrl + "/user"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        return sendObject(request, "GitHub user profile request failed");
    }

    private List<Map<String, Object>> userEmails(String accessToken) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(githubApiBaseUrl + "/user/emails"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        return sendArray(request, "GitHub email request failed");
    }

    private VerifiedEmail selectVerifiedEmail(List<Map<String, Object>> emails, String profileEmail) {
        for (Map<String, Object> item : emails) {
            if (boolValue(item.get("primary")) && boolValue(item.get("verified"))) {
                return new VerifiedEmail(normalizeEmail(stringValue(item.get("email"))));
            }
        }
        for (Map<String, Object> item : emails) {
            if (boolValue(item.get("verified"))) {
                return new VerifiedEmail(normalizeEmail(stringValue(item.get("email"))));
            }
        }
        return new VerifiedEmail(normalizeEmail(profileEmail));
    }

    private Map<String, Object> sendObject(HttpRequest request, String defaultMessage) throws IOException {
        Object parsed = sendJson(request, defaultMessage);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException(defaultMessage + ": invalid JSON payload");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private List<Map<String, Object>> sendArray(HttpRequest request, String defaultMessage) throws IOException {
        Object parsed = sendJson(request, defaultMessage);
        if (!(parsed instanceof Iterable<?> iterable)) {
            throw new IOException(defaultMessage + ": invalid JSON payload");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                items.add(normalized);
            }
        }
        return items;
    }

    private Object sendJson(HttpRequest request, String defaultMessage) throws IOException {
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(defaultMessage, e);
        }
        String body = new String(response.body(), StandardCharsets.UTF_8).trim();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(body.isEmpty() ? defaultMessage + " (HTTP " + response.statusCode() + ")" : body);
        }
        return JsonCodec.parse(body);
    }

    private String callbackUrl() {
        return baseUrl + "/auth/github/callback";
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase();
    }

    private String trimTrailingSlash(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private String sanitizeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank() || !returnTo.startsWith("/")) {
            return "/";
        }
        return returnTo;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record VerifiedEmail(String email) {
    }

    public record GithubLoginResult(AppUser user, String returnTo) {
    }
}
