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
import java.security.MessageDigest;
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
import top.nextnet.paper.monitor.model.OidcLoginRequest;
import top.nextnet.paper.monitor.repo.OidcLoginRequestRepository;

@ApplicationScoped
public class OidcService {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final OidcLoginRequestRepository oidcLoginRequestRepository;
    private final AuthService authService;
    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final boolean enabled;
    private final String baseUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public OidcService(
            OidcLoginRequestRepository oidcLoginRequestRepository,
            AuthService authService,
            @ConfigProperty(name = "paper-monitor.auth.oidc.issuer", defaultValue = "https://auth.nextnet.top") String issuer,
            @ConfigProperty(name = "paper-monitor.auth.oidc.client-id", defaultValue = "") String clientId,
            @ConfigProperty(name = "paper-monitor.auth.oidc.client-secret", defaultValue = "") String clientSecret,
            @ConfigProperty(name = "paper-monitor.auth.oidc.scopes", defaultValue = "openid profile email groups") String scopes,
            @ConfigProperty(name = "paper-monitor.auth.oidc.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "paper-monitor.base-url", defaultValue = "http://localhost:8080") String baseUrl
    ) {
        this.oidcLoginRequestRepository = oidcLoginRequestRepository;
        this.authService = authService;
        this.issuer = trimTrailingSlash(issuer);
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.scopes = scopes == null ? "openid profile email groups" : scopes.trim();
        this.enabled = enabled;
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    public boolean isEnabled() {
        return enabled && !clientId.isBlank() && !clientSecret.isBlank();
    }

    @Transactional
    public URI startLogin(String returnTo) throws IOException {
        if (!isEnabled()) {
            throw new IOException("OIDC login is not configured");
        }
        oidcLoginRequestRepository.deleteOlderThan(Instant.now().minus(30, ChronoUnit.MINUTES));
        OidcDiscovery discovery = discovery();
        OidcLoginRequest loginRequest = new OidcLoginRequest();
        loginRequest.state = randomToken(24);
        loginRequest.codeVerifier = randomToken(48);
        loginRequest.returnTo = sanitizeReturnTo(returnTo);
        oidcLoginRequestRepository.persist(loginRequest);
        String codeChallenge = codeChallenge(loginRequest.codeVerifier);
        String authorizationUrl = discovery.authorizationEndpoint()
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(callbackUrl())
                + "&scope=" + encode(scopes)
                + "&state=" + encode(loginRequest.state)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + encode(codeChallenge);
        return URI.create(authorizationUrl);
    }

    @Transactional
    public OidcLoginResult finishLogin(String state, String code) throws IOException {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            throw new IOException("OIDC callback is missing the authorization code or state");
        }
        OidcLoginRequest loginRequest = oidcLoginRequestRepository.findByState(state)
                .orElseThrow(() -> new IOException("Unknown OIDC login state"));
        oidcLoginRequestRepository.delete(loginRequest);

        OidcDiscovery discovery = discovery();
        Map<String, Object> tokenResponse = exchangeCode(discovery.tokenEndpoint(), code, loginRequest.codeVerifier);
        String accessToken = stringValue(tokenResponse.get("access_token"));
        if (accessToken == null) {
            throw new IOException("OIDC token response did not include an access token");
        }

        Map<String, Object> userInfo = userInfo(discovery.userInfoEndpoint(), accessToken);
        String subject = stringValue(userInfo.get("sub"));
        if (subject == null || subject.isBlank()) {
            throw new IOException("OIDC userinfo did not include a subject");
        }
        List<String> groups = stringList(userInfo.get("groups"));
        AppUser user = authService.upsertOidcUser(
                issuer,
                subject,
                firstNonBlank(stringValue(userInfo.get("preferred_username")), stringValue(userInfo.get("nickname"))),
                firstNonBlank(stringValue(userInfo.get("name")), stringValue(userInfo.get("preferred_username"))),
                stringValue(userInfo.get("email")),
                groups.contains("paper-admin"));
        return new OidcLoginResult(user, sanitizeReturnTo(loginRequest.returnTo));
    }

    private OidcDiscovery discovery() throws IOException {
        String url = issuer + "/.well-known/openid-configuration";
        Map<String, Object> payload = readJson(url);
        return new OidcDiscovery(
                required(payload, "authorization_endpoint"),
                required(payload, "token_endpoint"),
                required(payload, "userinfo_endpoint"));
    }

    private Map<String, Object> exchangeCode(String tokenEndpoint, String code, String codeVerifier) throws IOException {
        String body = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(callbackUrl())
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&code_verifier=" + encode(codeVerifier);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return sendJson(request, "OIDC token exchange failed");
    }

    private Map<String, Object> userInfo(String userInfoEndpoint, String accessToken) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(userInfoEndpoint))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        return sendJson(request, "OIDC userinfo request failed");
    }

    private Map<String, Object> readJson(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        return sendJson(request, "OIDC discovery failed");
    }

    private Map<String, Object> sendJson(HttpRequest request, String defaultMessage) throws IOException {
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
        return baseUrl + "/auth/oidc/callback";
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private String codeChallenge(String verifier) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IOException("Failed to generate PKCE challenge", e);
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String required(Map<String, Object> payload, String key) throws IOException {
        String value = stringValue(payload.get(key));
        if (value == null || value.isBlank()) {
            throw new IOException("OIDC discovery response did not include " + key);
        }
        return value;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : iterable) {
            String string = stringValue(item);
            if (string != null) {
                result.add(string);
            }
        }
        return result;
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

    public record OidcLoginResult(AppUser user, String returnTo) {
    }

    private record OidcDiscovery(String authorizationEndpoint, String tokenEndpoint, String userInfoEndpoint) {
    }
}
