package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Optional;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.PaperFeedSetupDraft;

@ApplicationScoped
public class PaperFeedSetupDraftRepository implements PanacheRepositoryBase<PaperFeedSetupDraft, String> {

    public Optional<PaperFeedSetupDraft> findAccessible(String id, AppUser user) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        if (user == null) {
            return find("id = ?1 and user is null and expiresAt > ?2", id, Instant.now()).firstResultOptional();
        }
        return find("id = ?1 and (user = ?2 or user is null) and expiresAt > ?3", id, user, Instant.now())
                .firstResultOptional();
    }

    public Optional<PaperFeedSetupDraft> findPendingAnonymous(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return find("""
                select draft from PaperFeedSetupDraft draft
                join fetch draft.logicalFeed logicalFeed
                where draft.id = ?1
                  and draft.user is null
                  and logicalFeed.owner is null
                  and draft.expiresAt > ?2
                """, id, Instant.now()).firstResultOptional();
    }

    public java.util.List<PaperFeedSetupDraft> findExpired() {
        return find("expiresAt <= ?1", Instant.now()).list();
    }

    public long deleteExpired() {
        return delete("expiresAt <= ?1", Instant.now());
    }
}
