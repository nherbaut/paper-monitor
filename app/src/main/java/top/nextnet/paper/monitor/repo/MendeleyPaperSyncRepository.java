package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.MendeleyFeedSync;
import top.nextnet.paper.monitor.model.MendeleyPaperSync;
import top.nextnet.paper.monitor.model.Paper;

@ApplicationScoped
public class MendeleyPaperSyncRepository implements PanacheRepository<MendeleyPaperSync> {
    public Optional<MendeleyPaperSync> findByFeedAndDocument(MendeleyFeedSync feed, String documentId) {
        return find("feedSync = ?1 and mendeleyDocumentId = ?2", feed, documentId).firstResultOptional();
    }
    public Optional<MendeleyPaperSync> findByFeedAndPaper(MendeleyFeedSync feed, Paper paper) {
        return find("feedSync = ?1 and paper = ?2", feed, paper).firstResultOptional();
    }
    public List<MendeleyPaperSync> findByFeed(MendeleyFeedSync feed) { return list("feedSync", feed); }
}
