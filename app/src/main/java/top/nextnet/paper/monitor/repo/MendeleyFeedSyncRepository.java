package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.MendeleyFeedSync;

@ApplicationScoped
public class MendeleyFeedSyncRepository implements PanacheRepository<MendeleyFeedSync> {
    public Optional<MendeleyFeedSync> findByUserAndFeed(AppUser user, LogicalFeed feed) {
        return find("user = ?1 and logicalFeed = ?2", user, feed).firstResultOptional();
    }
    public List<MendeleyFeedSync> findByUser(AppUser user) { return list("user", user); }
    public List<MendeleyFeedSync> findEnabledByLogicalFeedId(Long logicalFeedId) {
        return list("enabled = true and logicalFeed.id = ?1", logicalFeedId);
    }
    public List<MendeleyFeedSync> findEnabled() { return list("enabled", true); }
}
