package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.NewCookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.UserSession;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.AppUserRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedAccessGrantRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import top.nextnet.paper.monitor.repo.UserSessionRepository;
import top.nextnet.paper.monitor.repo.UserSettingsRepository;

@ApplicationScoped
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final LogicalFeedRepository logicalFeedRepository;
    private final LogicalFeedAccessGrantRepository logicalFeedAccessGrantRepository;
    private final UserSessionRepository userSessionRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final NotificationService notificationService;
    private final SystemSettingsService systemSettingsService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String sessionCookieName;
    private final long sessionTtlDays;
    private final String baseUrl;

    public AuthService(
            AppUserRepository appUserRepository,
            LogicalFeedRepository logicalFeedRepository,
            LogicalFeedAccessGrantRepository logicalFeedAccessGrantRepository,
            UserSessionRepository userSessionRepository,
            UserSettingsRepository userSettingsRepository,
            NotificationService notificationService,
            SystemSettingsService systemSettingsService,
            @ConfigProperty(name = "paper-monitor.auth.session.cookie-name", defaultValue = "paper_monitor_session") String sessionCookieName,
            @ConfigProperty(name = "paper-monitor.auth.session.ttl-days", defaultValue = "30") long sessionTtlDays,
            @ConfigProperty(name = "paper-monitor.base-url", defaultValue = "http://localhost:8080") String baseUrl
    ) {
        this.appUserRepository = appUserRepository;
        this.logicalFeedRepository = logicalFeedRepository;
        this.logicalFeedAccessGrantRepository = logicalFeedAccessGrantRepository;
        this.userSessionRepository = userSessionRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.notificationService = notificationService;
        this.systemSettingsService = systemSettingsService;
        this.sessionCookieName = sessionCookieName;
        this.sessionTtlDays = sessionTtlDays;
        this.baseUrl = baseUrl == null ? "http://localhost:8080" : baseUrl.trim();
    }

    public Optional<UserSession> findActiveSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return userSessionRepository.findActiveByToken(token);
    }

    @Transactional
    public AppUser loginLocal(String username, String password) {
        String normalizedUsername = normalizeRequired(username, "Username is required").toLowerCase();
        String normalizedPassword = normalizeRequired(password, "Password is required");
        Optional<AppUser> existing = appUserRepository.findLocalByUsername(normalizedUsername);
        if (existing.isEmpty() && appUserRepository.countLocalAccounts() == 0) {
            AppUser bootstrap = new AppUser();
            bootstrap.username = normalizedUsername;
            bootstrap.displayName = normalizedUsername;
            bootstrap.authProvider = "LOCAL";
            bootstrap.admin = true;
            bootstrap.emailVerified = true;
            bootstrap.emailVerifiedAt = Instant.now();
            bootstrap.approved = true;
            bootstrap.approvedAt = Instant.now();
            setPassword(bootstrap, normalizedPassword);
            appUserRepository.persist(bootstrap);
            ensureSettings(bootstrap);
            return bootstrap;
        }
        AppUser user = existing.orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));
        normalizeLegacyActivation(user);
        if (!verifyPassword(user, normalizedPassword)) {
            throw new IllegalArgumentException("Invalid username or password");
        }
        assertLocalLoginAllowed(user);
        ensureSettings(user);
        return user;
    }

    @Transactional
    public AppUser createLocalUser(String username, String displayName, String email, String password, boolean admin) {
        String normalizedUsername = normalizeRequired(username, "Username is required").toLowerCase();
        if (appUserRepository.findLocalByUsername(normalizedUsername).isPresent()) {
            throw new IllegalArgumentException("A local user with this username already exists");
        }
        AppUser user = new AppUser();
        user.username = normalizedUsername;
        user.displayName = normalize(displayName);
        user.email = normalize(email);
        user.authProvider = "LOCAL";
        user.admin = admin;
        user.emailVerified = true;
        user.emailVerifiedAt = user.email == null ? null : Instant.now();
        user.approved = true;
        user.approvedAt = Instant.now();
        setPassword(user, normalizeRequired(password, "Password is required"));
        appUserRepository.persist(user);
        ensureSettings(user);
        notificationService.sendUserAccountNotification(user,
                "Paper Monitor account created",
                "A Paper Monitor local account was created for you.\n\nUsername: " + user.username);
        return user;
    }

    @Transactional
    public AppUser signUpLocal(String username, String displayName, String email, String password) {
        if (appUserRepository.countLocalAccounts() == 0) {
            throw new IllegalArgumentException("Create the initial admin account from the sign-in form first");
        }
        String normalizedUsername = normalizeRequired(username, "Username is required").toLowerCase();
        String normalizedEmail = normalizeRequired(email, "Email is required").toLowerCase();
        if (appUserRepository.findLocalByUsername(normalizedUsername).isPresent()) {
            throw new IllegalArgumentException("A local user with this username already exists");
        }
        if (appUserRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("An account with this email already exists");
        }
        AppUser user = new AppUser();
        user.username = normalizedUsername;
        user.displayName = normalize(displayName);
        user.email = normalizedEmail;
        user.authProvider = "LOCAL";
        user.admin = false;
        user.emailVerified = false;
        user.approved = !systemSettingsService.requireAdminApprovalForNewUsers();
        user.approvedAt = user.approved ? Instant.now() : null;
        user.emailVerificationToken = randomToken(32);
        setPassword(user, normalizeRequired(password, "Password is required"));
        appUserRepository.persist(user);
        ensureSettings(user);
        notificationService.sendSignupVerificationEmail(user, verificationUrl(user.emailVerificationToken));
        if (!user.approved) {
            notificationService.sendPendingSignupNotification(appUserRepository.findAdminUsersWithEmail(), user, adminUsersUrl());
        }
        return user;
    }

    @Transactional
    public AppUser verifyEmail(String token) {
        String normalizedToken = normalizeRequired(token, "Verification token is required");
        AppUser user = appUserRepository.findByEmailVerificationToken(normalizedToken)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired verification link"));
        user.emailVerified = true;
        user.emailVerifiedAt = Instant.now();
        user.emailVerificationToken = null;
        return user;
    }

    @Transactional
    public AppUser approveUser(Long userId) {
        AppUser user = appUserRepository.findByIdOptional(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
        user.approved = true;
        if (user.approvedAt == null) {
            user.approvedAt = Instant.now();
        }
        notificationService.sendAccountApprovedNotification(user, loginUrl());
        return user;
    }

    @Transactional
    public AppUser updateUser(Long userId, String displayName, String email, boolean admin, String password) {
        AppUser user = appUserRepository.findByIdOptional(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
        user.displayName = normalize(displayName);
        user.email = normalize(email);
        user.admin = admin;
        if (password != null && !password.isBlank()) {
            if (!user.isLocalAccount()) {
                throw new IllegalArgumentException("Only local accounts can have a local password");
            }
            setPassword(user, password.trim());
        }
        notificationService.sendUserAccountNotification(user,
                "Paper Monitor account updated",
                "Your Paper Monitor account settings were updated.");
        return user;
    }

    @Transactional
    public void deleteUser(Long userId) {
        AppUser user = appUserRepository.findByIdOptional(userId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
        if (appUserRepository.count("admin", true) <= 1 && user.admin) {
            throw new IllegalArgumentException("Cannot delete the last admin user");
        }
        if (logicalFeedRepository.count("owner", user) > 0) {
            throw new IllegalArgumentException("Cannot delete a user who still owns logical feeds");
        }
        notificationService.sendUserAccountNotification(user,
                "Paper Monitor account removed",
                "Your Paper Monitor account was removed.");
        logicalFeedAccessGrantRepository.deleteByUser(user);
        user.delete();
    }

    public List<AppUser> allUsers() {
        List<AppUser> users = appUserRepository.find("order by approved asc, emailVerified asc, admin desc, username asc").list();
        users.forEach(this::normalizeLegacyActivation);
        return users;
    }

    @Transactional
    public AppUser upsertOidcUser(String issuer, String subject, String username, String displayName, String email, boolean admin) {
        AppUser user = appUserRepository.findByOidcIdentity(issuer, subject).orElseGet(AppUser::new);
        user.authProvider = "OIDC";
        user.oidcIssuer = issuer;
        user.oidcSubject = subject;
        user.username = fallback(normalize(username), fallback(normalize(email), subject));
        user.displayName = fallback(normalize(displayName), user.username);
        user.email = normalize(email);
        user.admin = admin;
        user.emailVerified = true;
        if (user.emailVerifiedAt == null) {
            user.emailVerifiedAt = Instant.now();
        }
        user.approved = true;
        if (user.approvedAt == null) {
            user.approvedAt = Instant.now();
        }
        user.emailVerificationToken = null;
        user.lastLoginAt = Instant.now();
        if (user.id == null) {
            appUserRepository.persist(user);
        }
        ensureSettings(user);
        return user;
    }

    @Transactional
    public AppUser upsertGithubUser(String githubUserId, String githubLogin, String displayName, String email) {
        String normalizedGithubUserId = normalizeRequired(githubUserId, "GitHub user id is required");
        String normalizedGithubLogin = fallback(normalize(githubLogin), "github-" + normalizedGithubUserId);
        String normalizedEmail = normalize(email);

        AppUser user = appUserRepository.findByGithubUserId(normalizedGithubUserId).orElse(null);
        boolean created = false;
        if (user == null && normalizedEmail != null) {
            user = appUserRepository.findByEmail(normalizedEmail).orElse(null);
        }
        if (user == null) {
            user = new AppUser();
            user.authProvider = "GITHUB";
            user.username = uniqueUsername(normalizedGithubLogin);
            user.displayName = fallback(normalize(displayName), normalizedGithubLogin);
            user.email = normalizedEmail;
            user.admin = false;
            user.approved = !systemSettingsService.requireAdminApprovalForNewUsers();
            user.approvedAt = user.approved ? Instant.now() : null;
            created = true;
        }

        if (user.githubUserId != null && !normalizedGithubUserId.equals(user.githubUserId)) {
            throw new IllegalArgumentException("This account is already linked to a different GitHub identity");
        }
        user.githubUserId = normalizedGithubUserId;
        user.githubLogin = normalizedGithubLogin;
        if (user.displayName == null || user.displayName.isBlank()) {
            user.displayName = fallback(normalize(displayName), normalizedGithubLogin);
        }
        if (user.email == null || user.email.isBlank()) {
            user.email = normalizedEmail;
        }
        user.emailVerified = true;
        if (user.emailVerifiedAt == null) {
            user.emailVerifiedAt = Instant.now();
        }
        user.emailVerificationToken = null;
        user.lastLoginAt = Instant.now();
        if (created) {
            appUserRepository.persist(user);
        }
        ensureSettings(user);
        if (created && !user.approved) {
            notificationService.sendPendingSignupNotification(appUserRepository.findAdminUsersWithEmail(), user, adminUsersUrl());
        }
        return user;
    }

    @Transactional
    public UserSession createSession(AppUser user) {
        userSessionRepository.deleteExpired();
        UserSession session = new UserSession();
        session.user = user;
        session.token = randomToken(32);
        session.expiresAt = Instant.now().plus(sessionTtlDays, ChronoUnit.DAYS);
        user.lastLoginAt = Instant.now();
        userSessionRepository.persist(session);
        return session;
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        userSessionRepository.delete("token", token);
    }

    @Transactional
    public UserSettings ensureSettings(AppUser user) {
        return userSettingsRepository.findByUser(user).orElseGet(() -> {
            UserSettings settings = new UserSettings();
            settings.user = user;
            settings.speedMultiplier = 1.1d;
            userSettingsRepository.persist(settings);
            user.settings = settings;
            return settings;
        });
    }

    @Transactional
    public void storeGithubAccessToken(AppUser user, String accessToken) {
        if (user == null || accessToken == null || accessToken.isBlank()) {
            return;
        }
        UserSettings settings = ensureSettings(user);
        settings.githubAccessToken = accessToken.trim();
        settings.githubAccessTokenUpdatedAt = Instant.now();
    }

    public NewCookie loginCookie(String token) {
        return new NewCookie.Builder(sessionCookieName)
                .value(token)
                .path("/")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .secure(isSecureCookie())
                .maxAge((int) ChronoUnit.SECONDS.between(Instant.now(), Instant.now().plus(sessionTtlDays, ChronoUnit.DAYS)))
                .build();
    }

    public NewCookie clearCookie() {
        return new NewCookie.Builder(sessionCookieName)
                .value("")
                .path("/")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .secure(isSecureCookie())
                .maxAge(0)
                .build();
    }

    public String sessionCookieName() {
        return sessionCookieName;
    }

    public boolean oidcRedirectRequiresSecureCookie() {
        return isSecureCookie();
    }

    private boolean isSecureCookie() {
        return baseUrl.startsWith("https://");
    }

    private void assertLocalLoginAllowed(AppUser user) {
        if (!user.isEmailVerified()) {
            throw new IllegalArgumentException("Your email address must be verified before you can sign in");
        }
        if (!user.isApproved()) {
            throw new IllegalArgumentException("Your account is awaiting admin approval");
        }
    }

    private void normalizeLegacyActivation(AppUser user) {
        if (user == null) {
            return;
        }
        if (!"LOCAL".equals(user.authProvider)) {
            return;
        }
        if (user.emailVerificationToken != null) {
            return;
        }
        if (user.emailVerified || user.approved) {
            return;
        }
        user.emailVerified = true;
        if (user.email != null && !user.email.isBlank() && user.emailVerifiedAt == null) {
            user.emailVerifiedAt = user.createdAt == null ? Instant.now() : user.createdAt;
        }
        user.approved = true;
        if (user.approvedAt == null) {
            user.approvedAt = user.createdAt == null ? Instant.now() : user.createdAt;
        }
    }

    private void setPassword(AppUser user, String password) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        user.passwordSalt = Base64.getEncoder().encodeToString(salt);
        user.passwordHash = hash(password, salt);
    }

    private boolean verifyPassword(AppUser user, String password) {
        if (user.passwordSalt == null || user.passwordHash == null) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(user.passwordSalt);
        return MessageDigest.isEqual(user.passwordHash.getBytes(StandardCharsets.UTF_8),
                hash(password, salt).getBytes(StandardCharsets.UTF_8));
    }

    private String hash(String password, byte[] salt) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
            return HexFormat.of().formatHex(factory.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash password", e);
        }
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeRequired(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String fallback(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String uniqueUsername(String base) {
        String normalizedBase = fallback(normalize(base), "github-user").toLowerCase();
        String candidate = normalizedBase;
        int suffix = 2;
        while (appUserRepository.findByUsername(candidate).isPresent()) {
            candidate = normalizedBase + "-" + suffix;
            suffix += 1;
        }
        return candidate;
    }

    private String verificationUrl(String token) {
        return normalizeBaseUrl() + "/signup/verify?token=" + token;
    }

    private String adminUsersUrl() {
        return normalizeBaseUrl() + "/admin#users";
    }

    private String loginUrl() {
        return normalizeBaseUrl() + "/login";
    }

    private String normalizeBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
