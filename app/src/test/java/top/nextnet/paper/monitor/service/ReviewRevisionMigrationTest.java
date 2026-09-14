package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.Review;

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

    @Test
    void preservesBaseDesignAnswersWhenCreatingFirstDerivation() {
        Map<String, Object> newDesign = Map.of("research_questions", List.of(
                question("question-a", "rq_1"),
                question("question-b", "rq_2")));
        Map<String, Object> formSchema = Map.of("fields", List.of(
                field("paper_class"), field("rq_1"), field("rq_2")));
        Map<String, Object> oldValues = Map.of(
                "paper_class", "evaluation_research",
                "rq_1", "Existing answer A",
                "rq_2", "Existing answer B",
                "rq_3", "Removed answer");

        Map<String, Object> migrated = service.migrateSubmissionValues(
                Map.of(), newDesign, formSchema, oldValues);

        assertEquals("evaluation_research", migrated.get("paper_class"));
        assertEquals("Existing answer A", migrated.get("rq_1"));
        assertEquals("Existing answer B", migrated.get("rq_2"));
        assertFalse(migrated.containsKey("rq_3"));
    }

    @Test
    void sanitizesAiDraftValuesWithoutRequiringACompleteForm() {
        Review review = new Review();
        review.formSchemaJson = JsonCodec.stringify(Map.of("fields", List.of(
                Map.of(
                        "id", "paper_class",
                        "required", true,
                        "cardinality", "single",
                        "values", List.of(Map.of("id", "research", "children", List.of())),
                        "subdimensions", List.of()),
                field("rq_1"))));

        Map<String, Object> sanitized = service.sanitizeDraftValues(review, Map.of(
                "paper_class", "invented",
                "rq_1", "Supported answer",
                "unknown", "Discard me"));

        assertEquals(Map.of("rq_1", "Supported answer"), sanitized);
    }

    @Test
    void normalizesNestedAiAnswersIntoFlatReviewValues() {
        Review review = new Review();
        review.formSchemaJson = JsonCodec.stringify(Map.of(
                "fields", List.of(
                        Map.of(
                                "id", "rq_1",
                                "value_type", "free_text",
                                "cardinality", "multiple",
                                "values", List.of(),
                                "subdimensions", List.of(
                                        categoryField("method", "multiple", Map.of(
                                                "survey", "Survey",
                                                "case_study", "Case study")),
                                        categoryField("relevance", "single", Map.of(
                                                "direct_oss", "Direct OSS evidence")),
                                        Map.of(
                                                "id", "findings",
                                                "value_type", "free_text",
                                                "cardinality", "multiple",
                                                "values", List.of(),
                                                "subdimensions", List.of()))),
                        appraisalField()),
                "scales", Map.of("evidence_support", Map.of(
                        "scale_type", "ordinal",
                        "scale_values", List.of(
                                Map.of("value", "0", "label", "Unsupported"),
                                Map.of("value", "1", "label", "Indirect"),
                                Map.of("value", "2", "label", "Direct"))))));

        Map<String, Object> normalized = service.sanitizeDraftValues(review, Map.of(
                "rq_1", List.of(
                        Map.of(
                                "method", List.of(Map.of("id", "survey")),
                                "relevance", Map.of("value", "Direct OSS evidence"),
                                "findings", List.of(Map.of("text", "Finding A"))),
                        Map.of(
                                "method", List.of("Case study"),
                                "findings", List.of(Map.of("evidence", "Finding B")))),
                "evidence_appraisal", List.of("methodological_fit"),
                "method_fit_supported", 2));

        assertFalse(normalized.containsKey("rq_1"));
        assertEquals(List.of("survey", "case_study"), normalized.get("method"));
        assertEquals("direct_oss", normalized.get("relevance"));
        assertEquals(List.of("Finding A", "Finding B"), normalized.get("findings"));
        assertEquals(List.of("methodological_fit"), normalized.get("evidence_appraisal"));
        assertEquals("2", normalized.get("method_fit_supported"));
    }

    @Test
    void rejectsObjectValuesForFreeTextFields() {
        Review review = new Review();
        review.formSchemaJson = JsonCodec.stringify(Map.of("fields", List.of(Map.of(
                "id", "findings",
                "value_type", "free_text",
                "cardinality", "multiple",
                "values", List.of(),
                "subdimensions", List.of()))));

        assertThrows(ReviewService.ReviewValidationException.class, () ->
                service.validateSubmission(review, Map.of("findings", List.of(Map.of("text", "Finding")))));
    }

    private Map<String, Object> question(String key, String slotId) {
        return Map.of("key", key, "slot_id", slotId);
    }

    private Map<String, Object> field(String id) {
        return Map.of("id", id, "subdimensions", List.of());
    }

    private Map<String, Object> categoryField(String id, String cardinality, Map<String, String> options) {
        return Map.of(
                "id", id,
                "value_type", "category",
                "cardinality", cardinality,
                "values", options.entrySet().stream()
                        .map(entry -> Map.<String, Object>of(
                                "id", entry.getKey(),
                                "label", entry.getValue(),
                                "children", List.of()))
                        .toList(),
                "subdimensions", List.of());
    }

    private Map<String, Object> appraisalField() {
        return Map.of(
                "id", "evidence_appraisal",
                "value_type", "category",
                "cardinality", "multiple",
                "values", List.of(Map.of(
                        "id", "methodological_fit",
                        "label", "Methodological fit",
                        "criteria", List.of(Map.of(
                                "id", "method_fit_supported",
                                "scale", "evidence_support")),
                        "children", List.of())),
                "subdimensions", List.of());
    }
}
