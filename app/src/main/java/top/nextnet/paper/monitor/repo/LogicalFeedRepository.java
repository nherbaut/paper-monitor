package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import top.nextnet.paper.monitor.model.LogicalFeed;

@ApplicationScoped
public class LogicalFeedRepository implements PanacheRepository<LogicalFeed> {
}
