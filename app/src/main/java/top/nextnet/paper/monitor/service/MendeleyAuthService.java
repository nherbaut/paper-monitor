package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
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
import java.util.Map;
import java.util.Objects;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.runtime.StartupEvent;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.MendeleyLoginRequest;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.MendeleyLoginRequestRepository;

@ApplicationScoped
public class MendeleyAuthService {
    private static final Logger LOG = Logger.getLogger(MendeleyAuthService.class);
    private static final String AUTHORIZE_URL = "https://api.mendeley.com/oauth/authorize";
    private static final String TOKEN_URL = "https://api.mendeley.com/oauth/token";
    static final String PROFILE_MEDIA_TYPE = "application/vnd.mendeley-profiles.1+json";
    private final HttpClient httpClient;
    private final MendeleyLoginRequestRepository requests;
    private final AuthService authService;
    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final String baseUrl;
    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public MendeleyAuthService(MendeleyLoginRequestRepository requests, AuthService authService,
            @ConfigProperty(name = "paper-monitor.mendeley.client-id", defaultValue = "not-configured") String clientId,
            @ConfigProperty(name = "paper-monitor.mendeley.client-secret", defaultValue = "not-configured") String clientSecret,
            @ConfigProperty(name = "paper-monitor.mendeley.scopes", defaultValue = "all") String scopes,
            @ConfigProperty(name = "paper-monitor.mendeley.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "paper-monitor.base-url", defaultValue = "http://localhost:8080") String baseUrl) {
        this(HttpClient.newHttpClient(), requests, authService, clientId, clientSecret, scopes, enabled, baseUrl);
    }

    MendeleyAuthService(HttpClient client, MendeleyLoginRequestRepository requests, AuthService authService,
            String clientId, String clientSecret, String scopes, boolean enabled, String baseUrl) {
        this.httpClient = client;
        this.requests = requests;
        this.authService = authService;
        this.clientId = credential(clientId);
        this.clientSecret = credential(clientSecret);
        this.scopes = trim(scopes).isEmpty() ? "all" : trim(scopes);
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "http://localhost:8080" : baseUrl.replaceAll("/+$", "");
    }

    public boolean isEnabled() { return configurationIssue() == null; }
    public String requestedScopes() { return scopes; }
    public String callbackUrl() { return baseUrl + "/auth/mendeley/callback"; }

    public String configurationIssue() {
        if (!enabled) return "PAPER_MONITOR_MENDELEY_ENABLED is false";
        if (clientId.isBlank()) return "PAPER_MONITOR_MENDELEY_CLIENT_ID is missing";
        if (clientSecret.isBlank()) return "PAPER_MONITOR_MENDELEY_CLIENT_SECRET is missing";
        return null;
    }

    void logConfiguration(@Observes StartupEvent ignored) {
        String issue = configurationIssue();
        if (issue == null) {
            LOG.infof("Mendeley integration configured: enabled=true, clientIdConfigured=true, "
                    + "clientSecretConfigured=true, scopes=%s, callbackUrl=%s", scopes, callbackUrl());
        } else {
            LOG.warnf("Mendeley integration unavailable: %s; enabled=%s, clientIdConfigured=%s, "
                    + "clientSecretConfigured=%s, callbackUrl=%s", issue, enabled, !clientId.isBlank(),
                    !clientSecret.isBlank(), callbackUrl());
        }
    }

    @Transactional
    public URI start(AppUser user, String returnTo) throws IOException {
        if (!isEnabled()) throw new IOException("Mendeley integration is not configured: " + configurationIssue());
        if (user == null) throw new IOException("A signed-in user is required to connect Mendeley");
        requests.deleteOlderThan(Instant.now().minus(30, ChronoUnit.MINUTES));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        MendeleyLoginRequest request = new MendeleyLoginRequest();
        request.state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        request.user = user;
        request.returnTo = safeReturnTo(returnTo);
        requests.persist(request);
        return URI.create(AUTHORIZE_URL + "?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(callbackUrl()) + "&response_type=code&scope=" + encode(scopes)
                + "&state=" + encode(request.state));
    }

    @Transactional
    public String finish(AppUser user, String state, String code) throws IOException {
        if (user == null || state == null || code == null) throw new IOException("Invalid Mendeley callback");
        MendeleyLoginRequest login = requests.findByState(state)
                .orElseThrow(() -> new IOException(
                        "This Mendeley connection callback has already been used or expired; start the connection again"));
        if (!Objects.equals(login.user.id, user.id)) throw new IOException("Mendeley connection state belongs to another user");
        if (login.createdAt.isBefore(Instant.now().minus(30, ChronoUnit.MINUTES))) {
            requests.delete(login);
            throw new IOException("The Mendeley connection request expired; start again");
        }
        Map<String, Object> tokens = tokenRequest("grant_type=authorization_code&code=" + encode(code)
                + "&redirect_uri=" + encode(callbackUrl()));
        String refreshToken = value(tokens.get("refresh_token"));
        String accessToken = value(tokens.get("access_token"));
        if (refreshToken == null || accessToken == null) throw new IOException("Mendeley did not return reusable credentials");
        UserSettings settings = authService.ensureSettings(user);
        settings.mendeleyRefreshToken = refreshToken;
        settings.mendeleyAccessToken = accessToken;
        settings.mendeleyAccessTokenExpiresAt = expiry(tokens);
        settings.mendeleyGrantedScopes = value(tokens.get("scope"));
        settings.mendeleyConnectedAt = Instant.now();
        requests.delete(login);
        try {
            Map<String, Object> profile = getObject("https://api.mendeley.com/profiles/me", accessToken,
                    PROFILE_MEDIA_TYPE);
            settings.mendeleyProfileId = value(profile.get("id"));
            settings.mendeleyDisplayName = displayName(profile);
            settings.mendeleyLastError = null;
        } catch (IOException profileError) {
            settings.mendeleyProfileId = null;
            settings.mendeleyDisplayName = null;
            settings.mendeleyLastError = "Connected, but the Mendeley profile could not be loaded: "
                    + profileError.getMessage();
            LOG.warnf(profileError,
                    "Mendeley OAuth token exchange succeeded for user %s, but profile lookup failed; connection retained",
                    user.id);
        }
        return safeReturnTo(login.returnTo);
    }

    @Transactional
    public void disconnect(AppUser user) {
        UserSettings settings = authService.ensureSettings(user);
        settings.mendeleyRefreshToken = null;
        settings.mendeleyAccessToken = null;
        settings.mendeleyAccessTokenExpiresAt = null;
        settings.mendeleyGrantedScopes = null;
        settings.mendeleyConnectedAt = null;
        settings.mendeleyProfileId = null;
        settings.mendeleyDisplayName = null;
        settings.mendeleyLastError = null;
    }

    public String accessToken(UserSettings settings) throws IOException {
        if (settings == null || !settings.hasMendeleyConnection()) throw new IOException("Mendeley is not connected");
        if (settings.mendeleyAccessToken != null && settings.mendeleyAccessTokenExpiresAt != null
                && settings.mendeleyAccessTokenExpiresAt.isAfter(Instant.now().plus(1, ChronoUnit.MINUTES))) {
            return settings.mendeleyAccessToken;
        }
        Map<String, Object> tokens = tokenRequest("grant_type=refresh_token&refresh_token="
                + encode(settings.mendeleyRefreshToken) + "&redirect_uri=" + encode(callbackUrl()));
        String accessToken = value(tokens.get("access_token"));
        if (accessToken == null) throw new IOException("Mendeley token refresh did not return an access token");
        String replacement = value(tokens.get("refresh_token"));
        if (replacement != null) settings.mendeleyRefreshToken = replacement;
        settings.mendeleyAccessToken = accessToken;
        settings.mendeleyAccessTokenExpiresAt = expiry(tokens);
        return accessToken;
    }

    private Map<String, Object> tokenRequest(String body) throws IOException {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return sendObject(request, "Mendeley token request failed");
    }

    private Map<String, Object> getObject(String url, String token, String accept) throws IOException {
        return sendObject(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Accept", accept).GET().build(), "Mendeley profile request failed");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sendObject(HttpRequest request, String message) throws IOException {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException(message + " (HTTP " + response.statusCode() + "): " + response.body());
            }
            Object parsed = JsonCodec.parse(response.body());
            if (!(parsed instanceof Map<?, ?> map)) throw new IOException(message + ": invalid JSON response");
            return (Map<String, Object>) map;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(message + ": interrupted", e);
        }
    }

    private static Instant expiry(Map<String, Object> tokens) {
        try { return Instant.now().plusSeconds(Long.parseLong(String.valueOf(tokens.getOrDefault("expires_in", 3600)))); }
        catch (RuntimeException e) { return Instant.now().plusSeconds(3600); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static String credential(String value) { String normalized = trim(value); return "not-configured".equals(normalized) ? "" : normalized; }
    private static String value(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
    private static String safeReturnTo(String value) { return value != null && value.startsWith("/") && !value.startsWith("//") ? value : "/admin#mendeley"; }
    private static String displayName(Map<String, Object> profile) {
        String first = value(profile.get("first_name"));
        String last = value(profile.get("last_name"));
        String joined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return joined.isEmpty() ? value(profile.get("email")) : joined;
    }
}
