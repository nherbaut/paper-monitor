package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Optional;
import top.nextnet.paper.monitor.model.OidcLoginRequest;

@ApplicationScoped
public class OidcLoginRequestRepository implements PanacheRepository<OidcLoginRequest> {

    public Optional<OidcLoginRequest> findByState(String state) {
        return find("state", state).firstResultOptional();
    }

    public long deleteOlderThan(Instant threshold) {
        return delete("createdAt < ?1", threshold);
    }
}
