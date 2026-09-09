package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PaperEvent;

class PaperEventServiceTest {
    @Test
    void backfillsFromTheLatestLocalChangeOnly() {
        Paper paper = new Paper();
        paper.discoveredAt = Instant.parse("2026-01-01T00:00:00Z");
        PaperEvent local = event("NOTES_CHANGED", "2026-02-01T00:00:00Z");
        PaperEvent remote = event("MENDELEY_PULL", "2026-03-01T00:00:00Z");
        PaperEvent viewed = event("NOTE_VIEWED", "2026-04-01T00:00:00Z");

        assertEquals(local.happenedAt,
                PaperEventService.backfilledUpdatedAt(paper, List.of(local, remote, viewed)));
    }

    @Test
    void legacyPapersWithoutEventsUseTheirDiscoveryTime() {
        Paper paper = new Paper();
        paper.discoveredAt = Instant.parse("2026-01-01T00:00:00Z");

        assertNotNull(PaperEventService.backfilledUpdatedAt(paper, List.of()));
        assertEquals(paper.discoveredAt, PaperEventService.backfilledUpdatedAt(paper, List.of()));
    }

    private static PaperEvent event(String type, String happenedAt) {
        PaperEvent event = new PaperEvent();
        event.type = type;
        event.happenedAt = Instant.parse(happenedAt);
        return event;
    }
}
