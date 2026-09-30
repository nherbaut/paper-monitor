package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.ArxivPdfRetrievalJob;

@ApplicationScoped
public class ArxivPdfRetrievalJobRepository implements PanacheRepository<ArxivPdfRetrievalJob> {
    private static final List<String> ACTIVE = List.of("QUEUED", "RUNNING", "FINALIZING");

    public Optional<ArxivPdfRetrievalJob> findActiveByFeed(Long feedId) {
        return find("logicalFeedId = ?1 and status in ?2 order by startedAt desc", feedId, ACTIVE)
                .firstResultOptional();
    }

    public Optional<ArxivPdfRetrievalJob> findLatestByFeed(Long feedId) {
        return find("logicalFeedId = ?1 order by startedAt desc", feedId).firstResultOptional();
    }

    public List<ArxivPdfRetrievalJob> findInterrupted() {
        return find("status in ?1", ACTIVE).list();
    }

    public static boolean isActive(String status) {
        return ACTIVE.contains(status);
    }
}
