package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewReportServiceTest {

    private final ReviewReportService service = new ReviewReportService(
            null,
            "http://localhost:8080",
            "http://localhost:8091",
            "");

    @Test
    void rendersFreeTextDimensionsByCitedPaper() {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("paper_id", "7643");
        instance.put("paper_class", "evaluation_research");
        instance.put("summary", "First line\nSecond line");
        Map<String, Object> schemaStats = Map.of("fields", List.of(Map.of(
                "id", "summary",
                "label", "Summary",
                "closed_list", false,
                "present_count", 1,
                "value_counts", Map.of())));
        List<Map<String, Object>> reviewedItems = List.of(Map.of(
                "paper_id", "7643",
                "title", "A paper title",
                "instance", instance));

        String markdown = service.renderResultsMarkdown(schemaStats, reviewedItems);

        assertTrue(markdown.contains("## Results"));
        assertTrue(markdown.contains("### Summary"));
        assertTrue(markdown.contains("#### A paper title [1]"));
        assertTrue(markdown.contains("First line\nSecond line"));
        assertFalse(markdown.contains("| Field | Value |"));
    }

    @Test
    void rendersClosedListsWithEveryOptionRatioCountAndPieChart() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("Accepted", 2);
        counts.put("Rejected", 1);
        counts.put("Undecided", 0);
        Map<String, Object> schemaStats = Map.of("fields", List.of(Map.of(
                "id", "decision",
                "label", "Decision",
                "closed_list", true,
                "cardinality", "single",
                "present_count", 3,
                "value_counts", counts)));

        String markdown = service.renderResultsMarkdown(schemaStats, List.of());

        assertTrue(markdown.contains("| Option | Ratio | Count |"));
        assertTrue(markdown.contains("| Accepted | 66.7% | 2 |"));
        assertTrue(markdown.contains("| Rejected | 33.3% | 1 |"));
        assertTrue(markdown.contains("| Undecided | 0.0% | 0 |"));
        assertTrue(markdown.contains("![Pie chart for Decision](data:image/png;base64,"));
    }

    @Test
    void rendersPaperReferenceAsBibtex() {
        Map<String, Object> paper = new LinkedHashMap<>();
        paper.put("paper_id", "42");
        paper.put("title", "A {useful} paper");
        paper.put("authors", List.of("Ada Lovelace", "Grace Hopper"));
        paper.put("published_on", "2026-03-10");
        paper.put("venue", "Journal of Tests");
        paper.put("doi", "10.1000/example");
        paper.put("source_link", "https://doi.org/10.1000/example");

        String bibtex = service.renderBibtex(paper);

        assertTrue(bibtex.startsWith("@article{paper42,"));
        assertTrue(bibtex.contains("author = {Ada Lovelace and Grace Hopper}"));
        assertTrue(bibtex.contains("year = {2026}"));
        assertTrue(bibtex.contains("doi = {10.1000/example}"));
    }

    @Test
    void rendersSelectedStatesAsBulletsAndReferencesAtTheEnd() {
        Map<String, Object> reviewedPaper = new LinkedHashMap<>();
        reviewedPaper.put("paper_id", "42");
        reviewedPaper.put("title", "A paper title");
        reviewedPaper.put("authors", List.of("Ada Lovelace"));
        reviewedPaper.put("published_on", "2026-03-10");
        reviewedPaper.put("source_link", "https://example.test/paper");
        reviewedPaper.put("instance", Map.of());
        Map<String, Object> report = Map.of(
                "review", Map.of(
                        "title", "Test review",
                        "logical_feed_name", "Test papers",
                        "taxonomy_title", "Test taxonomy",
                        "taxonomy_id", "test-taxonomy",
                        "taxonomy_link", "https://example.test/taxonomy",
                        "selected_states", List.of("SCREENING/SCREENED", "INCLUDED/IN_REVIEW")),
                "scope_stats", Map.of(
                        "total_in_live_scope", 1,
                        "reviewed", 1,
                        "remaining", 0,
                        "by_state", Map.of("INCLUDED", 1)),
                "paper_metadata_stats", Map.of(
                        "with_pdf", 0,
                        "with_notes", 0,
                        "by_year", Map.of("2026", 1),
                        "by_venue", Map.of()),
                "schema_stats", Map.of("fields", List.of()),
                "reviewed_items", List.of(reviewedPaper));

        String markdown = service.renderMarkdown(report);

        assertTrue(markdown.contains("- Selected states:\n  - SCREENING/SCREENED\n  - INCLUDED/IN\\_REVIEW\n"));
        assertTrue(markdown.contains("## References\n\n### [1] A paper title"));
        assertTrue(markdown.contains("```bibtex\n@misc{paper42,"));
        assertTrue(markdown.lastIndexOf("## References") > markdown.lastIndexOf("## Results"));
    }
}
