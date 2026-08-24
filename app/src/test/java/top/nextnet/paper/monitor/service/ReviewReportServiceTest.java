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
    void rendersFreeTextDimensionsOutsideTheValueTable() {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("paper_id", "7643");
        instance.put("paper_class", "evaluation_research");
        instance.put("summary", "First line\nSecond line");
        Map<String, Object> schema = Map.of(
                "scales", Map.of(),
                "fields", List.of(
                        field("paper_class", "category"),
                        field("summary", "free_text")));

        String markdown = service.renderInstanceMarkdown(instance, schema);

        assertTrue(markdown.contains("| `paper_id` | 7643 |"));
        assertTrue(markdown.contains("| `paper_class` | evaluation\\_research |"));
        assertFalse(markdown.contains("| `summary` |"));
        assertTrue(markdown.contains("#### `summary`\n\nFirst line\nSecond line\n"));
    }

    @Test
    void rendersCriteriaUsingAFreeTextScaleOutsideTheValueTable() {
        Map<String, Object> freeTextCriterion = Map.of(
                "id", "rq_1",
                "label", "Research question 1",
                "scale", "free_text_scale");
        Map<String, Object> scoredCriterion = Map.of(
                "id", "quality_score",
                "label", "Quality score",
                "scale", "ordinal_scale");
        Map<String, Object> option = Map.of(
                "id", "evaluation_research",
                "criteria", List.of(freeTextCriterion, scoredCriterion),
                "children", List.of());
        Map<String, Object> paperClass = new LinkedHashMap<>(field("paper_class", "category"));
        paperClass.put("values", List.of(option));
        Map<String, Object> schema = Map.of(
                "scales", Map.of(
                        "free_text_scale", Map.of("scale_type", "free_text"),
                        "ordinal_scale", Map.of("scale_type", "ordinal")),
                "fields", List.of(paperClass));
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("paper_class", "evaluation_research");
        instance.put("quality_score", 2);
        instance.put("rq_1", "A long answer\r\ncontinued here");

        String markdown = service.renderInstanceMarkdown(instance, schema);

        assertTrue(markdown.contains("| `quality_score` | 2 |"));
        assertFalse(markdown.contains("| `rq_1` |"));
        assertTrue(markdown.contains("#### `rq_1`\n\nA long answer\r\ncontinued here\n"));
    }

    private Map<String, Object> field(String id, String valueType) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", id);
        field.put("value_type", valueType);
        field.put("values", List.of());
        field.put("subdimensions", List.of());
        return field;
    }
}
