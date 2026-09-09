package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import java.time.Instant;
import java.util.Set;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PaperEvent;
import top.nextnet.paper.monitor.repo.PaperEventRepository;

@ApplicationScoped
public class PaperEventService {

    static final Set<String> LOCAL_CHANGE_TYPES = Set.of(
            "FETCH", "PDF_UPLOADED", "STATE_CHANGED", "STATE_MIGRATED", "STATE_REPAIRED",
            "NOTES_CHANGED", "TAGS_CHANGED", "MENDELEY_MERGE");

    private final PaperEventRepository paperEventRepository;
    private final Event<PaperChangedEvent> paperChanges;

    public PaperEventService(PaperEventRepository paperEventRepository, Event<PaperChangedEvent> paperChanges) {
        this.paperEventRepository = paperEventRepository;
        this.paperChanges = paperChanges;
    }

    public void log(Paper paper, String type, String details) {
        PaperEvent event = new PaperEvent();
        event.paper = paper;
        event.type = type;
        event.details = details;
        event.happenedAt = Instant.now();
        if (LOCAL_CHANGE_TYPES.contains(type)) {
            paper.updatedAt = event.happenedAt;
        }
        paperEventRepository.persist(event);
        if (paper.logicalFeed != null && paper.logicalFeed.id != null) {
            paperChanges.fire(new PaperChangedEvent(paper.logicalFeed.id, type));
        }
    }

    static Instant backfilledUpdatedAt(Paper paper, java.util.List<PaperEvent> events) {
        Instant latest = paper.discoveredAt;
        if (events != null) {
            for (PaperEvent event : events) {
                if (event != null && LOCAL_CHANGE_TYPES.contains(event.type) && event.happenedAt != null
                        && (latest == null || event.happenedAt.isAfter(latest))) {
                    latest = event.happenedAt;
                }
            }
        }
        return latest == null ? Instant.now() : latest;
    }
}
