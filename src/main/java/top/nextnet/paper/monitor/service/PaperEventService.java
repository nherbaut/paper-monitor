package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PaperEvent;
import top.nextnet.paper.monitor.repo.PaperEventRepository;

@ApplicationScoped
public class PaperEventService {

    private final PaperEventRepository paperEventRepository;

    public PaperEventService(PaperEventRepository paperEventRepository) {
        this.paperEventRepository = paperEventRepository;
    }

    public void log(Paper paper, String type, String details) {
        PaperEvent event = new PaperEvent();
        event.paper = paper;
        event.type = type;
        event.details = details;
        event.happenedAt = Instant.now();
        paperEventRepository.persist(event);
    }
}
