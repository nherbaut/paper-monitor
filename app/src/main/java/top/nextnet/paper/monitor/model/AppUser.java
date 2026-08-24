package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"oidcIssuer", "oidcSubject"}),
        @UniqueConstraint(columnNames = {"githubUserId"})
})
public class AppUser extends PanacheEntityBase {

    private static final int STUDENT_AVATAR_COUNT = 24;
    private static final String STUDENT_AVATAR_PREFIX = "miage-student-";
    private static final String STUDENT_AVATAR_SUFFIX = ".png";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 120)
    public String username;

    @Column(length = 255)
    public String displayName;

    @Column(length = 255)
    public String email;

    @Column(length = 64)
    public String avatarFileName;

    @Column(nullable = false, length = 16)
    public String authProvider;

    @Column(length = 255)
    public String oidcIssuer;

    @Column(length = 255)
    public String oidcSubject;

    @Column(length = 255)
    public String githubUserId;

    @Column(length = 255)
    public String githubLogin;

    @Column(length = 255)
    public String passwordSalt;

    @Column(length = 255)
    public String passwordHash;

    @Column(nullable = false)
    public boolean admin;

    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean emailVerified;

    public Instant emailVerifiedAt;

    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean approved;

    public Instant approvedAt;

    @Column(length = 255)
    public String emailVerificationToken;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant lastLoginAt;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public UserSettings settings;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<UserSession> sessions = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<LogicalFeedAccessGrant> logicalFeedAccessGrants = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<AppUserEmail> secondaryEmails = new ArrayList<>();

    public boolean isAdmin() {
        return admin;
    }

    public boolean isLocalAccount() {
        return "LOCAL".equals(authProvider);
    }

    public boolean isOidcAccount() {
        return "OIDC".equals(authProvider);
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public boolean isGithubAccount() {
        return "GITHUB".equals(authProvider);
    }

    public boolean hasGithubLogin() {
        return githubUserId != null && !githubUserId.isBlank();
    }

    public boolean isApproved() {
        return approved;
    }

    public boolean isActive() {
        return isEmailVerified() && isApproved();
    }

    public String displayLabel() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        if (username != null && !username.isBlank()) {
            return username;
        }
        if (email != null && !email.isBlank()) {
            return email;
        }
        return "User";
    }

    public String avatarUrl() {
        String selected = validAvatarFileName(avatarFileName);
        if (selected == null) {
            String identity = id != null
                    ? Long.toString(id)
                    : String.join("|", valueOrEmpty(username), valueOrEmpty(email), valueOrEmpty(displayName));
            int avatarNumber = Math.floorMod(identity.hashCode(), STUDENT_AVATAR_COUNT) + 1;
            selected = avatarFileName(avatarNumber);
        }
        return "/assets/student-avatar/" + selected;
    }

    public static String normalizeAvatarFileName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (validAvatarFileName(normalized) == null) {
            throw new IllegalArgumentException("Choose one of the available student avatars");
        }
        return normalized;
    }

    public static List<AvatarOption> avatarOptions() {
        return java.util.stream.IntStream.rangeClosed(1, STUDENT_AVATAR_COUNT)
                .mapToObj(number -> {
                    String fileName = avatarFileName(number);
                    return new AvatarOption(fileName, "/assets/student-avatar/" + fileName, "Avatar " + number);
                })
                .toList();
    }

    private static String validAvatarFileName(String value) {
        if (value == null) {
            return null;
        }
        for (int number = 1; number <= STUDENT_AVATAR_COUNT; number++) {
            String candidate = avatarFileName(number);
            if (candidate.equals(value)) {
                return candidate;
            }
        }
        return null;
    }

    private static String avatarFileName(int number) {
        return STUDENT_AVATAR_PREFIX + String.format(Locale.ROOT, "%02d", number) + STUDENT_AVATAR_SUFFIX;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    public record AvatarOption(String fileName, String url, String label) {
    }
}
