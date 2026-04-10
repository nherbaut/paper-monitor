package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
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
}
