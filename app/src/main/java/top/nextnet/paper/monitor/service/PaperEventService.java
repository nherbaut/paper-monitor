package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import java.time.Instant;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PaperEvent;
import top.nextnet.paper.monitor.repo.PaperEventRepository;

@ApplicationScoped
public class PaperEventService {

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
        paperEventRepository.persist(event);
        if (paper.logicalFeed != null && paper.logicalFeed.id != null) {
            paperChanges.fire(new PaperChangedEvent(paper.logicalFeed.id, type));
        }
    }
}
