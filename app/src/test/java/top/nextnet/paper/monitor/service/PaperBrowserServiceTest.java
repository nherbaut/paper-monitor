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
import top.nextnet.paper.monitor.repo.PaperRepository;

@QuarkusTest
class PaperBrowserServiceTest {

    @Inject
    PaperBrowserService browser;

    @Inject
    PaperRepository paperRepository;

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
            if (index == 5) paper.uploadedPdfPath = "/tmp/browser-test.pdf";
            if (index == 7) paper.sourceLink = "https://arxiv.org/abs/" + suffix + index;
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
        Set<Long> ids = new HashSet<>(first.items().stream().map(PaperBrowserService.BrowserPaper::id).toList());
        Assertions.assertTrue(second.items().stream().map(PaperBrowserService.BrowserPaper::id).noneMatch(ids::contains));
        ids.addAll(second.items().stream().map(PaperBrowserService.BrowserPaper::id).toList());
        Assertions.assertTrue(third.items().stream().map(PaperBrowserService.BrowserPaper::id).noneMatch(ids::contains));
        Assertions.assertTrue(third.items().stream().anyMatch((paper) -> paper.publishedOn() == null));

        PaperBrowserService.Query filtered = new PaperBrowserService.Query(
                "TODO", List.of("Shared"), "needle date:>=2026-01 date:<2027", false, List.of());
        PaperBrowserService.Page match = browser.page(logicalFeed, filtered, null, 30);
        Assertions.assertEquals(1, match.total());
        Assertions.assertEquals("Needle 17", match.items().getFirst().title());

        List<PaperBrowserService.Facet> states = browser.stateFacets(logicalFeed);
        Assertions.assertEquals(13L, facetCount(states, "state:NEW"));
        Assertions.assertEquals(12L, facetCount(states, "state:TODO"));

        List<PaperBrowserService.Facet> facets = browser.facets(logicalFeed,
                new PaperBrowserService.Query(null, List.of("state:TODO"), null, false, List.of()));
        Assertions.assertEquals(12L, facetCount(facets, "Shared"));
        Assertions.assertEquals(4L, facetCount(facets, "Alpha"));
        Assertions.assertEquals(1L, facetCount(facets, "has-pdf"));
        Assertions.assertEquals(1L, facetCount(facets, "arxiv"));
        Assertions.assertEquals(13L, facetCount(facets, "state:NEW"));
        Assertions.assertEquals(12L, facetCount(facets, "state:TODO"));
    }

    @Test
    @Transactional
    void dailyDigestIncludesEveryPaperSourceWithinCheckpointWindow() {
        String suffix = UUID.randomUUID().toString();
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = "Digest " + suffix;
        logicalFeed.workflowStates = QuickSetupWorkflows.KANBAN;
        logicalFeed.persist();

        Feed manualFeed = new Feed();
        manualFeed.name = "Manual upload";
        manualFeed.url = "upload://logical-feed/" + suffix;
        manualFeed.logicalFeed = logicalFeed;
        manualFeed.persist();

        Instant checkpoint = Instant.parse("2026-10-18T06:00:00Z");
        Instant cutoff = checkpoint.plusSeconds(3600);
        Paper atCheckpoint = digestPaper(logicalFeed, manualFeed, suffix + "-old", checkpoint);
        Paper manualImport = digestPaper(logicalFeed, manualFeed, suffix + "-manual", checkpoint.plusSeconds(1));
        Paper atCutoff = digestPaper(logicalFeed, manualFeed, suffix + "-cutoff", cutoff);
        digestPaper(logicalFeed, manualFeed, suffix + "-future", cutoff.plusSeconds(1));

        List<Paper> papers = paperRepository.findNewPapersForDigest(logicalFeed, checkpoint, cutoff);

        Assertions.assertEquals(List.of(manualImport.id, atCutoff.id), papers.stream().map(paper -> paper.id).toList());
        Assertions.assertFalse(papers.stream().anyMatch(paper -> paper.id.equals(atCheckpoint.id)));
    }

    private Paper digestPaper(LogicalFeed logicalFeed, Feed feed, String suffix, Instant discoveredAt) {
        Paper paper = new Paper();
        paper.title = "Digest paper " + suffix;
        paper.sourceLink = "upload:" + suffix;
        paper.status = logicalFeed.initialPaperStatus();
        paper.discoveredAt = discoveredAt;
        paper.feed = feed;
        paper.logicalFeed = logicalFeed;
        paper.persist();
        return paper;
    }

    private long facetCount(List<PaperBrowserService.Facet> facets, String key) {
        return facets.stream().filter((facet) -> facet.key().equals(key)).findFirst().orElseThrow().count();
    }
}
