package top.nextnet.paper.monitor.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;

final class PaperMarkdownFrontMatter {

    static final String SCHEMA = "paper-monitor/paper/v1";

    private static final Pattern MANAGED_FRONT_MATTER = Pattern.compile(
            "\\A---\\R(?<yaml>.*?)\\R---(?:\\R\\R|\\R|\\z)",
            Pattern.DOTALL);
    private static final Pattern SCHEMA_MARKER = Pattern.compile(
            "(?m)^schema:\\s*[\"']?" + Pattern.quote(SCHEMA) + "[\"']?\\s*$");
    private static final Pattern DOI_PATTERN = Pattern.compile(
            "\\b10\\.\\d{4,9}/[-._;()/:A-Z0-9]+\\b",
            Pattern.CASE_INSENSITIVE);

    private PaperMarkdownFrontMatter() {
    }

    static String render(Paper paper, String pdfFileName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schema", SCHEMA);
        metadata.put("paper_id", paper.id);
        metadata.put("bibliography", bibliography(paper));
        metadata.put("links", links(paper));
        metadata.put("source", source(paper));
        metadata.put("workflow", workflow(paper));
        metadata.put("files", files(paper, pdfFileName));

        String notes = paper.notes == null ? "" : paper.notes;
        return "---\n" + yaml().dump(metadata) + "---\n\n" + notes;
    }

    static String extractNotes(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return markdown == null ? "" : markdown;
        }
        Matcher frontMatter = MANAGED_FRONT_MATTER.matcher(markdown);
        if (!frontMatter.find() || !SCHEMA_MARKER.matcher(frontMatter.group("yaml")).find()) {
            return markdown;
        }
        return markdown.substring(frontMatter.end());
    }

    private static Map<String, Object> bibliography(Paper paper) {
        Map<String, Object> bibliography = new LinkedHashMap<>();
        bibliography.put("title", paper.title);
        bibliography.put("authors", paper.authors);
        bibliography.put("abstract", paper.summary);
        bibliography.put("publisher", paper.publisher);
        bibliography.put("published_on", paper.publishedOn == null ? null : paper.publishedOn.toString());
        bibliography.put("doi", extractDoi(paper.sourceLink, paper.openAccessLink));
        return bibliography;
    }

    private static Map<String, Object> links(Paper paper) {
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("source", paper.sourceLink);
        links.put("open_access", paper.openAccessLink);
        return links;
    }

    private static Map<String, Object> source(Paper paper) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("logical_feed_id", paper.logicalFeed == null ? null : paper.logicalFeed.id);
        source.put("logical_feed_name", paper.logicalFeed == null ? null : paper.logicalFeed.name);
        source.put("feed_id", paper.feed == null ? null : paper.feed.id);
        source.put("feed_name", paper.feed == null ? null : paper.feed.name);
        source.put("discovered_at", paper.discoveredAt == null ? null : paper.discoveredAt.toString());
        return source;
    }

    private static Map<String, Object> workflow(Paper paper) {
        LogicalFeed logicalFeed = paper.logicalFeed;
        WorkflowStateConfig workflow = logicalFeed == null ? null : logicalFeed.workflowConfig();
        WorkflowStateConfig.State state = workflow == null
                ? null
                : workflow.states().stream()
                        .filter((candidate) -> candidate.id().equals(paper.status))
                        .findFirst()
                        .orElse(null);

        Map<String, Object> stateMetadata = new LinkedHashMap<>();
        stateMetadata.put("id", paper.status);
        stateMetadata.put("label", state == null ? null : state.label());
        stateMetadata.put("prisma_bucket",
                state == null || state.report() == null ? null : state.report().prismaBucket());

        Map<String, Object> workflowMetadata = new LinkedHashMap<>();
        workflowMetadata.put("state", stateMetadata);
        workflowMetadata.put("tags", paper.tagList());
        workflowMetadata.put("eligibility", eligibility(paper, logicalFeed));
        return workflowMetadata;
    }

    private static Map<String, Object> eligibility(Paper paper, LogicalFeed logicalFeed) {
        Map<String, Object> eligibility = new LinkedHashMap<>();
        if (paper.eligibilityExclusionCriterionId == null) {
            eligibility.put("exclusion", null);
        } else {
            Map<String, Object> criterion = criterion(
                    paper.eligibilityExclusionCriterionId,
                    taxonomy(logicalFeed == null ? null : logicalFeed.eligibilityExclusionTaxonomy,
                            "EXCLUSION", "Eligibility exclusion criteria"));
            Map<String, Object> exclusion = new LinkedHashMap<>();
            exclusion.put("criterion", criterion);
            exclusion.put("notes", paper.eligibilityExclusionNotes);
            eligibility.put("exclusion", exclusion);
        }

        List<Map<String, Object>> inclusionCriteria = new ArrayList<>();
        WorkflowStateConfig.Taxonomy inclusionTaxonomy = taxonomy(
                logicalFeed == null ? null : logicalFeed.eligibilityInclusionTaxonomy,
                "INCLUSION",
                "Eligibility inclusion criteria");
        for (String criterionId : paper.eligibilityInclusionCriteriaIds()) {
            inclusionCriteria.add(criterion(criterionId, inclusionTaxonomy));
        }
        Map<String, Object> inclusion = new LinkedHashMap<>();
        inclusion.put("criteria", inclusionCriteria);
        eligibility.put("inclusion", inclusion);
        return eligibility;
    }

    private static Map<String, Object> criterion(String id, WorkflowStateConfig.Taxonomy taxonomy) {
        Map<String, Object> criterion = new LinkedHashMap<>();
        criterion.put("id", id);
        criterion.put("label", criterionLabel(taxonomy == null ? List.of() : taxonomy.values(), id));
        return criterion;
    }

    private static String criterionLabel(List<WorkflowStateConfig.Criterion> criteria, String id) {
        for (WorkflowStateConfig.Criterion criterion : criteria) {
            if (criterion.id().equals(id)) {
                return criterion.label();
            }
            String childLabel = criterionLabel(criterion.children(), id);
            if (childLabel != null) {
                return childLabel;
            }
        }
        return null;
    }

    private static WorkflowStateConfig.Taxonomy taxonomy(String yaml, String id, String label) {
        if (yaml == null || yaml.isBlank()) {
            return null;
        }
        return WorkflowStateConfig.standaloneTaxonomy(yaml, id, label);
    }

    private static Map<String, Object> files(Paper paper, String pdfFileName) {
        boolean pdfAvailable = pdfFileName != null;
        Map<String, Object> pdf = new LinkedHashMap<>();
        pdf.put("available", pdfAvailable);
        pdf.put("name", pdfAvailable ? pdfFileName : null);
        pdf.put("original_name", pdfAvailable ? paper.uploadedPdfFileName : null);

        Map<String, Object> files = new LinkedHashMap<>();
        files.put("pdf", pdf);
        return files;
    }

    private static String extractDoi(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Matcher matcher = DOI_PATTERN.matcher(candidate);
            if (matcher.find()) {
                return matcher.group().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }

    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setWidth(120);
        return new Yaml(options);
    }
}
