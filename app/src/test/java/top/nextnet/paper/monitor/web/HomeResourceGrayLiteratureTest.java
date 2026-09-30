package top.nextnet.paper.monitor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;

class HomeResourceGrayLiteratureTest {

    @Test
    void canonicalizesHttpUrlsForDuplicateDetection() {
        assertEquals(
                "https://example.org/report?q=one",
                HomeResource.normalizeGrayLiteratureUrl(" HTTPS://Example.ORG:443/report?q=one#section "));
        assertEquals(
                "http://example.org/",
                HomeResource.normalizeGrayLiteratureUrl("http://Example.org:80"));
    }

    @Test
    void rejectsNonWebAndCredentialedUrls() {
        assertThrows(WebApplicationException.class,
                () -> HomeResource.normalizeGrayLiteratureUrl("file:///tmp/report.pdf"));
        assertThrows(WebApplicationException.class,
                () -> HomeResource.normalizeGrayLiteratureUrl("https://user:secret@example.org/report"));
    }

    @Test
    void detectsPapersStillInTheirPaperFeedIntakeState() {
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.workflowStates = """
                version: 2
                initial_state: NEW
                states:
                - id: NEW
                  label: New
                - id: TRIAGE
                  label: Triage
                transitions:
                - from: NEW
                  to:
                  - TRIAGE
        """;
        Feed feed = new Feed();
        feed.logicalFeed = logicalFeed;
        feed.url = "https://example.org/rss";

        Paper paper = new Paper();
        paper.feed = feed;
        paper.logicalFeed = logicalFeed;
        paper.status = "new";

        assertEquals(true, HomeResource.isPaperInRssIntakeState(paper));

        paper.status = "TRIAGE";
        assertEquals(false, HomeResource.isPaperInRssIntakeState(paper));
    }

    @Test
    void usesShortFeedBasedExportFileNames() {
        assertEquals("impact-of-genai-on-oss-projects",
                HomeResource.exportBaseFileName("Impact of GenAI on OSS projects", "report"));
        assertEquals("impact-of-genai-on-oss-projects-notes",
                HomeResource.exportBaseFileName("Impact of GenAI on OSS projects", "notes"));
        assertEquals("impact-of-genai-on-oss-projects-archive",
                HomeResource.exportBaseFileName("Impact of GenAI on OSS projects", "all"));
        assertTrue(HomeResource.exportBaseFileName("A".repeat(300), "all").length() <= 80);
    }
}
