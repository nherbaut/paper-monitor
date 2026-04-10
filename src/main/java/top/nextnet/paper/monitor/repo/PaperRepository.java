package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;

@ApplicationScoped
public class PaperRepository implements PanacheRepository<Paper> {

    public Optional<Paper> findBySourceLink(String sourceLink) {
        return find("sourceLink", sourceLink).firstResultOptional();
    }

    public List<Paper> findRecentForLogicalFeed(LogicalFeed logicalFeed, int limit) {
        return find("select p from Paper p "
                        + "join fetch p.logicalFeed "
                        + "join fetch p.feed "
                        + "where p.logicalFeed = ?1 "
                        + "order by p.publishedOn desc nulls last, p.discoveredAt desc",
                logicalFeed)
                .page(0, limit)
                .list();
    }

    public List<Paper> findRecent(int limit) {
        return find("select p from Paper p "
                        + "join fetch p.logicalFeed "
                        + "join fetch p.feed "
                        + "order by p.discoveredAt desc")
                .page(0, limit)
                .list();
    }

    public List<Paper> findAllForReader() {
        return find("select p from Paper p "
                        + "join fetch p.logicalFeed "
                        + "join fetch p.feed "
                        + "order by p.discoveredAt desc")
                .list();
    }

    public Optional<Paper> findForReader(Long id) {
        return find("select p from Paper p "
                        + "join fetch p.logicalFeed "
                        + "join fetch p.feed "
                        + "where p.id = ?1",
                id)
                .firstResultOptional();
    }

    public List<Paper> findByLogicalFeedAndStatus(LogicalFeed logicalFeed, String status) {
        return find("select p from Paper p "
                        + "join fetch p.logicalFeed "
                        + "join fetch p.feed "
                        + "where p.logicalFeed = ?1 and p.status = ?2 "
                        + "order by p.discoveredAt desc",
                logicalFeed, status)
                .list();
    }

    public Map<Long, Map<String, Long>> countByLogicalFeedAndStatus() {
        List<Object[]> rows = getEntityManager().createQuery(
                        "select p.logicalFeed.id, p.status, count(p) "
                                + "from Paper p "
                                + "group by p.logicalFeed.id, p.status",
                        Object[].class)
                .getResultList();

        Map<Long, Map<String, Long>> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Long logicalFeedId = (Long) row[0];
            String status = (String) row[1];
            Long count = (Long) row[2];
            counts.computeIfAbsent(logicalFeedId, (ignored) -> new LinkedHashMap<>())
                    .put(status, count);
        }
        return counts;
    }
}
