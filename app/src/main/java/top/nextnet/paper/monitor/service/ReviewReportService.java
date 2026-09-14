package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.Review;
import top.nextnet.paper.monitor.model.ReviewSubmission;

@ApplicationScoped
public class ReviewReportService {

    private final ReviewService reviewService;
    private final String paperMonitorBaseUrl;
    private final String paperDataExtractorBaseUrl;
    private static final String DEFAULT_PDE_BASE_URL = "http://localhost:8091";
    private static final Pattern DOI_PATTERN = Pattern.compile(
            "\\b10\\.\\d{4,9}/[-._;()/:A-Z0-9]+\\b",
            Pattern.CASE_INSENSITIVE);

    static {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    public ReviewReportService(
            ReviewService reviewService,
            @ConfigProperty(name = "paper-monitor.base-url", defaultValue = "http://localhost:8080") String paperMonitorBaseUrl,
            @ConfigProperty(name = "paper-monitor.pde.public-base-url", defaultValue = "") String paperDataExtractorPublicBaseUrl,
            @ConfigProperty(name = "paper-monitor.pde.base-url", defaultValue = "") String legacyPaperDataExtractorBaseUrl
    ) {
        this.reviewService = reviewService;
        this.paperMonitorBaseUrl = trimTrailingSlash(paperMonitorBaseUrl);
        this.paperDataExtractorBaseUrl = trimTrailingSlash(firstNonBlank(
                paperDataExtractorPublicBaseUrl,
                legacyPaperDataExtractorBaseUrl,
                DEFAULT_PDE_BASE_URL));
    }

    public Map<String, Object> aggregate(Review review) {
        List<Paper> liveScopePapers = reviewService.papersInLiveScope(review);
        Map<Long, ReviewSubmission> submissionsByPaperId = reviewService.completeSubmissionsByPaperId(review);
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
        return renderMarkdown(aggregate(review));
    }

    String renderMarkdown(Map<String, Object> report) {
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
        markdown.append("- Taxonomy in PDE: ").append(linkOrText(stringValue(reviewSummary.get("taxonomy_link")))).append("\n");
        markdown.append("- Selected states:\n");
        appendNestedBullets(markdown, stringList(reviewSummary.get("selected_states")));
        markdown.append("\n");

        markdown.append("## Scope Statistics\n\n");
        markdown.append("- Total papers in live scope: ").append(scopeStats.get("total_in_live_scope")).append("\n");
        markdown.append("- Reviewed: ").append(scopeStats.get("reviewed")).append("\n");
        markdown.append("- Remaining: ").append(scopeStats.get("remaining")).append("\n\n");
        markdown.append("### By State\n\n");
        appendKeyValueBullets(markdown, asIntegerMap(scopeStats.get("by_state")));

        markdown.append("## Paper Metadata Statistics\n\n");
        markdown.append("- Papers with PDF: ").append(metadataStats.get("with_pdf")).append("\n");
        markdown.append("- Papers with notes: ").append(metadataStats.get("with_notes")).append("\n\n");
        markdown.append("### By Year\n\n");
        appendKeyValueBullets(markdown, asIntegerMap(metadataStats.get("by_year")));
        markdown.append("### By Venue\n\n");
        appendKeyValueBullets(markdown, asIntegerMap(metadataStats.get("by_venue")));

        markdown.append(renderResultsMarkdown(schemaStats, reviewedItems));
        appendPaperNotes(markdown, reviewedItems);
        appendReferences(markdown, reviewedItems);

        return markdown.toString();
    }

    String renderResultsMarkdown(Map<String, Object> schemaStats, List<Map<String, Object>> reviewedItems) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("## Results\n\n");
        Map<String, Integer> citationNumbers = citationNumbers(reviewedItems);
        List<Map<String, Object>> fields = objectMapList(schemaStats.get("fields"));
        if (fields.isEmpty()) {
            markdown.append("No review fields are configured.\n\n");
            return markdown.toString();
        }
        for (Map<String, Object> field : fields) {
            String fieldId = stringValue(field.get("id"));
            String fieldLabel = firstNonBlank(stringValue(field.get("label")), fieldId);
            markdown.append("### ").append(escapeMarkdown(fieldLabel)).append("\n\n");
            if (booleanValue(field.get("closed_list"))) {
                appendClosedListResults(markdown, field);
            } else {
                appendOpenResults(markdown, fieldId, reviewedItems, citationNumbers);
            }
        }
        return markdown.toString();
    }

    private void appendClosedListResults(StringBuilder markdown, Map<String, Object> field) {
        int responseCount = integerValue(field.get("present_count"));
        Map<String, Integer> counts = asIntegerMap(field.get("value_counts"));
        markdown.append(responseCount).append(responseCount == 1 ? " paper responded." : " papers responded.").append("\n\n");
        if ("multiple".equals(stringValue(field.get("cardinality")))) {
            markdown.append("Ratios are based on papers with a response; multiple selections may total more than 100%.\n\n");
        }
        markdown.append("| Option | Ratio | Count |\n");
        markdown.append("| --- | ---: | ---: |\n");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            markdown.append("| ")
                    .append(escapeMarkdown(entry.getKey()))
                    .append(" | ")
                    .append(formatRatio(entry.getValue(), responseCount))
                    .append(" | ")
                    .append(entry.getValue())
                    .append(" |\n");
        }
        markdown.append("\n");
        String chart = pieChartDataUri(counts);
        if (chart != null) {
            markdown.append("![Pie chart for ")
                    .append(escapeMarkdown(stringValue(field.get("label"))))
                    .append("](")
                    .append(chart)
                    .append(")\n\n");
        }
    }

    private void appendOpenResults(
            StringBuilder markdown,
            String fieldId,
            List<Map<String, Object>> reviewedItems,
            Map<String, Integer> citationNumbers
    ) {
        boolean found = false;
        for (Map<String, Object> item : reviewedItems) {
            Object value = asObjectMap(item.get("instance")).get(fieldId);
            if (isMissing(value)) {
                continue;
            }
            found = true;
            markdown.append("#### ")
                    .append(escapeMarkdown(stringValue(item.get("title"))))
                    .append(" ")
                    .append(citation(item, citationNumbers))
                    .append("\n\n")
                    .append(escapeMarkdown(renderValue(value)))
                    .append("\n\n");
        }
        if (!found) {
            markdown.append("No responses.\n\n");
        }
    }

    private void appendPaperNotes(StringBuilder markdown, List<Map<String, Object>> reviewedItems) {
        List<Map<String, Object>> withNotes = reviewedItems.stream()
                .filter(item -> {
                    String notes = stringValue(item.get("notes"));
                    return notes != null && !notes.isBlank();
                })
                .toList();
        if (withNotes.isEmpty()) {
            return;
        }
        Map<String, Integer> citationNumbers = citationNumbers(reviewedItems);
        markdown.append("## Paper Notes\n\n");
        for (Map<String, Object> item : withNotes) {
            markdown.append("### ")
                    .append(escapeMarkdown(stringValue(item.get("title"))))
                    .append(" ")
                    .append(citation(item, citationNumbers))
                    .append("\n\n")
                    .append(stringValue(item.get("notes")))
                    .append("\n\n");
        }
    }

    private void appendReferences(StringBuilder markdown, List<Map<String, Object>> reviewedItems) {
        markdown.append("## References\n\n");
        if (reviewedItems.isEmpty()) {
            markdown.append("No reviewed papers.\n");
            return;
        }
        Map<String, Integer> citationNumbers = citationNumbers(reviewedItems);
        for (Map<String, Object> item : reviewedItems) {
            markdown.append("### ")
                    .append(citation(item, citationNumbers))
                    .append(" ")
                    .append(escapeMarkdown(stringValue(item.get("title"))))
                    .append("\n\n")
                    .append(renderFormattedReference(item))
                    .append("\n\n")
                    .append("```bibtex\n")
                    .append(renderBibtex(item))
                    .append("```\n\n");
        }
    }

    private Map<String, Integer> citationNumbers(List<Map<String, Object>> reviewedItems) {
        Map<String, Integer> citations = new LinkedHashMap<>();
        for (Map<String, Object> item : reviewedItems) {
            citations.put(stringValue(item.get("paper_id")), citations.size() + 1);
        }
        return citations;
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
        summary.put("taxonomy_link", paperDataExtractorBaseUrl + "/api/review-designs/" + review.templateId + "/download");
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
        item.put("open_access_link", paper.openAccessLink);
        item.put("doi", extractDoi(paper.sourceLink, paper.openAccessLink));
        item.put("paper_monitor_link", paperMonitorBaseUrl + "/?paperId=" + paper.id + "&logicalFeedId=" + paper.logicalFeed.id);
        if (paper.uploadedPdfPath != null && !paper.uploadedPdfPath.isBlank()) {
            item.put("pdf_link", paperMonitorBaseUrl + "/papers/" + paper.id + "/pdf?disposition=attachment");
        }
        item.put("notes", paper.notes);
        item.put("instance", instance);
        return item;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
        Map<String, Object> scales = asObjectMap(formSchema.get("scales"));
        for (Map<String, Object> field : objectMapList(formSchema.get("fields"))) {
            flattenField(field, fields, scales);
        }
        return fields;
    }

    private void flattenField(
            Map<String, Object> field,
            List<Map<String, Object>> fields,
            Map<String, Object> scales
    ) {
        fields.add(fieldDefinition(field));
        for (Map<String, Object> option : allOptions(objectMapList(field.get("values")))) {
            for (Map<String, Object> criterion : objectMapList(option.get("criteria"))) {
                fields.add(criterionDefinition(criterion, scales));
            }
        }
        for (Map<String, Object> subfield : objectMapList(field.get("subdimensions"))) {
            flattenField(subfield, fields, scales);
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

    private Map<String, Object> criterionDefinition(
            Map<String, Object> criterion,
            Map<String, Object> scales
    ) {
        Map<String, Object> definition = new LinkedHashMap<>();
        String scaleId = stringValue(criterion.get("scale"));
        Map<String, Object> scale = asObjectMap(scales.get(scaleId));
        definition.put("id", stringValue(criterion.get("id")));
        definition.put("label", firstNonBlank(stringValue(criterion.get("label")), stringValue(criterion.get("id"))));
        definition.put("kind", "criterion");
        definition.put("required", booleanValue(criterion.get("required")));
        definition.put("scale", scaleId);
        definition.put("value_type", stringValue(scale.get("scale_type")));
        definition.put("options", scaleOptionLabels(objectMapList(scale.get("scale_values"))));
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

    private Map<String, String> scaleOptionLabels(List<Map<String, Object>> values) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map<String, Object> value : values) {
            String id = stringValue(value.get("value"));
            if (id != null) {
                labels.put(id, firstNonBlank(stringValue(value.get("label")), id).replace('_', ' '));
            }
        }
        return labels;
    }

    private void increment(Map<String, Integer> target, String key) {
        target.merge(key, 1, Integer::sum);
    }

    private void appendKeyValueBullets(StringBuilder markdown, Map<String, Integer> values) {
        if (values.isEmpty()) {
            markdown.append("- None\n\n");
            return;
        }
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            markdown.append("- ")
                    .append(escapeMarkdown(entry.getKey()))
                    .append(": ")
                    .append(entry.getValue())
                    .append("\n");
        }
        markdown.append("\n");
    }

    private void appendNestedBullets(StringBuilder markdown, List<String> values) {
        if (values.isEmpty()) {
            markdown.append("  - None\n");
            return;
        }
        for (String value : values) {
            markdown.append("  - ").append(escapeMarkdown(value)).append("\n");
        }
    }

    private String formatRatio(int count, int responseCount) {
        if (responseCount <= 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", count * 100.0 / responseCount);
    }

    private String pieChartDataUri(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> slices = counts.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .toList();
        int height = Math.max(320, 90 + slices.size() * 24);
        BufferedImage image = new BufferedImage(760, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));

            int pieSize = 270;
            int pieX = 25;
            int pieY = Math.max(25, (height - pieSize) / 2);
            int total = slices.stream().mapToInt(Map.Entry::getValue).sum();
            if (total == 0) {
                graphics.setColor(new Color(225, 229, 235));
                graphics.fillOval(pieX, pieY, pieSize, pieSize);
                graphics.setColor(new Color(75, 85, 99));
                graphics.drawString("No responses", pieX + 78, pieY + pieSize / 2);
            } else {
                int startAngle = 90;
                int allocatedAngle = 0;
                for (int index = 0; index < slices.size(); index++) {
                    Map.Entry<String, Integer> slice = slices.get(index);
                    int angle = index == slices.size() - 1
                            ? 360 - allocatedAngle
                            : Math.min(360 - allocatedAngle, (int) Math.round(slice.getValue() * 360.0 / total));
                    graphics.setColor(chartColor(index, slices.size()));
                    graphics.fillArc(pieX, pieY, pieSize, pieSize, startAngle, angle);
                    startAngle += angle;
                    allocatedAngle += angle;
                }
            }

            int legendX = 330;
            int legendY = 45;
            for (int index = 0; index < slices.size(); index++) {
                Map.Entry<String, Integer> slice = slices.get(index);
                graphics.setColor(chartColor(index, slices.size()));
                graphics.fillRoundRect(legendX, legendY - 13, 16, 16, 4, 4);
                graphics.setColor(new Color(31, 41, 55));
                graphics.drawString(shortenChartLabel(slice.getKey()) + " (" + slice.getValue() + ")", legendX + 26, legendY);
                legendY += 24;
            }
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                return null;
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Color chartColor(int index, int total) {
        float hue = total <= 1 ? 0.58f : (float) index / total;
        return Color.getHSBColor(hue, 0.62f, 0.82f);
    }

    private String shortenChartLabel(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 44 ? value : value.substring(0, 41) + "...";
    }

    private String citation(Map<String, Object> item, Map<String, Integer> citationNumbers) {
        Integer number = citationNumbers.get(stringValue(item.get("paper_id")));
        return "[" + (number == null ? "?" : number) + "]";
    }

    private String renderFormattedReference(Map<String, Object> item) {
        List<String> parts = new ArrayList<>();
        List<String> authors = stringList(item.get("authors"));
        if (!authors.isEmpty()) {
            parts.add(String.join(", ", authors));
        }
        String title = stringValue(item.get("title"));
        if (title != null && !title.isBlank()) {
            parts.add("“" + title + "”");
        }
        String venue = stringValue(item.get("venue"));
        if (venue != null && !venue.isBlank()) {
            parts.add(venue);
        }
        String publishedOn = stringValue(item.get("published_on"));
        if (publishedOn != null && publishedOn.length() >= 4) {
            parts.add(publishedOn.substring(0, 4));
        }
        String reference = escapeMarkdown(String.join(". ", parts));
        String doi = stringValue(item.get("doi"));
        if (doi != null && !doi.isBlank()) {
            reference += ". [https://doi.org/" + escapeMarkdown(doi) + "](<https://doi.org/" + doi + ">)";
        }
        return reference.endsWith(".") ? reference : reference + ".";
    }

    String renderBibtex(Map<String, Object> item) {
        String paperId = firstNonBlank(stringValue(item.get("paper_id")), "unknown");
        String venue = stringValue(item.get("venue"));
        List<String> fields = new ArrayList<>();
        addBibtexField(fields, "title", stringValue(item.get("title")));
        addBibtexField(fields, "author", String.join(" and ", stringList(item.get("authors"))));
        String publishedOn = stringValue(item.get("published_on"));
        if (publishedOn != null && publishedOn.length() >= 4) {
            addBibtexField(fields, "year", publishedOn.substring(0, 4));
        }
        addBibtexField(fields, "journal", venue);
        addBibtexField(fields, "doi", stringValue(item.get("doi")));
        addBibtexField(fields, "url", firstNonBlank(
                stringValue(item.get("source_link")),
                firstNonBlank(stringValue(item.get("open_access_link")), stringValue(item.get("paper_monitor_link")))));

        StringBuilder bibtex = new StringBuilder();
        bibtex.append(venue == null || venue.isBlank() ? "@misc" : "@article")
                .append("{paper")
                .append(paperId.replaceAll("[^A-Za-z0-9_-]", "_"))
                .append(",\n");
        for (int index = 0; index < fields.size(); index++) {
            bibtex.append("  ").append(fields.get(index));
            if (index < fields.size() - 1) {
                bibtex.append(",");
            }
            bibtex.append("\n");
        }
        return bibtex.append("}\n").toString();
    }

    private void addBibtexField(List<String> fields, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        fields.add(name + " = {" + escapeBibtex(value) + "}");
    }

    private String escapeBibtex(String value) {
        return value.replace("\\", "\\\\")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replaceAll("\\R+", " ")
                .trim();
    }

    private String extractDoi(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Matcher matcher = DOI_PATTERN.matcher(candidate);
            if (matcher.find()) {
                return matcher.group();
            }
        }
        return null;
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

    private int integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String firstNonBlank(String left, String right) {
        return left != null && !left.isBlank() ? left : right;
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

    private String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
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
            map.put("closed_list", !optionLabels.isEmpty());
            map.put("free_text", "free_text".equals(valueType));
            map.put("present_count", presentCount);
            Map<String, Integer> orderedCounts = new LinkedHashMap<>();
            for (String optionLabel : optionLabels.values()) {
                orderedCounts.putIfAbsent(optionLabel, 0);
            }
            for (Map.Entry<String, Integer> entry : valueCounts.entrySet()) {
                orderedCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            map.put("value_counts", orderedCounts);
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
