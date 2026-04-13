package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
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

    public Optional<AppUser> findByEmail(String email) {
        return find("lower(email) = ?1", email.toLowerCase()).firstResultOptional();
    }

    public long countLocalAccounts() {
        return count("authProvider", "LOCAL");
    }
}
