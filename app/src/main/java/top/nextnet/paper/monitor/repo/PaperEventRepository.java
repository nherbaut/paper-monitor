package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.nextnet.paper.monitor.model.PaperEvent;

@ApplicationScoped
public class PaperEventRepository implements PanacheRepository<PaperEvent> {

    public List<PaperEvent> findRecent(int limit) {
        return find("select e from PaperEvent e "
                        + "join fetch e.paper p "
                        + "join fetch p.logicalFeed "
                        + "order by e.happenedAt desc")
                .page(0, limit)
                .list();
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
}
