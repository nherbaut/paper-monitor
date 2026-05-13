package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.Feed;

@ApplicationScoped
public class FeedRepository implements PanacheRepository<Feed> {

    public Optional<Feed> findByUrl(String url) {
        return find("url", url).firstResultOptional();
    }

    public List<Feed> findAllForAdminView() {
        return getEntityManager()
                .createQuery("""
                        select feed
                        from Feed feed
                        join fetch feed.logicalFeed logicalFeed
                        join fetch logicalFeed.owner
                        order by feed.name
                        """, Feed.class)
                .getResultList();
    }
}
