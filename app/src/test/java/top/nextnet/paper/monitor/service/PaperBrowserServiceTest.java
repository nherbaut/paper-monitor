package top.nextnet.paper.monitor.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;

@QuarkusTest
class PaperBrowserServiceTest {

    @Inject
    PaperBrowserService browser;

    @Test
    @Transactional
    void pagesWithStableCursorAndServerSideFilters() {
        String suffix = UUID.randomUUID().toString();
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = "Browser " + suffix;
        logicalFeed.workflowStates = QuickSetupWorkflows.KANBAN;
        logicalFeed.persist();

        Feed feed = new Feed();
        feed.name = "RSS " + suffix;
        feed.url = "https://example.invalid/" + suffix + ".rss";
        feed.logicalFeed = logicalFeed;
        feed.persist();

        for (int index = 0; index < 25; index++) {
            Paper paper = new Paper();
            paper.title = (index == 17 ? "Needle " : "Paper ") + index;
            paper.sourceLink = "https://example.invalid/paper/" + suffix + "/" + index;
            paper.status = index % 2 == 0 ? "NEW" : "TODO";
            paper.tags = index % 3 == 0 ? "Alpha\nShared" : "Shared";
            paper.publishedOn = index >= 22 ? null : LocalDate.of(2026, 1, 1).plusDays(index);
            paper.discoveredAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index);
            paper.feed = feed;
            paper.logicalFeed = logicalFeed;
            paper.persist();
        }

        PaperBrowserService.Query all = new PaperBrowserService.Query(null, List.of(), null, false, List.of());
        PaperBrowserService.Page first = browser.page(logicalFeed, all, null, 10);
        PaperBrowserService.Page second = browser.page(logicalFeed, all, first.nextCursor(), 10);
        PaperBrowserService.Page third = browser.page(logicalFeed, all, second.nextCursor(), 10);
        Assertions.assertEquals(10, first.items().size());
        Assertions.assertEquals(10, second.items().size());
        Assertions.assertEquals(5, third.items().size());
        Assertions.assertEquals(25, first.total());
        Assertions.assertEquals(-1, second.total());
        Assertions.assertNull(third.nextCursor());
        Set<Long> ids = new HashSet<>(first.items().stream().map((paper) -> paper.id).toList());
        Assertions.assertTrue(second.items().stream().map((paper) -> paper.id).noneMatch(ids::contains));
        ids.addAll(second.items().stream().map((paper) -> paper.id).toList());
        Assertions.assertTrue(third.items().stream().map((paper) -> paper.id).noneMatch(ids::contains));
        Assertions.assertTrue(third.items().stream().anyMatch((paper) -> paper.publishedOn == null));

        PaperBrowserService.Query filtered = new PaperBrowserService.Query(
                "TODO", List.of("Shared"), "needle date:>=2026-01 date:<2027", false, List.of());
        PaperBrowserService.Page match = browser.page(logicalFeed, filtered, null, 30);
        Assertions.assertEquals(1, match.total());
        Assertions.assertEquals("Needle 17", match.items().getFirst().title);
    }
}
