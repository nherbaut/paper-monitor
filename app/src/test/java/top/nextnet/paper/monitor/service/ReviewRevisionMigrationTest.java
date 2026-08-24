package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewRevisionMigrationTest {

    private final ReviewService service = new ReviewService(null, null, null, null, null);

    @Test
    void migratesResearchQuestionAnswersByStableKey() {
        Map<String, Object> oldDesign = Map.of("research_questions", List.of(
                question("question-a", "rq_1"),
                question("question-b", "rq_2")));
        Map<String, Object> newDesign = Map.of("research_questions", List.of(
                question("question-b", "rq_1"),
                question("question-a", "rq_2"),
                question("question-c", "rq_3")));
        Map<String, Object> paperClass = new LinkedHashMap<>(field("paper_class"));
        paperClass.put("values", List.of(Map.of(
                "id", "evaluation_research",
                "criteria", List.of(Map.of("id", "method_sound")),
                "children", List.of())));
        Map<String, Object> formSchema = Map.of("fields", List.of(
                paperClass, field("rq_1"), field("rq_2"), field("rq_3")));
        Map<String, Object> oldValues = new LinkedHashMap<>();
        oldValues.put("paper_class", "evaluation_research");
        oldValues.put("rq_1", "Answer A");
        oldValues.put("rq_2", "Answer B");
        oldValues.put("method_sound", 2);
        oldValues.put("removed_field", "Discard me");

        Map<String, Object> migrated = service.migrateSubmissionValues(
                oldDesign, newDesign, formSchema, oldValues);

        assertEquals("evaluation_research", migrated.get("paper_class"));
        assertEquals("Answer B", migrated.get("rq_1"));
        assertEquals("Answer A", migrated.get("rq_2"));
        assertEquals(2, migrated.get("method_sound"));
        assertFalse(migrated.containsKey("rq_3"));
        assertFalse(migrated.containsKey("removed_field"));
    }

    private Map<String, Object> question(String key, String slotId) {
        return Map.of("key", key, "slot_id", slotId);
    }

    private Map<String, Object> field(String id) {
        return Map.of("id", id, "subdimensions", List.of());
    }
}
