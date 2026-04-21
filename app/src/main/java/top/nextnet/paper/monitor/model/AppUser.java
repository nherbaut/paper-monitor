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

@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"oidcIssuer", "oidcSubject"})
})
public class AppUser extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 120)
    public String username;

    @Column(length = 255)
    public String displayName;

    @Column(length = 255)
    public String email;

    @Column(nullable = false, length = 16)
    public String authProvider;

    @Column(length = 255)
    public String oidcIssuer;

    @Column(length = 255)
    public String oidcSubject;

    @Column(length = 255)
    public String passwordSalt;

    @Column(length = 255)
    public String passwordHash;

    @Column(nullable = false)
    public boolean admin;

    @Column(nullable = false)
    public boolean emailVerified;

    public Instant emailVerifiedAt;

    @Column(nullable = false)
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
        return emailVerified || isLegacyActivatedLocalAccount();
    }

    public boolean isApproved() {
        return approved || isLegacyActivatedLocalAccount();
    }

    public boolean isActive() {
        return isEmailVerified() && isApproved();
    }

    public boolean isLegacyActivatedLocalAccount() {
        return isLocalAccount()
                && emailVerificationToken == null
                && !emailVerified
                && !approved;
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
}
