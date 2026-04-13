package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import top.nextnet.paper.monitor.model.LogicalFeed;

@ApplicationScoped
public class LogicalFeedRepository implements PanacheRepository<LogicalFeed> {

    public List<LogicalFeed> findAllForAdminView() {
        return getEntityManager()
                .createQuery("""
                        select distinct logicalFeed
                        from LogicalFeed logicalFeed
                        left join fetch logicalFeed.owner
                        left join fetch logicalFeed.accessGrants grant
                        left join fetch grant.user
                        order by logicalFeed.name
                        """, LogicalFeed.class)
                .getResultList();
    }

    public boolean existsPublicReadable() {
        return count("publicReadable", true) > 0;
    }
}
