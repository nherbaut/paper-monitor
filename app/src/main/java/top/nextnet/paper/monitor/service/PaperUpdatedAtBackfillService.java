package top.nextnet.paper.monitor.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PaperEvent;
import top.nextnet.paper.monitor.repo.PaperEventRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;

@ApplicationScoped
public class PaperUpdatedAtBackfillService {
    private static final Logger LOG = Logger.getLogger(PaperUpdatedAtBackfillService.class);
    private static final int BATCH_SIZE = 250;
    private final PaperRepository papers;
    private final PaperEventRepository events;
    private final ManagedExecutor executor;

    public PaperUpdatedAtBackfillService(PaperRepository papers, PaperEventRepository events,
            ManagedExecutor executor) {
        this.papers = papers;
        this.events = events;
        this.executor = executor;
    }

    void backfillAfterStartup(@Observes StartupEvent ignored) {
        executor.execute(() -> {
            try {
                int total = 0;
                int updated;
                do {
                    updated = QuarkusTransaction.requiringNew().call(() -> {
                        List<Paper> missing = papers.find("updatedAt is null order by id")
                                .page(0, BATCH_SIZE).list();
                        Map<Long, List<PaperEvent>> byPaper = events.findByPaperIds(
                                missing.stream().map(paper -> paper.id).toList());
                        missing.forEach(paper -> paper.updatedAt = PaperEventService.backfilledUpdatedAt(
                                paper, byPaper.get(paper.id)));
                        return missing.size();
                    });
                    total += updated;
                } while (updated == BATCH_SIZE);
                if (total > 0) LOG.infof("Backfilled update timestamps for %d paper(s)", total);
            } catch (RuntimeException error) {
                LOG.error("Could not backfill paper update timestamps; application startup will continue", error);
            }
        });
    }
}
