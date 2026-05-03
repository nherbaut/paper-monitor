package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.AppUser;

@ApplicationScoped
public class AppUserRepository implements PanacheRepository<AppUser> {

    public Optional<AppUser> findLocalByUsername(String username) {
        return find("authProvider = ?1 and lower(username) = ?2", "LOCAL", username.toLowerCase()).firstResultOptional();
    }

    public Optional<AppUser> findByOidcIdentity(String issuer, String subject) {
        return find("oidcIssuer = ?1 and oidcSubject = ?2", issuer, subject).firstResultOptional();
    }

    public Optional<AppUser> findByGithubUserId(String githubUserId) {
        return find("githubUserId", githubUserId).firstResultOptional();
    }

    public Optional<AppUser> findByEmail(String email) {
        return find("lower(email) = ?1", email.toLowerCase()).firstResultOptional();
    }

    public Optional<AppUser> findByUsername(String username) {
        return find("lower(username) = ?1", username.toLowerCase()).firstResultOptional();
    }

    public Optional<AppUser> findByEmailVerificationToken(String token) {
        return find("emailVerificationToken", token).firstResultOptional();
    }

    public List<AppUser> findAdminUsersWithEmail() {
        return find("admin = true and email is not null and trim(email) <> '' order by username asc").list();
    }

    public long countLocalAccounts() {
        return count("authProvider", "LOCAL");
    }
}
