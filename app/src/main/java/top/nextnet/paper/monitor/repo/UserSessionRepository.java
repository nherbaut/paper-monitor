package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Optional;
import top.nextnet.paper.monitor.model.UserSession;

@ApplicationScoped
public class UserSessionRepository implements PanacheRepository<UserSession> {

    public Optional<UserSession> findActiveByToken(String token) {
        return find("token = ?1 and expiresAt > ?2", token, Instant.now()).firstResultOptional();
    }

    public long deleteExpired() {
        return delete("expiresAt <= ?1", Instant.now());
    }
}
