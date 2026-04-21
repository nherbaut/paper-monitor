package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;

@ApplicationScoped
public class PaperRepository implements PanacheRepository<Paper> {

    public Optional<Paper> findByLogicalFeedAndSourceLink(LogicalFeed logicalFeed, String sourceLink) {
        return find("logicalFeed = ?1 and sourceLink = ?2", logicalFeed, sourceLink).firstResultOptional();
    }

    public Optional<Paper> findByShareToken(String shareToken) {
        return find("select p from Paper p "
                        + "join fetch p.logicalFeed "
                        + "join fetch p.feed "
                        + "where p.shareToken = ?1",
                shareToken)
                .firstResultOptional();
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
                        + "order by p.publishedOn desc nulls last, p.discoveredAt desc")
                .page(0, limit)
                .list();
    }

    public List<Paper> findAllForReader() {
        return find("select p from Paper p "
                        + "join fetch p.logicalFeed "
                        + "join fetch p.feed "
                        + "order by p.publishedOn desc nulls last, p.discoveredAt desc")
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
                        + "order by p.publishedOn desc nulls last, p.discoveredAt desc",
                logicalFeed, status)
                .list();
    }

    public List<Paper> findForTabExport(LogicalFeed logicalFeed, List<String> leafStates, String topLevelStatus) {
        List<String> matchingStates = leafStates.stream()
                .filter((state) -> state.equals(topLevelStatus) || state.startsWith(topLevelStatus + "/"))
                .toList();
        if (matchingStates.isEmpty()) {
            return List.of();
        }
        return find("select p from Paper p "
                        + "join fetch p.logicalFeed "
                        + "join fetch p.feed "
                        + "where p.logicalFeed = ?1 and p.status in ?2 "
                        + "order by p.publishedOn desc nulls last, p.discoveredAt desc",
                logicalFeed, matchingStates)
                .list();
    }

    public List<Paper> findAllForExport(LogicalFeed logicalFeed) {
        return find("select p from Paper p "
                        + "join fetch p.logicalFeed "
                        + "join fetch p.feed "
                        + "where p.logicalFeed = ?1 "
                        + "order by p.publishedOn desc nulls last, p.discoveredAt desc",
                logicalFeed)
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

    public long countDiscoveredSince(Instant threshold) {
        return count("discoveredAt >= ?1", threshold);
    }
}
