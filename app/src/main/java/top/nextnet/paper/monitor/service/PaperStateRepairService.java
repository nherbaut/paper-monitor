package top.nextnet.paper.monitor.service;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import java.util.List;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;

@ApplicationScoped
public class PaperStateRepairService {

    private final LogicalFeedRepository logicalFeedRepository;
    private final PaperRepository paperRepository;
    private final PaperEventService paperEventService;

    public PaperStateRepairService(
            LogicalFeedRepository logicalFeedRepository,
            PaperRepository paperRepository,
            PaperEventService paperEventService
    ) {
        this.logicalFeedRepository = logicalFeedRepository;
        this.paperRepository = paperRepository;
        this.paperEventService = paperEventService;
    }

    @Transactional
    void repairOnStartup(@Observes StartupEvent ignored) {
        int repaired = repairAll();
        if (repaired > 0) {
            Log.infof("Repaired %d paper(s) with orphaned workflow states", repaired);
        }
    }

    @Transactional
    public int repairAll() {
        int repaired = 0;
        for (LogicalFeed logicalFeed : logicalFeedRepository.listAll()) {
            repaired += repairLogicalFeed(logicalFeed);
        }
        return repaired;
    }

    public int repairLogicalFeed(LogicalFeed logicalFeed) {
        if (logicalFeed == null) {
            return 0;
        }
        WorkflowStateConfig workflow = logicalFeed.workflowConfig();
        List<Paper> papers = paperRepository.findAllForExport(logicalFeed);
        int repaired = 0;
        for (Paper paper : papers) {
            String previousStatus = paper.status;
            if (!repairPaperStateIfOrphan(paper, workflow)) {
                continue;
            }
            paperEventService.log(paper, "STATE_REPAIRED",
                    (previousStatus == null || previousStatus.isBlank() ? "<blank>" : previousStatus)
                            + " -> " + paper.status);
            repaired++;
        }
        return repaired;
    }

    static boolean repairPaperStateIfOrphan(Paper paper, WorkflowStateConfig workflow) {
        if (paper == null || workflow == null) {
            return false;
        }
        String previousStatus = paper.status;
        if (previousStatus != null && !previousStatus.isBlank() && workflow.containsLeafState(previousStatus)) {
            return false;
        }
        paper.status = workflow.initialPaperStatus();
        return true;
    }
}
