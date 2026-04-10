package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import top.nextnet.paper.monitor.model.Feed;

@ApplicationScoped
public class FeedRepository implements PanacheRepository<Feed> {

    public Optional<Feed> findByUrl(String url) {
        return find("url", url).firstResultOptional();
    }
}
