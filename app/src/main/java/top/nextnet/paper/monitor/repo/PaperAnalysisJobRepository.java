package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.PaperAnalysisJob;

@ApplicationScoped
public class PaperAnalysisJobRepository implements PanacheRepository<PaperAnalysisJob> {

    private static final List<String> ACTIVE_STATUSES = List.of("QUEUED", "ANALYZING", "SAVING");

    public Optional<PaperAnalysisJob> findActiveByUserAndPaper(Long userId, Long paperId) {
        return find("userId = ?1 and paperId = ?2 and status in ?3 order by startedAt desc",
                userId, paperId, ACTIVE_STATUSES).firstResultOptional();
    }

    public Optional<PaperAnalysisJob> findLatestByUserAndPaper(Long userId, Long paperId) {
        return find("userId = ?1 and paperId = ?2 order by startedAt desc", userId, paperId).firstResultOptional();
    }

    public Optional<PaperAnalysisJob> findByIdAndUser(Long id, Long userId) {
        return find("id = ?1 and userId = ?2", id, userId).firstResultOptional();
    }

    public List<PaperAnalysisJob> findInterrupted() {
        return find("status in ?1", ACTIVE_STATUSES).list();
    }
}
