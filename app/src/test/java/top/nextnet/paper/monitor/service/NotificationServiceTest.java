package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;

class NotificationServiceTest {

    @Test
    void rendersDailyDigestGroupedByRssFeed() {
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = "trust";
        Feed feed = feed("trust calibration");
        Paper first = paper(
                feed,
                "Trust Calibration for RF and Analog Mixed-Signal Systems: A Survey of Lightweight Hardware Security",
                "Bustana Teene, Ankit Mittal",
                "Proceedings of the IEEE VLSI Test Symposium",
                "2026-04-27",
                "https://doi.org/10.1109/vts69484.2026.11563395");
        Paper second = paper(
                feed,
                "The Role of Transparency in Takeover Information for Trust Calibration in Automated Driving Systems",
                "Xinze Liu, Lingyun Wan, Yan Ge",
                "Lecture Notes in Computer Science",
                "2026-01-01",
                "https://doi.org/10.1007/978-3-032-29459-3_2");

        NotificationService.RenderedDigest digest = NotificationService.renderRssDigest(
                logicalFeed, List.of(first, second), LocalDate.of(2026, 7, 4));

        assertEquals("2 new papers in trust", digest.subject());
        assertTrue(digest.text().contains("Daily update for 4 July 2026"));
        assertTrue(digest.text().contains("trust calibration"));
        assertTrue(digest.html().contains("2 new papers"));
        assertTrue(digest.html().contains("https://doi.org/10.1109/vts69484.2026.11563395"));
    }

    @Test
    void escapesMetadataAndDoesNotLinkUnsafeUrls() {
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = "R&D <review>";
        Paper paper = paper(feed("Source & reports"), "<script>alert('x')</script>",
                "A & B", "Research > Sales", "2026-01-01", "javascript:alert(1)");

        NotificationService.RenderedDigest digest = NotificationService.renderRssDigest(
                logicalFeed, List.of(paper), LocalDate.of(2026, 7, 4));

        assertEquals("1 new paper in R&D <review>", digest.subject());
        assertTrue(digest.html().contains("&lt;script&gt;"));
        assertTrue(digest.html().contains("R&amp;D &lt;review&gt;"));
        assertFalse(digest.html().contains("href=\"javascript:"));
    }

    @Test
    void rendersStateTransitionLinksForValidOutgoingStatesWithoutCriteria() {
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = "trust";
        logicalFeed.workflowStates = """
                version: 2
                initial_state: NEW
                states:
                - id: NEW
                  label: New
                - id: TODO
                  label: To review
                - id: DONE
                  label: Included
                  requires:
                    inclusion_criteria:
                      taxonomy: inclusion
                      min: 1
                transitions:
                - from: NEW
                  to:
                  - TODO
                  - DONE
                taxonomies:
                  inclusion:
                    label: Inclusion
                    values:
                    - id: inc1
                      label: Include
                """;
        Paper paper = paper(feed("trust calibration"), "A paper", "A. Author", "Venue", "2026-01-01", "https://doi.org/10/example");
        paper.id = 42L;

        NotificationService.RenderedDigest digest = NotificationService.renderRssDigest(
                logicalFeed, List.of(paper), LocalDate.of(2026, 7, 4), "https://papers.example.test/");

        assertTrue(digest.text().contains("To review: https://papers.example.test/papers/42/transition?status=TODO"));
        assertTrue(digest.html().contains("href=\"https://papers.example.test/papers/42/transition?status=TODO\""));
        assertFalse(digest.html().contains("status=DONE"));
    }

    private Feed feed(String name) {
        Feed feed = new Feed();
        feed.name = name;
        feed.url = "https://example.org/rss";
        return feed;
    }

    private Paper paper(
            Feed feed,
            String title,
            String authors,
            String publisher,
            String publishedOn,
            String sourceLink
    ) {
        Paper paper = new Paper();
        paper.feed = feed;
        paper.title = title;
        paper.status = "NEW";
        paper.authors = authors;
        paper.publisher = publisher;
        paper.publishedOn = LocalDate.parse(publishedOn);
        paper.sourceLink = sourceLink;
        return paper;
    }
}
