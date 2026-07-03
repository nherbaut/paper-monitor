package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;

class PaperMarkdownFrontMatterTest {

    @Test
    void rendersMetadataAndKeepsNotesAsMarkdownBody() {
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.id = 3L;
        logicalFeed.name = "Review";
        logicalFeed.workflowStates = """
                version: 2
                initial_state: SCREENING/ELIGIBLE
                states:
                  - id: SCREENING/ELIGIBLE
                    label: Eligible
                    report:
                      prisma_bucket: database_assessed_for_eligibility
                transitions: []
                """;
        logicalFeed.eligibilityInclusionTaxonomy = """
                - id: EMPIRICAL
                  label: Empirical evidence
                """;

        Feed feed = new Feed();
        feed.id = 8L;
        feed.name = "Database search";

        Paper paper = new Paper();
        paper.id = 42L;
        paper.title = "A paper: with YAML-sensitive text";
        paper.sourceLink = "https://doi.org/10.1000/Example.42";
        paper.openAccessLink = "https://example.test/paper.pdf";
        paper.authors = "Alice Example; Bob Example";
        paper.summary = "First line\nSecond line";
        paper.publisher = "Example Press";
        paper.publishedOn = LocalDate.of(2026, 6, 1);
        paper.discoveredAt = Instant.parse("2026-06-02T10:15:30Z");
        paper.status = "SCREENING/ELIGIBLE";
        paper.notes = "# Notes\n\nEditable.";
        paper.uploadedPdfPath = "stored/file.pdf";
        paper.uploadedPdfFileName = "original.pdf";
        paper.feed = feed;
        paper.logicalFeed = logicalFeed;
        paper.setEligibilityInclusionCriteriaIds(List.of("EMPIRICAL"));

        String markdown = PaperMarkdownFrontMatter.render(paper, "paper-42--a-paper.pdf");
        int closingDelimiter = markdown.indexOf("\n---\n\n");
        @SuppressWarnings("unchecked")
        var metadata = (java.util.Map<String, Object>) new Yaml().load(markdown.substring(4, closingDelimiter));

        assertEquals(PaperMarkdownFrontMatter.SCHEMA, metadata.get("schema"));
        assertEquals(42L, ((Number) metadata.get("paper_id")).longValue());
        assertEquals(Paper.TYPE_PAPER, metadata.get("record_type"));
        @SuppressWarnings("unchecked")
        var bibliography = (java.util.Map<String, Object>) metadata.get("bibliography");
        @SuppressWarnings("unchecked")
        var source = (java.util.Map<String, Object>) metadata.get("source");
        assertEquals("2026-06-01", bibliography.get("published_on"));
        assertEquals("2026-06-02T10:15:30Z", source.get("discovered_at"));
        assertEquals(3L, ((Number) source.get("logical_feed_id")).longValue());
        assertEquals("Review", source.get("logical_feed_name"));
        assertTrue(!source.containsKey("feed_url"));
        assertTrue(markdown.contains("DATABASE_ASSESSED_FOR_ELIGIBILITY"));
        assertTrue(markdown.contains("Empirical evidence"));
        assertEquals("# Notes\n\nEditable.", PaperMarkdownFrontMatter.extractNotes(markdown));
    }

    @Test
    void preservesLegacyNotesWithoutManagedFrontMatter() {
        String notes = "---\ntitle: User-authored document\n---\n\nBody";

        assertEquals(notes, PaperMarkdownFrontMatter.extractNotes(notes));
    }

    @Test
    void stripsManagedFrontMatterEvenWhenMetadataWasEdited() {
        String markdown = """
                ---
                schema: "paper-monitor/paper/v1"
                paper_id: 99
                title: Changed in Git
                ---

                Body only
                """;

        assertEquals("Body only\n", PaperMarkdownFrontMatter.extractNotes(markdown));
    }
}
