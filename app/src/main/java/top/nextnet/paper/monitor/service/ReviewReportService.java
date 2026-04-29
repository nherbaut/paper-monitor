package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.Review;
import top.nextnet.paper.monitor.model.ReviewSubmission;

@ApplicationScoped
public class ReviewReportService {

    private final ReviewService reviewService;

    public ReviewReportService(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    public Map<String, Object> aggregate(Review review) {
        List<Paper> liveScopePapers = reviewService.papersInLiveScope(review);
        Map<Long, ReviewSubmission> submissionsByPaperId = reviewService.submissionsByPaperId(review);
        Map<String, Object> formSchema = reviewService.formSchema(review);

        int reviewedCount = 0;
        List<Map<String, Object>> reviewedItems = new ArrayList<>();
        Map<String, Integer> stateCounts = new LinkedHashMap<>();
        Map<String, Integer> yearCounts = new LinkedHashMap<>();
        Map<String, Integer> venueCounts = new LinkedHashMap<>();
        int papersWithPdf = 0;
        int papersWithNotes = 0;

        List<Map<String, Object>> fieldDefinitions = flattenFieldDefinitions(formSchema);
        Map<String, FieldAccumulator> fieldAccumulators = new LinkedHashMap<>();
        for (Map<String, Object> field : fieldDefinitions) {
            String fieldId = stringValue(field.get("id"));
            if (fieldId != null) {
                fieldAccumulators.put(fieldId, new FieldAccumulator(field));
            }
        }

        for (Paper paper : liveScopePapers) {
            increment(stateCounts, paper.topLevelStatus());
            increment(yearCounts, paper.publishedOn == null ? "Unknown" : String.valueOf(paper.publishedOn.getYear()));
            increment(venueCounts, normalizeBucket(paper.publisher, "Unknown venue"));
            if (paper.uploadedPdfPath != null && !paper.uploadedPdfPath.isBlank()) {
                papersWithPdf += 1;
            }
            if (paper.notes != null && !paper.notes.isBlank()) {
                papersWithNotes += 1;
            }

            ReviewSubmission submission = submissionsByPaperId.get(paper.id);
            if (submission == null) {
                continue;
            }
            reviewedCount += 1;

            Map<String, Object> instance = reviewService.submissionInstance(submission);
            reviewedItems.add(reviewedItem(paper, instance));
            accumulateFieldStats(fieldAccumulators, instance, formSchema);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("review", reviewSummary(review, formSchema));
        report.put("scope_stats", scopeStats(reviewedCount, liveScopePapers.size(), stateCounts));
        report.put("paper_metadata_stats", paperMetadataStats(liveScopePapers.size(), papersWithPdf, papersWithNotes, yearCounts, venueCounts));
        report.put("schema_stats", schemaStats(fieldAccumulators.values()));
        report.put("reviewed_items", reviewedItems);
        return report;
    }

    public String renderMarkdown(Review review) {
        Map<String, Object> report = aggregate(review);
        Map<String, Object> reviewSummary = asObjectMap(report.get("review"));
        Map<String, Object> scopeStats = asObjectMap(report.get("scope_stats"));
        Map<String, Object> metadataStats = asObjectMap(report.get("paper_metadata_stats"));
        Map<String, Object> schemaStats = asObjectMap(report.get("schema_stats"));
        List<Map<String, Object>> reviewedItems = objectMapList(report.get("reviewed_items"));

        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(escapeMarkdown(stringValue(reviewSummary.get("title")))).append("\n\n");
        markdown.append("## Review Summary\n\n");
        markdown.append("- Paper feed: ").append(escapeMarkdown(stringValue(reviewSummary.get("logical_feed_name")))).append("\n");
        markdown.append("- Taxonomy: ").append(escapeMarkdown(stringValue(reviewSummary.get("taxonomy_title")))).append("\n");
        markdown.append("- Taxonomy id: `").append(escapeCode(stringValue(reviewSummary.get("taxonomy_id")))).append("`\n");
        markdown.append("- Selected states: ").append(joinStrings(stringList(reviewSummary.get("selected_states")))).append("\n\n");

        markdown.append("## Scope Statistics\n\n");
        markdown.append("- Total papers in live scope: ").append(scopeStats.get("total_in_live_scope")).append("\n");
        markdown.append("- Reviewed: ").append(scopeStats.get("reviewed")).append("\n");
        markdown.append("- Remaining: ").append(scopeStats.get("remaining")).append("\n\n");
        markdown.append("### By State\n\n");
        appendKeyValueTable(markdown, asIntegerMap(scopeStats.get("by_state")), "State", "Count");

        markdown.append("## Paper Metadata Statistics\n\n");
        markdown.append("- Papers with PDF: ").append(metadataStats.get("with_pdf")).append("\n");
        markdown.append("- Papers with notes: ").append(metadataStats.get("with_notes")).append("\n\n");
        markdown.append("### By Year\n\n");
        appendKeyValueTable(markdown, asIntegerMap(metadataStats.get("by_year")), "Year", "Count");
        markdown.append("### By Venue\n\n");
        appendKeyValueTable(markdown, asIntegerMap(metadataStats.get("by_venue")), "Venue", "Count");

        markdown.append("## Schema Statistics\n\n");
        markdown.append("| Field | Kind | Present count | Value counts |\n");
        markdown.append("| --- | --- | ---: | --- |\n");
        for (Map<String, Object> field : objectMapList(schemaStats.get("fields"))) {
            markdown.append("| ")
                    .append(escapeMarkdown(stringValue(field.get("label"))))
                    .append(" | ")
                    .append(escapeMarkdown(stringValue(field.get("kind"))))
                    .append(" | ")
                    .append(stringValue(field.get("present_count")))
                    .append(" | ")
                    .append(escapeMarkdown(joinValueCounts(asIntegerMap(field.get("value_counts")))))
                    .append(" |\n");
        }
        markdown.append("\n");

        markdown.append("## Reviewed Items\n\n");
        for (Map<String, Object> item : reviewedItems) {
            markdown.append("### ").append(escapeMarkdown(stringValue(item.get("title")))).append("\n\n");
            markdown.append("- Paper id: `").append(escapeCode(stringValue(item.get("paper_id")))).append("`\n");
            markdown.append("- State: ").append(escapeMarkdown(stringValue(item.get("state")))).append("\n");
            markdown.append("- Authors: ").append(joinStrings(stringList(item.get("authors")))).append("\n");
            markdown.append("- Published on: ").append(escapeMarkdown(stringValue(item.get("published_on")))).append("\n");
            markdown.append("- Venue: ").append(escapeMarkdown(stringValue(item.get("venue")))).append("\n");
            markdown.append("- Source link: ").append(linkOrText(stringValue(item.get("source_link")))).append("\n\n");
            markdown.append("| Field | Value |\n");
            markdown.append("| --- | --- |\n");
            Map<String, Object> instance = asObjectMap(item.get("instance"));
            for (Map.Entry<String, Object> entry : instance.entrySet()) {
                markdown.append("| `")
                        .append(escapeCode(entry.getKey()))
                        .append("` | ")
                        .append(escapeMarkdown(renderValue(entry.getValue())))
                        .append(" |\n");
            }
            markdown.append("\n");
        }

        return markdown.toString();
    }

    private Map<String, Object> reviewSummary(Review review, Map<String, Object> formSchema) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", review.id);
        summary.put("title", review.title);
        summary.put("logical_feed_id", review.logicalFeed.id);
        summary.put("logical_feed_name", review.logicalFeed.name);
        summary.put("selected_states", reviewService.selectedStates(review));
        summary.put("taxonomy_id", stringValue(formSchema.get("id")));
        summary.put("taxonomy_title", stringValue(formSchema.get("title")));
        return summary;
    }

    private Map<String, Object> scopeStats(int reviewedCount, int totalCount, Map<String, Integer> stateCounts) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_in_live_scope", totalCount);
        stats.put("reviewed", reviewedCount);
        stats.put("remaining", Math.max(0, totalCount - reviewedCount));
        stats.put("by_state", stateCounts);
        return stats;
    }

    private Map<String, Object> paperMetadataStats(
            int totalCount,
            int papersWithPdf,
            int papersWithNotes,
            Map<String, Integer> yearCounts,
            Map<String, Integer> venueCounts
    ) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_papers", totalCount);
        stats.put("with_pdf", papersWithPdf);
        stats.put("with_notes", papersWithNotes);
        stats.put("by_year", yearCounts);
        stats.put("by_venue", venueCounts);
        return stats;
    }

    private Map<String, Object> schemaStats(Collection<FieldAccumulator> accumulators) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldAccumulator accumulator : accumulators) {
            fields.add(accumulator.toMap());
        }
        return Map.of("fields", fields);
    }

    private Map<String, Object> reviewedItem(Paper paper, Map<String, Object> instance) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("paper_id", String.valueOf(paper.id));
        item.put("title", paper.title);
        item.put("state", paper.topLevelStatus());
        item.put("authors", reviewService.paperSnapshot(paper).get("authors"));
        item.put("published_on", paper.publishedOn == null ? null : paper.publishedOn.toString());
        item.put("venue", paper.publisher);
        item.put("source_link", paper.sourceLink);
        item.put("instance", instance);
        return item;
    }

    private void accumulateFieldStats(
            Map<String, FieldAccumulator> accumulators,
            Map<String, Object> instance,
            Map<String, Object> formSchema
    ) {
        Map<String, Object> scales = asObjectMap(formSchema.get("scales"));
        for (Map.Entry<String, FieldAccumulator> entry : accumulators.entrySet()) {
            String fieldId = entry.getKey();
            FieldAccumulator accumulator = entry.getValue();
            Object rawValue = instance.get(fieldId);
            if (isMissing(rawValue)) {
                continue;
            }
            accumulator.presentCount += 1;
            for (String value : stringValues(rawValue)) {
                increment(accumulator.valueCounts, accumulator.displayValue(value, scales));
            }
        }
    }

    private List<Map<String, Object>> flattenFieldDefinitions(Map<String, Object> formSchema) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map<String, Object> field : objectMapList(formSchema.get("fields"))) {
            flattenField(field, fields);
        }
        return fields;
    }

    private void flattenField(Map<String, Object> field, List<Map<String, Object>> fields) {
        fields.add(fieldDefinition(field));
        for (Map<String, Object> option : allOptions(objectMapList(field.get("values")))) {
            for (Map<String, Object> criterion : objectMapList(option.get("criteria"))) {
                fields.add(criterionDefinition(criterion));
            }
        }
        for (Map<String, Object> subfield : objectMapList(field.get("subdimensions"))) {
            flattenField(subfield, fields);
        }
    }

    private Map<String, Object> fieldDefinition(Map<String, Object> field) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("id", stringValue(field.get("id")));
        definition.put("label", firstNonBlank(stringValue(field.get("label")), stringValue(field.get("id"))));
        definition.put("kind", "field");
        definition.put("required", booleanValue(field.get("required")));
        definition.put("cardinality", stringValue(field.get("cardinality")));
        definition.put("value_type", stringValue(field.get("value_type")));
        definition.put("options", optionLabels(objectMapList(field.get("values"))));
        return definition;
    }

    private Map<String, Object> criterionDefinition(Map<String, Object> criterion) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("id", stringValue(criterion.get("id")));
        definition.put("label", firstNonBlank(stringValue(criterion.get("label")), stringValue(criterion.get("id"))));
        definition.put("kind", "criterion");
        definition.put("required", booleanValue(criterion.get("required")));
        definition.put("scale", stringValue(criterion.get("scale")));
        return definition;
    }

    private List<Map<String, Object>> allOptions(List<Map<String, Object>> options) {
        List<Map<String, Object>> flattened = new ArrayList<>();
        for (Map<String, Object> option : options) {
            flattened.add(option);
            flattened.addAll(allOptions(objectMapList(option.get("children"))));
        }
        return flattened;
    }

    private Map<String, String> optionLabels(List<Map<String, Object>> options) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map<String, Object> option : allOptions(options)) {
            String optionId = stringValue(option.get("id"));
            if (optionId != null) {
                labels.put(optionId, firstNonBlank(stringValue(option.get("label")), optionId));
            }
        }
        return labels;
    }

    private void increment(Map<String, Integer> target, String key) {
        target.merge(key, 1, Integer::sum);
    }

    private void appendKeyValueTable(StringBuilder markdown, Map<String, Integer> values, String keyHeader, String valueHeader) {
        markdown.append("| ").append(keyHeader).append(" | ").append(valueHeader).append(" |\n");
        markdown.append("| --- | ---: |\n");
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            markdown.append("| ")
                    .append(escapeMarkdown(entry.getKey()))
                    .append(" | ")
                    .append(entry.getValue())
                    .append(" |\n");
        }
        markdown.append("\n");
    }

    private String normalizeBucket(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean isMissing(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String string) {
            return string.isBlank();
        }
        return value instanceof List<?> rows && rows.isEmpty();
    }

    private List<String> stringValues(Object rawValue) {
        if (rawValue instanceof List<?> rows) {
            List<String> values = new ArrayList<>();
            for (Object row : rows) {
                values.add(String.valueOf(row));
            }
            return values;
        }
        return List.of(String.valueOf(rawValue));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String left, String right) {
        return left != null && !left.isBlank() ? left : right;
    }

    private String joinStrings(List<String> values) {
        if (values.isEmpty()) {
            return "None";
        }
        return values.stream().map(this::escapeMarkdown).reduce((left, right) -> left + ", " + right).orElse("None");
    }

    private String joinValueCounts(Map<String, Integer> values) {
        if (values.isEmpty()) {
            return "None";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            parts.add(entry.getKey() + " (" + entry.getValue() + ")");
        }
        return String.join(", ", parts);
    }

    private String renderValue(Object value) {
        if (value instanceof List<?> rows) {
            List<String> parts = new ArrayList<>();
            for (Object row : rows) {
                parts.add(String.valueOf(row));
            }
            return String.join(", ", parts);
        }
        return String.valueOf(value);
    }

    private String linkOrText(String value) {
        if (value == null || value.isBlank()) {
            return "Unavailable";
        }
        return "[" + escapeMarkdown(value) + "](" + value + ")";
    }

    private String escapeMarkdown(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("#", "\\#")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String escapeCode(String value) {
        return value == null ? "" : value.replace("`", "\\`");
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> cast = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            cast.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return cast;
    }

    private List<Map<String, Object>> objectMapList(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?>) {
                result.add(asObjectMap(row));
            }
        }
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object row : rows) {
            result.add(String.valueOf(row));
        }
        return result;
    }

    private Map<String, Integer> asIntegerMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Integer> cast = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object raw = entry.getValue();
            if (raw instanceof Number number) {
                cast.put(String.valueOf(entry.getKey()), number.intValue());
            }
        }
        return cast;
    }

    private static final class FieldAccumulator {
        private final String id;
        private final String label;
        private final String kind;
        private final boolean required;
        private final String cardinality;
        private final String valueType;
        private final String scaleId;
        private final Map<String, String> optionLabels;
        private int presentCount;
        private final Map<String, Integer> valueCounts = new LinkedHashMap<>();

        private FieldAccumulator(Map<String, Object> definition) {
            this.id = String.valueOf(definition.get("id"));
            this.label = String.valueOf(definition.get("label"));
            this.kind = String.valueOf(definition.get("kind"));
            this.required = Boolean.TRUE.equals(definition.get("required"));
            this.cardinality = definition.get("cardinality") == null ? null : String.valueOf(definition.get("cardinality"));
            this.valueType = definition.get("value_type") == null ? null : String.valueOf(definition.get("value_type"));
            this.scaleId = definition.get("scale") == null ? null : String.valueOf(definition.get("scale"));
            this.optionLabels = new LinkedHashMap<>();
            if (definition.get("options") instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    optionLabels.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }

        private String displayValue(String rawValue, Map<String, Object> scales) {
            String optionLabel = optionLabels.get(rawValue);
            if (optionLabel != null) {
                return optionLabel;
            }
            if (scaleId != null) {
                Map<String, Object> scale = scales.get(scaleId) instanceof Map<?, ?> ? castMap(scales.get(scaleId)) : Map.of();
                for (Map<String, Object> scaleValue : castList(scale.get("scale_values"))) {
                    if (rawValue.equals(String.valueOf(scaleValue.get("value")))) {
                        String label = scaleValue.get("label") == null ? rawValue : String.valueOf(scaleValue.get("label"));
                        return humanize(label);
                    }
                }
            }
            return rawValue;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("label", label);
            map.put("kind", kind);
            map.put("required", required);
            if (cardinality != null) {
                map.put("cardinality", cardinality);
            }
            if (valueType != null) {
                map.put("value_type", valueType);
            }
            if (scaleId != null) {
                map.put("scale", scaleId);
            }
            map.put("present_count", presentCount);
            map.put("value_counts", valueCounts);
            return map;
        }

        private static String humanize(String value) {
            return value.replace('_', ' ');
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> castMap(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, Object> cast = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                cast.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return cast;
        }

        private static List<Map<String, Object>> castList(Object value) {
            if (!(value instanceof List<?> rows)) {
                return List.of();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?>) {
                    result.add(castMap(row));
                }
            }
            return result;
        }
    }
}
