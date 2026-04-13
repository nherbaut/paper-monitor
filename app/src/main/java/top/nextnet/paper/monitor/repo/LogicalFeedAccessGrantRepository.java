package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.LogicalFeedAccessGrant;

@ApplicationScoped
public class LogicalFeedAccessGrantRepository implements PanacheRepository<LogicalFeedAccessGrant> {

    public Optional<LogicalFeedAccessGrant> findByLogicalFeedAndUser(LogicalFeed logicalFeed, AppUser user) {
        return find("logicalFeed = ?1 and user = ?2", logicalFeed, user).firstResultOptional();
    }

    public List<LogicalFeedAccessGrant> findByUser(AppUser user) {
        return find("user", user).list();
    }

    public long deleteByUser(AppUser user) {
        return delete("user", user);
    }
}
