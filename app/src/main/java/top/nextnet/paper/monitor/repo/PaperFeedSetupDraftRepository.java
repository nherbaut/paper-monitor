package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Optional;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.PaperFeedSetupDraft;

@ApplicationScoped
public class PaperFeedSetupDraftRepository implements PanacheRepository<PaperFeedSetupDraft> {

    public Optional<PaperFeedSetupDraft> findOwned(String id, AppUser user) {
        if (id == null || user == null) {
            return Optional.empty();
        }
        return find("id = ?1 and user = ?2 and expiresAt > ?3", id, user, Instant.now()).firstResultOptional();
    }

    public long deleteExpired() {
        return delete("expiresAt <= ?1", Instant.now());
    }
}
