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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.GoogleDriveLoginRequest;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.GoogleDriveLoginRequestRepository;

@ApplicationScoped
public class GoogleDriveAuthService {

    private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

    private final HttpClient httpClient;
    private final GoogleDriveLoginRequestRepository loginRequestRepository;
    private final AuthService authService;
    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final boolean enabled;
    private final String baseUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public GoogleDriveAuthService(
            GoogleDriveLoginRequestRepository loginRequestRepository,
            AuthService authService,
            @ConfigProperty(name = "paper-monitor.auth.google.client-id", defaultValue = "") String clientId,
            @ConfigProperty(name = "paper-monitor.auth.google.client-secret", defaultValue = "") String clientSecret,
            @ConfigProperty(name = "paper-monitor.auth.google.scopes", defaultValue = "openid email profile https://www.googleapis.com/auth/drive") String scopes,
            @ConfigProperty(name = "paper-monitor.auth.google.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "paper-monitor.base-url", defaultValue = "http://localhost:8080") String baseUrl
    ) {
        this(HttpClient.newHttpClient(), loginRequestRepository, authService, clientId, clientSecret, scopes, enabled, baseUrl);
    }

    GoogleDriveAuthService(
            HttpClient httpClient,
            GoogleDriveLoginRequestRepository loginRequestRepository,
            AuthService authService,
            String clientId,
            String clientSecret,
            String scopes,
            boolean enabled,
            String baseUrl
    ) {
        this.httpClient = httpClient;
        this.loginRequestRepository = loginRequestRepository;
        this.authService = authService;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.scopes = scopes == null || scopes.isBlank()
                ? "openid email profile https://www.googleapis.com/auth/drive"
                : scopes.trim();
        this.enabled = enabled;
        this.baseUrl = trimTrailingSlash(baseUrl, "http://localhost:8080");
    }

    public boolean isEnabled() {
        return enabled && !clientId.isBlank() && !clientSecret.isBlank();
    }

    @Transactional
    public URI startConnection(String returnTo) throws IOException {
        if (!isEnabled()) {
            throw new IOException("Google Drive sync is not configured");
        }
        loginRequestRepository.deleteOlderThan(Instant.now().minus(30, ChronoUnit.MINUTES));
        GoogleDriveLoginRequest request = new GoogleDriveLoginRequest();
        request.state = randomToken(24);
        request.returnTo = sanitizeReturnTo(returnTo);
        loginRequestRepository.persist(request);
        String authorizationUrl = GOOGLE_AUTH_URL
                + "?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(callbackUrl())
                + "&response_type=code"
                + "&scope=" + encode(scopes)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + encode(request.state);
        return URI.create(authorizationUrl);
    }

    @Transactional
    public GoogleDriveConnectionResult finishConnection(AppUser user, String state, String code) throws IOException {
        if (user == null) {
            throw new IOException("A signed-in user is required to connect Google Drive");
        }
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            throw new IOException("Google Drive callback is missing the authorization code or state");
        }
        GoogleDriveLoginRequest loginRequest = loginRequestRepository.findByState(state)
                .orElseThrow(() -> new IOException("Unknown Google Drive connection state"));
        loginRequestRepository.delete(loginRequest);

        Map<String, Object> tokenResponse = exchangeCode(code);
        String refreshToken = stringValue(tokenResponse.get("refresh_token"));
        String accessToken = stringValue(tokenResponse.get("access_token"));
        if (refreshToken == null) {
            throw new IOException("Google did not return a refresh token. Disconnect and grant offline access again.");
        }
        if (accessToken == null) {
            throw new IOException("Google token response did not include an access token");
        }

        Map<String, Object> profile = userInfo(accessToken);
        UserSettings settings = authService.ensureSettings(user);
        settings.googleDriveRefreshToken = refreshToken;
        settings.googleDriveConnectedAt = Instant.now();
        settings.googleDriveEmail = stringValue(profile.get("email"));
        settings.googleDriveDisplayName = firstNonBlank(stringValue(profile.get("name")), settings.googleDriveEmail);
        settings.googleDriveLastSyncError = null;
        return new GoogleDriveConnectionResult(user, sanitizeReturnTo(loginRequest.returnTo));
    }

    @Transactional
    public void disconnect(AppUser user) {
        if (user == null) {
            return;
        }
        UserSettings settings = authService.ensureSettings(user);
        settings.googleDriveRefreshToken = null;
        settings.googleDriveConnectedAt = null;
        settings.googleDriveEmail = null;
        settings.googleDriveDisplayName = null;
        settings.googleDriveSyncEnabled = false;
        settings.googleDriveLastSyncError = null;
    }

    public String accessToken(UserSettings settings) throws IOException {
        if (settings == null || settings.googleDriveRefreshToken == null || settings.googleDriveRefreshToken.isBlank()) {
            throw new IOException("Google Drive is not connected");
        }
        String body = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&refresh_token=" + encode(settings.googleDriveRefreshToken)
                + "&grant_type=refresh_token";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GOOGLE_TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        Map<String, Object> response = sendObject(request, "Google Drive token refresh failed");
        String accessToken = stringValue(response.get("access_token"));
        if (accessToken == null) {
            throw new IOException("Google token refresh response did not include an access token");
        }
        return accessToken;
    }

    private Map<String, Object> exchangeCode(String code) throws IOException {
        String body = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&code=" + encode(code)
                + "&grant_type=authorization_code"
                + "&redirect_uri=" + encode(callbackUrl());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GOOGLE_TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return sendObject(request, "Google Drive token exchange failed");
    }

    private Map<String, Object> userInfo(String accessToken) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GOOGLE_USERINFO_URL))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return sendObject(request, "Google user profile request failed");
    }

    private Map<String, Object> sendObject(HttpRequest request, String defaultMessage) throws IOException {
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
        Object parsed = JsonCodec.parse(body);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException(defaultMessage + ": invalid JSON payload");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private String callbackUrl() {
        return baseUrl + "/auth/google-drive/callback";
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String trimTrailingSlash(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private String sanitizeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank() || !returnTo.startsWith("/")) {
            return "/admin#google-drive";
        }
        return returnTo;
    }

    public record GoogleDriveConnectionResult(AppUser user, String returnTo) {
    }
}
