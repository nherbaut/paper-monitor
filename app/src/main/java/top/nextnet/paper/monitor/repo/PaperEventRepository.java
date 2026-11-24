package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import top.nextnet.paper.monitor.model.PaperEvent;

@ApplicationScoped
public class PaperEventRepository implements PanacheRepository<PaperEvent> {

    public List<PaperEvent> findRecent(int limit) {
        return find("select e from PaperEvent e "
                        + "join fetch e.paper p "
                        + "join fetch p.logicalFeed "
                        + "order by e.happenedAt desc, e.id desc")
                .page(0, limit)
                .list();
    }

    public Map<Long, Long> latestStatusEventIds(List<Long> paperIds) {
        if (paperIds.isEmpty()) return Map.of();
        List<Object[]> rows = getEntityManager().createQuery(
                "select e.paper.id, max(e.id) from PaperEvent e where e.paper.id in :ids "
                        + "and e.type in :types group by e.paper.id", Object[].class)
                .setParameter("ids", paperIds)
                .setParameter("types", List.of("STATE_CHANGED", "STATE_REVERTED", "STATE_MIGRATED",
                        "STATE_REPAIRED", "MENDELEY_PULL", "MENDELEY_MERGE"))
                .getResultList();
        Map<Long, Long> latest = new LinkedHashMap<>();
        for (Object[] row : rows) latest.put((Long) row[0], (Long) row[1]);
        return latest;
    }

    public Map<Long, List<PaperEvent>> findByPaperIds(List<Long> paperIds) {
        if (paperIds == null || paperIds.isEmpty()) {
            return Map.of();
        }
        List<PaperEvent> events = find("select e from PaperEvent e "
                        + "join fetch e.paper p "
                        + "where p.id in ?1 "
                        + "order by e.happenedAt desc",
                paperIds)
                .list();
        Map<Long, List<PaperEvent>> grouped = new LinkedHashMap<>();
        for (PaperEvent event : events) {
            grouped.computeIfAbsent(event.paper.id, ignored -> new java.util.ArrayList<>()).add(event);
        }
        return grouped;
    }

    public boolean existsByPaperIdAndType(Long paperId, String type) {
        return count("paper.id = ?1 and type = ?2", paperId, type) > 0;
    }

    public Set<Long> paperIdsWithEventType(List<Long> paperIds, String type) {
        if (paperIds == null || paperIds.isEmpty()) {
            return Set.of();
        }
        return getEntityManager().createQuery(
                        "select distinct e.paper.id from PaperEvent e where e.paper.id in :paperIds and e.type = :type",
                        Long.class)
                .setParameter("paperIds", paperIds)
                .setParameter("type", type)
                .getResultStream()
                .collect(Collectors.toSet());
    }
}
