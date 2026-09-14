package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.Review;
import top.nextnet.paper.monitor.model.ReviewSubmission;
import top.nextnet.paper.monitor.repo.PaperRepository;
import top.nextnet.paper.monitor.repo.ReviewRepository;
import top.nextnet.paper.monitor.repo.ReviewSubmissionRepository;

@ApplicationScoped
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewSubmissionRepository reviewSubmissionRepository;
    private final PaperRepository paperRepository;
    private final LogicalFeedAccessService logicalFeedAccessService;
    private final PaperDataExtractorService paperDataExtractorService;

    public ReviewService(
            ReviewRepository reviewRepository,
            ReviewSubmissionRepository reviewSubmissionRepository,
            PaperRepository paperRepository,
            LogicalFeedAccessService logicalFeedAccessService,
            PaperDataExtractorService paperDataExtractorService
    ) {
        this.reviewRepository = reviewRepository;
        this.reviewSubmissionRepository = reviewSubmissionRepository;
        this.paperRepository = paperRepository;
        this.logicalFeedAccessService = logicalFeedAccessService;
        this.paperDataExtractorService = paperDataExtractorService;
    }

    public List<Review> reviewsForOwner(AppUser owner) {
        return reviewRepository.findByOwner(owner);
    }

    public Optional<Review> reviewForPaper(AppUser owner, Paper paper) {
        if (owner == null || paper == null || paper.logicalFeed == null) {
            return Optional.empty();
        }
        return reviewRepository.findByOwnerAndLogicalFeed(owner, paper.logicalFeed)
                .filter(review -> {
                    List<String> states = selectedStates(review);
                    return states.contains(paper.status) || states.contains(paper.topLevelStatus());
                });
    }

    public Review requireReview(Long reviewId, AppUser owner) {
        return reviewRepository.findReadableById(reviewId, owner).orElseThrow(NotFoundException::new);
    }

    @Transactional
    public void deleteReview(Review review) {
        reviewSubmissionRepository.deleteByReview(review);
        review.delete();
    }

    @Transactional
    public void deleteReviewsForLogicalFeed(LogicalFeed logicalFeed) {
        if (logicalFeed == null) {
            return;
        }
        for (Review review : reviewRepository.findByLogicalFeed(logicalFeed)) {
            reviewSubmissionRepository.deleteByReview(review);
            review.delete();
        }
    }

    @Transactional
    public Review createOrReplaceReview(AppUser owner, LogicalFeed logicalFeed, List<String> selectedStates, String templateId) {
        if (!logicalFeedAccessService.canRead(logicalFeed, owner)) {
            throw new ForbiddenException();
        }
        List<String> normalizedStates = normalizeSelectedStates(logicalFeed, selectedStates);
        if (normalizedStates.isEmpty()) {
            throw new BadRequestException("Select at least one state");
        }
        String normalizedTemplateId = normalizeRequired(templateId, "Review template is required");
        PaperDataExtractorService.ReviewTemplateDetail template = paperDataExtractorService.loadReviewTemplate(normalizedTemplateId, owner);
        Map<String, Object> reviewDesign = template.reviewDesign();
        String title = logicalFeed.name + " review";

        Review review = reviewRepository.findByOwnerAndLogicalFeed(owner, logicalFeed).orElseGet(Review::new);
        review.owner = owner;
        review.logicalFeed = logicalFeed;
        review.selectedStatesJson = JsonCodec.stringify(normalizedStates);
        Instant now = Instant.now();
        review.updatedAt = now;
        if (review.createdAt == null) {
            review.title = title;
            review.templateId = normalizedTemplateId;
            review.templateTitle = title;
            review.reviewDesignJson = JsonCodec.stringify(reviewDesign);
            review.formSchemaJson = JsonCodec.stringify(template.formSchema());
            review.reviewJsonSchemaJson = JsonCodec.stringify(template.reviewJsonSchema());
            review.reviewLinkmlSchemaJson = JsonCodec.stringify(template.reviewLinkmlSchema());
            review.createdAt = now;
            reviewRepository.persist(review);
        } else if (!Objects.equals(review.templateId, normalizedTemplateId)) {
            activateRevision(review, template);
        }
        return review;
    }

    @Transactional
    public MigrationResult activateRevision(Review review, PaperDataExtractorService.ReviewTemplateDetail template) {
        Map<String, Object> oldDesign = asObjectMap(JsonCodec.parse(review.reviewDesignJson));
        Map<String, Object> newDesign = template.reviewDesign();

        review.title = firstNonBlank(stringValue(newDesign.get("title")), template.id());
        review.templateId = template.id();
        review.templateTitle = review.title;
        review.reviewDesignJson = JsonCodec.stringify(newDesign);
        review.formSchemaJson = JsonCodec.stringify(template.formSchema());
        review.reviewJsonSchemaJson = JsonCodec.stringify(template.reviewJsonSchema());
        review.reviewLinkmlSchemaJson = JsonCodec.stringify(template.reviewLinkmlSchema());
        review.updatedAt = Instant.now();

        int completed = 0;
        int drafts = 0;
        for (ReviewSubmission submission : reviewSubmissionRepository.findByReview(review)) {
            Map<String, Object> oldValues = submissionValues(submission);
            Map<String, Object> migrated = migrateSubmissionValues(
                    oldDesign, newDesign, template.formSchema(), oldValues);
            submission.payloadJson = JsonCodec.stringify(submissionInstance(review, submission.paper, migrated));
            submission.updatedAt = Instant.now();
            submission.complete = isValidSubmission(review, migrated);
            if (submission.complete) {
                completed++;
            } else {
                drafts++;
            }
        }
        return new MigrationResult(completed, drafts);
    }

    Map<String, Object> migrateSubmissionValues(
            Map<String, Object> oldDesign,
            Map<String, Object> newDesign,
            Map<String, Object> newFormSchema,
            Map<String, Object> oldValues
    ) {
        Map<String, String> oldSlotsByKey = researchQuestionSlotsByKey(oldDesign);
        Map<String, String> newSlotsByKey = researchQuestionSlotsByKey(newDesign);
        Set<String> oldRqSlots = new HashSet<>(oldSlotsByKey.values());
        Set<String> newFieldIds = new HashSet<>();
        for (Map<String, Object> field : objectMapList(newFormSchema.get("fields"))) {
            collectMigrationFieldIds(field, newFieldIds);
        }
        Map<String, Object> migrated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : oldValues.entrySet()) {
            if (!oldRqSlots.contains(entry.getKey()) && newFieldIds.contains(entry.getKey())) {
                migrated.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : oldSlotsByKey.entrySet()) {
            String newSlot = newSlotsByKey.get(entry.getKey());
            if (newSlot != null && oldValues.containsKey(entry.getValue())) {
                migrated.put(newSlot, oldValues.get(entry.getValue()));
            }
        }
        return migrated;
    }

    private void collectMigrationFieldIds(Map<String, Object> field, Set<String> fieldIds) {
        collectFieldIds(field, fieldIds);
        collectCriterionIds(objectMapList(field.get("values")), fieldIds);
        for (Map<String, Object> subfield : objectMapList(field.get("subdimensions"))) {
            collectMigrationFieldIds(subfield, fieldIds);
        }
    }

    private void collectCriterionIds(List<Map<String, Object>> options, Set<String> fieldIds) {
        for (Map<String, Object> option : options) {
            for (Map<String, Object> criterion : objectMapList(option.get("criteria"))) {
                String criterionId = stringValue(criterion.get("id"));
                if (criterionId != null) {
                    fieldIds.add(criterionId);
                }
            }
            collectCriterionIds(objectMapList(option.get("children")), fieldIds);
        }
    }

    public List<String> selectedStates(Review review) {
        Object parsed = JsonCodec.parse(review.selectedStatesJson);
        if (!(parsed instanceof List<?> rows)) {
            return List.of();
        }
        List<String> states = new ArrayList<>();
        for (Object row : rows) {
            if (row != null) {
                states.add(String.valueOf(row));
            }
        }
        return states;
    }

    public Map<String, Object> formSchema(Review review) {
        return asObjectMap(JsonCodec.parse(review.formSchemaJson));
    }

    public List<Paper> papersInLiveScope(Review review) {
        List<String> selectedStates = selectedStates(review);
        if (selectedStates.isEmpty()) {
            return List.of();
        }
        return paperRepository.findAllForExport(review.logicalFeed).stream()
                .filter((paper) -> selectedStates.contains(paper.status)
                        || selectedStates.contains(paper.topLevelStatus()))
                .toList();
    }

    public Map<Long, ReviewSubmission> submissionsByPaperId(Review review) {
        return reviewSubmissionRepository.findByReviewIndexedByPaperId(review);
    }

    public Map<Long, ReviewSubmission> completeSubmissionsByPaperId(Review review) {
        Map<Long, ReviewSubmission> submissions = new LinkedHashMap<>(submissionsByPaperId(review));
        submissions.entrySet().removeIf((entry) -> !entry.getValue().complete);
        return submissions;
    }

    public ReviewPaperContext requireReviewPaper(Review review, Long paperId) {
        Paper paper = paperRepository.findForReader(paperId).orElseThrow(NotFoundException::new);
        if (!Objects.equals(paper.logicalFeed.id, review.logicalFeed.id)) {
            throw new NotFoundException();
        }
        List<String> selectedStates = selectedStates(review);
        if (!selectedStates.contains(paper.status)
                && !selectedStates.contains(paper.topLevelStatus())) {
            throw new NotFoundException();
        }
        ReviewSubmission submission = reviewSubmissionRepository.findByReviewAndPaper(review, paper).orElse(null);
        return new ReviewPaperContext(paper, submission);
    }

    @Transactional
    public ReviewSubmission saveSubmission(Review review, Paper paper, Map<String, Object> values) {
        validateSubmission(review, values);
        ReviewSubmission submission = reviewSubmissionRepository.findByReviewAndPaper(review, paper).orElseGet(ReviewSubmission::new);
        submission.review = review;
        submission.paper = paper;
        submission.payloadJson = JsonCodec.stringify(submissionInstance(review, paper, values));
        submission.updatedAt = Instant.now();
        submission.complete = true;
        if (submission.id == null) {
            reviewSubmissionRepository.persist(submission);
        }
        return submission;
    }

    @Transactional
    public ReviewSubmission replaceWithDraft(Review review, Paper paper, Map<String, Object> proposedValues) {
        Map<String, Object> values = sanitizeDraftValues(review, proposedValues);
        ReviewSubmission submission = reviewSubmissionRepository.findByReviewAndPaper(review, paper)
                .orElseGet(ReviewSubmission::new);
        submission.review = review;
        submission.paper = paper;
        submission.payloadJson = JsonCodec.stringify(submissionInstance(review, paper, values));
        submission.updatedAt = Instant.now();
        submission.complete = false;
        if (submission.id == null) {
            reviewSubmissionRepository.persist(submission);
        }
        return submission;
    }

    public Map<String, Object> sanitizeDraftValues(Review review, Map<String, Object> proposedValues) {
        Map<String, Object> candidate = normalizeDraftValues(review, proposedValues);
        while (!candidate.isEmpty()) {
            try {
                validateSubmission(review, candidate);
                break;
            } catch (ReviewValidationException exception) {
                Set<String> invalidFields = new HashSet<>();
                for (ValidationError error : exception.errors()) {
                    if (!error.message().startsWith("Missing required")) {
                        invalidFields.add(error.fieldId());
                    }
                }
                if (invalidFields.isEmpty()) {
                    break;
                }
                invalidFields.forEach(candidate::remove);
            }
        }
        return candidate;
    }

    private Map<String, Object> normalizeDraftValues(Review review, Map<String, Object> proposedValues) {
        if (proposedValues == null || proposedValues.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> schema = formSchema(review);
        Map<String, Object> scales = asObjectMap(schema.get("scales"));
        Map<String, Map<String, Object>> fieldsById = new LinkedHashMap<>();
        for (Map<String, Object> field : objectMapList(schema.get("fields"))) {
            collectFieldsById(field, fieldsById, scales);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        collectProposedFieldValues(proposedValues, fieldsById, normalized);
        return normalized;
    }

    private void collectFieldsById(
            Map<String, Object> field,
            Map<String, Map<String, Object>> fieldsById,
            Map<String, Object> scales
    ) {
        String fieldId = stringValue(field.get("id"));
        if (fieldId != null) {
            fieldsById.put(fieldId, field);
        }
        collectCriterionFieldsById(objectMapList(field.get("values")), fieldsById, scales);
        for (Map<String, Object> subfield : objectMapList(field.get("subdimensions"))) {
            collectFieldsById(subfield, fieldsById, scales);
        }
    }

    private void collectCriterionFieldsById(
            List<Map<String, Object>> options,
            Map<String, Map<String, Object>> fieldsById,
            Map<String, Object> scales
    ) {
        for (Map<String, Object> option : options) {
            for (Map<String, Object> criterion : objectMapList(option.get("criteria"))) {
                String criterionId = stringValue(criterion.get("id"));
                if (criterionId != null) {
                    fieldsById.put(criterionId, criterionField(criterion, scales));
                }
            }
            collectCriterionFieldsById(objectMapList(option.get("children")), fieldsById, scales);
        }
    }

    private Map<String, Object> criterionField(Map<String, Object> criterion, Map<String, Object> scales) {
        Map<String, Object> field = new LinkedHashMap<>(criterion);
        field.put("cardinality", "single");
        Map<String, Object> scale = asObjectMap(scales.get(stringValue(criterion.get("scale"))));
        List<Map<String, Object>> scaleValues = objectMapList(scale.get("scale_values"));
        if (!scaleValues.isEmpty()) {
            List<Map<String, Object>> options = new ArrayList<>();
            for (Map<String, Object> scaleValue : scaleValues) {
                String value = stringValue(scaleValue.get("value"));
                if (value != null) {
                    options.add(Map.of(
                            "id", value,
                            "label", Objects.requireNonNullElse(stringValue(scaleValue.get("label")), value),
                            "children", List.of()));
                }
            }
            field.put("values", options);
        } else {
            field.put("value_type", "numeric".equals(stringValue(scale.get("scale_type")))
                    ? "numeric" : "free_text");
            field.put("values", List.of());
        }
        field.put("subdimensions", List.of());
        return field;
    }

    private void collectProposedFieldValues(
            Object value,
            Map<String, Map<String, Object>> fieldsById,
            Map<String, Object> normalized
    ) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Map<String, Object> field = fieldsById.get(key);
                if (field != null) {
                    mergeNormalizedFieldValue(normalized, field, normalizeFieldValue(field, entry.getValue()));
                }
                collectProposedFieldValues(entry.getValue(), fieldsById, normalized);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectProposedFieldValues(item, fieldsById, normalized);
            }
        }
    }

    private Object normalizeFieldValue(Map<String, Object> field, Object value) {
        boolean multiple = "multiple".equals(stringValue(field.get("cardinality")));
        List<Map<String, Object>> options = objectMapList(field.get("values"));
        if (!options.isEmpty()) {
            List<String> values = normalizedCategoryValues(value, options, multiple);
            return multiple ? values : values.stream().findFirst().orElse(null);
        }
        if (!objectMapList(field.get("subdimensions")).isEmpty() && containsStructuredObject(value)) {
            return null;
        }
        if ("numeric".equals(stringValue(field.get("value_type")))) {
            List<Number> values = normalizedNumericValues(value);
            return multiple ? values : values.stream().findFirst().orElse(null);
        }
        List<String> values = normalizedTextValues(value);
        if (multiple) {
            return values;
        }
        return values.isEmpty() ? null : String.join("\n", values);
    }

    private List<String> normalizedCategoryValues(
            Object value,
            List<Map<String, Object>> options,
            boolean multiple
    ) {
        Map<String, String> accepted = new LinkedHashMap<>();
        collectAcceptedCategoryValues(options, accepted);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectCategoryValues(value, accepted, values, multiple);
        return new ArrayList<>(values);
    }

    private void collectAcceptedCategoryValues(List<Map<String, Object>> options, Map<String, String> accepted) {
        for (Map<String, Object> option : options) {
            String id = stringValue(option.get("id"));
            String label = stringValue(option.get("label"));
            if (id != null) {
                accepted.put(id.toLowerCase(java.util.Locale.ROOT), id);
            }
            if (id != null && label != null) {
                accepted.putIfAbsent(label.trim().toLowerCase(java.util.Locale.ROOT), id);
            }
            collectAcceptedCategoryValues(objectMapList(option.get("children")), accepted);
        }
    }

    private void collectCategoryValues(
            Object value,
            Map<String, String> accepted,
            Set<String> result,
            boolean splitMultiple
    ) {
        if (value instanceof List<?> list) {
            list.forEach(item -> collectCategoryValues(item, accepted, result, splitMultiple));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("id", "value", "option_id", "optionId", "selected")) {
                if (map.containsKey(key)) {
                    collectCategoryValues(map.get(key), accepted, result, splitMultiple);
                }
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String acceptedId = accepted.get(String.valueOf(entry.getKey()).trim().toLowerCase(java.util.Locale.ROOT));
                if (acceptedId != null && isTruthy(entry.getValue())) {
                    result.add(acceptedId);
                }
            }
            return;
        }
        if (value == null) {
            return;
        }
        String candidate = String.valueOf(value).trim();
        String acceptedId = accepted.get(candidate.toLowerCase(java.util.Locale.ROOT));
        if (acceptedId != null) {
            result.add(acceptedId);
            return;
        }
        if (splitMultiple && (candidate.contains(",") || candidate.contains(";"))) {
            for (String item : candidate.split("[,;]")) {
                collectCategoryValues(item, accepted, result, false);
            }
        }
    }

    private List<Number> normalizedNumericValues(Object value) {
        List<Number> result = new ArrayList<>();
        collectNumericValues(value, result);
        return result;
    }

    private void collectNumericValues(Object value, List<Number> result) {
        if (value instanceof List<?> list) {
            list.forEach(item -> collectNumericValues(item, result));
        } else if (value instanceof Number number) {
            result.add(number);
        } else if (value instanceof String string) {
            try {
                result.add(Double.valueOf(string.trim()));
            } catch (NumberFormatException ignored) {
                // Unsupported AI values are omitted from the draft.
            }
        } else if (value instanceof Map<?, ?> map) {
            for (String key : List.of("value", "answer", "number")) {
                if (map.containsKey(key)) {
                    collectNumericValues(map.get(key), result);
                    return;
                }
            }
        }
    }

    private List<String> normalizedTextValues(Object value) {
        List<String> result = new ArrayList<>();
        collectTextValues(value, result);
        return result.stream().filter(item -> !item.isBlank()).distinct().toList();
    }

    private void collectTextValues(Object value, List<String> result) {
        if (value instanceof List<?> list) {
            list.forEach(item -> collectTextValues(item, result));
        } else if (value instanceof String string) {
            if (!string.isBlank()) {
                result.add(string.trim());
            }
        } else if (value instanceof Number || value instanceof Boolean) {
            result.add(String.valueOf(value));
        } else if (value instanceof Map<?, ?> map) {
            for (String key : List.of("text", "value", "answer", "summary", "finding", "evidence", "quote", "description")) {
                if (map.containsKey(key)) {
                    collectTextValues(map.get(key), result);
                    return;
                }
            }
            result.add(JsonCodec.stringify(map));
        }
    }

    private void mergeNormalizedFieldValue(
            Map<String, Object> normalized,
            Map<String, Object> field,
            Object value
    ) {
        if (isMissing(value)) {
            return;
        }
        String fieldId = stringValue(field.get("id"));
        if (!"multiple".equals(stringValue(field.get("cardinality")))) {
            normalized.putIfAbsent(fieldId, value);
            return;
        }
        List<Object> merged = new ArrayList<>();
        Object existing = normalized.get(fieldId);
        if (existing instanceof List<?> list) {
            merged.addAll(list);
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (!merged.contains(item)) {
                    merged.add(item);
                }
            }
        } else if (!merged.contains(value)) {
            merged.add(value);
        }
        if (!merged.isEmpty()) {
            normalized.put(fieldId, merged);
        }
    }

    private boolean containsStructuredObject(Object value) {
        if (value instanceof Map<?, ?>) {
            return true;
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(this::containsStructuredObject);
        }
        return false;
    }

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0D;
        }
        return value != null && !String.valueOf(value).isBlank()
                && !"false".equalsIgnoreCase(String.valueOf(value));
    }

    public void validateSubmission(Review review, Map<String, Object> values) {
        Map<String, Object> safeValues = values == null ? Map.of() : values;
        Map<String, Object> schema = formSchema(review);
        Map<String, Object> scales = asObjectMap(schema.get("scales"));
        ValidationContext context = new ValidationContext();
        for (Map<String, Object> field : objectMapList(schema.get("fields"))) {
            collectFieldIds(field, context.fieldIds);
            validateField(field, safeValues, scales, context);
        }
        for (String key : safeValues.keySet()) {
            if (!context.fieldIds.contains(key) && !context.activeCriterionIds.contains(key)) {
                context.errors.add(new ValidationError(key, "Unknown or inactive field"));
            }
        }
        if (!context.errors.isEmpty()) {
            throw new ReviewValidationException(context.errors);
        }
    }

    @Transactional
    public void resetSubmission(Review review, Paper paper) {
        reviewSubmissionRepository.findByReviewAndPaper(review, paper).ifPresent(ReviewSubmission::delete);
    }

    public Map<String, Object> submissionValues(ReviewSubmission submission) {
        if (submission == null || submission.payloadJson == null || submission.payloadJson.isBlank()) {
            return Map.of();
        }
        Map<String, Object> payload = submissionInstance(submission);
        if (payload.containsKey("values")) {
            return asObjectMap(payload.get("values"));
        }
        Map<String, Object> values = new LinkedHashMap<>(payload);
        values.remove("paper_id");
        values.remove("taxonomy_id");
        return values;
    }

    public Map<String, Object> submissionValues(Review review, Paper paper) {
        return reviewSubmissionRepository.findByReviewAndPaper(review, paper)
                .map(this::submissionValues)
                .orElse(Map.of());
    }

    public Map<String, Object> submissionInstance(ReviewSubmission submission) {
        if (submission == null || submission.payloadJson == null || submission.payloadJson.isBlank()) {
            return Map.of();
        }
        return asObjectMap(JsonCodec.parse(submission.payloadJson));
    }

    public Map<String, Object> submissionInstance(Review review, Paper paper, Map<String, Object> values) {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("paper_id", String.valueOf(paper.id));
        instance.put("taxonomy_id", schemaTaxonomyId(review));
        if (values != null) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                instance.put(entry.getKey(), entry.getValue());
            }
        }
        return instance;
    }

    public List<String> normalizeSelectedStates(LogicalFeed logicalFeed, List<String> selectedStates) {
        List<String> workflow = new ArrayList<>(logicalFeed.workflowStateList());
        for (String topLevelState : logicalFeed.topLevelWorkflowStateList()) {
            if (!workflow.contains(topLevelState)) {
                workflow.add(topLevelState);
            }
        }
        List<String> normalized = new ArrayList<>();
        for (String state : selectedStates == null ? List.<String>of() : selectedStates) {
            String candidate = normalize(state);
            if (candidate != null && workflow.contains(candidate) && !normalized.contains(candidate)) {
                normalized.add(candidate);
            }
        }
        return normalized;
    }

    public Map<String, Object> paperSnapshot(Paper paper) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", String.valueOf(paper.id));
        snapshot.put("record_type", paper.recordTypeValue());
        snapshot.put("title", paper.title);
        snapshot.put("authors", authorsList(paper.authors));
        snapshot.put("abstract", paper.summary);
        snapshot.put("published_on", paper.publishedOn == null ? null : paper.publishedOn.toString());
        snapshot.put("source_link", paper.sourceLink);
        return snapshot;
    }

    private String schemaTaxonomyId(Review review) {
        Map<String, Object> schema = formSchema(review);
        String formSchemaId = stringValue(schema.get("id"));
        if (formSchemaId != null && !formSchemaId.isBlank()) {
            return formSchemaId;
        }
        return review.templateId;
    }

    private boolean isValidSubmission(Review review, Map<String, Object> values) {
        try {
            validateSubmission(review, values);
            return true;
        } catch (ReviewValidationException ignored) {
            return false;
        }
    }

    private Map<String, String> researchQuestionSlotsByKey(Map<String, Object> reviewDesign) {
        Map<String, String> slots = new LinkedHashMap<>();
        for (Map<String, Object> question : objectMapList(reviewDesign.get("research_questions"))) {
            String key = stringValue(question.get("key"));
            String slot = stringValue(question.get("slot_id"));
            if (key != null && slot != null) {
                slots.put(key, slot);
            }
        }
        return slots;
    }

    private List<String> authorsList(String authors) {
        if (authors == null || authors.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(authors.split("\\s*,\\s*|\\s*;\\s*|\\R"))
                .map(String::trim)
                .filter((value) -> !value.isBlank())
                .toList();
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

    private void collectFieldIds(Map<String, Object> field, Set<String> fieldIds) {
        String fieldId = stringValue(field.get("id"));
        if (fieldId != null) {
            fieldIds.add(fieldId);
        }
        for (Map<String, Object> subfield : objectMapList(field.get("subdimensions"))) {
            collectFieldIds(subfield, fieldIds);
        }
    }

    private void validateField(
            Map<String, Object> field,
            Map<String, Object> values,
            Map<String, Object> scales,
            ValidationContext context
    ) {
        String fieldId = stringValue(field.get("id"));
        if (fieldId == null) {
            return;
        }
        Object value = values.get(fieldId);
        boolean missing = isMissing(value);
        if (booleanValue(field.get("required")) && missing) {
            context.errors.add(new ValidationError(fieldId, "Missing required field"));
        }

        List<Map<String, Object>> options = objectMapList(field.get("values"));
        if (!missing) {
            validateCardinality(fieldId, stringValue(field.get("cardinality")), value, context.errors);
            if (!options.isEmpty()) {
                if (!hasScalarShape(value)) {
                    context.errors.add(new ValidationError(fieldId, "Field expects option identifiers"));
                } else {
                    List<String> submittedValues = stringList(value);
                    Set<String> allowed = collectOptionIds(options);
                    for (String submittedValue : submittedValues) {
                        if (!allowed.contains(submittedValue)) {
                            context.errors.add(new ValidationError(fieldId, "Unknown value: " + submittedValue));
                        }
                    }
                    validateSelectedCriteria(options, submittedValues, values, scales, context);
                }
            } else if ("numeric".equals(stringValue(field.get("value_type")))) {
                validateNumericValue(fieldId, value, context.errors);
            } else if (!hasTextShape(value)) {
                context.errors.add(new ValidationError(fieldId, "Field expects text values"));
            }
        }

        for (Map<String, Object> subfield : objectMapList(field.get("subdimensions"))) {
            validateField(subfield, values, scales, context);
        }
    }

    private void validateCardinality(String fieldId, String cardinality, Object value, List<ValidationError> errors) {
        if ("single".equals(cardinality) && value instanceof List<?>) {
            errors.add(new ValidationError(fieldId, "Field accepts a single value"));
        }
        if ("multiple".equals(cardinality) && !(value instanceof List<?>)) {
            errors.add(new ValidationError(fieldId, "Field accepts multiple values"));
        }
    }

    private void validateNumericValue(String fieldId, Object value, List<ValidationError> errors) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Number)) {
                    errors.add(new ValidationError(fieldId, "Field expects numeric values"));
                    return;
                }
            }
            return;
        }
        if (!(value instanceof Number)) {
            errors.add(new ValidationError(fieldId, "Field expects a numeric value"));
        }
    }

    private boolean hasScalarShape(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().allMatch(item -> item instanceof String || item instanceof Number);
        }
        return value instanceof String || value instanceof Number;
    }

    private boolean hasTextShape(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().allMatch(String.class::isInstance);
        }
        return value instanceof String;
    }

    private void validateSelectedCriteria(
            List<Map<String, Object>> options,
            List<String> selectedValues,
            Map<String, Object> values,
            Map<String, Object> scales,
            ValidationContext context
    ) {
        Map<String, Map<String, Object>> optionsById = collectOptionsById(options);
        for (String selectedValue : selectedValues) {
            Map<String, Object> option = optionsById.get(selectedValue);
            if (option == null) {
                continue;
            }
            for (Map<String, Object> criterion : objectMapList(option.get("criteria"))) {
                validateCriterion(criterion, values, scales, context);
            }
        }
    }

    private void validateCriterion(
            Map<String, Object> criterion,
            Map<String, Object> values,
            Map<String, Object> scales,
            ValidationContext context
    ) {
        String criterionId = stringValue(criterion.get("id"));
        if (criterionId == null) {
            return;
        }
        context.activeCriterionIds.add(criterionId);
        Object answer = values.get(criterionId);
        boolean missing = isMissing(answer);
        if (booleanValue(criterion.get("required")) && missing) {
            context.errors.add(new ValidationError(criterionId, "Missing required criterion"));
            return;
        }
        if (missing) {
            return;
        }
        Map<String, Object> scale = asObjectMap(scales.get(stringValue(criterion.get("scale"))));
        if (scale.isEmpty()) {
            return;
        }
        List<Map<String, Object>> scaleValues = objectMapList(scale.get("scale_values"));
        if (!scaleValues.isEmpty()) {
            Set<String> allowed = new HashSet<>();
            for (Map<String, Object> scaleValue : scaleValues) {
                String value = stringValue(scaleValue.get("value"));
                if (value != null) {
                    allowed.add(value);
                }
            }
            if (!allowed.isEmpty() && !allowed.contains(String.valueOf(answer))) {
                context.errors.add(new ValidationError(criterionId, "Unsupported value"));
            }
            return;
        }
        if ("numeric".equals(stringValue(scale.get("scale_type"))) && !(answer instanceof Number)) {
            context.errors.add(new ValidationError(criterionId, "Criterion expects a numeric value"));
        }
    }

    private Set<String> collectOptionIds(List<Map<String, Object>> options) {
        return collectOptionsById(options).keySet();
    }

    private Map<String, Map<String, Object>> collectOptionsById(List<Map<String, Object>> options) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> option : options) {
            String optionId = stringValue(option.get("id"));
            if (optionId != null) {
                result.put(optionId, option);
            }
            result.putAll(collectOptionsById(objectMapList(option.get("children"))));
        }
        return result;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> rows) {
            List<String> result = new ArrayList<>();
            for (Object row : rows) {
                result.add(String.valueOf(row));
            }
            return result;
        }
        return List.of(String.valueOf(value));
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

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String left, String right) {
        return left != null && !left.isBlank() ? left : right;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeRequired(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new BadRequestException(message);
        }
        return normalized;
    }

    public record ReviewPaperContext(Paper paper, ReviewSubmission submission) {
    }

    public record ValidationError(String fieldId, String message) {
    }

    public static final class ReviewValidationException extends RuntimeException {
        private final List<ValidationError> errors;

        public ReviewValidationException(List<ValidationError> errors) {
            super(errors.isEmpty() ? "Review form is invalid" : errors.getFirst().message());
            this.errors = List.copyOf(errors);
        }

        public List<ValidationError> errors() {
            return errors;
        }
    }

    private static final class ValidationContext {
        private final List<ValidationError> errors = new ArrayList<>();
        private final Set<String> fieldIds = new HashSet<>();
        private final Set<String> activeCriterionIds = new HashSet<>();
    }

    public record MigrationResult(int completedSubmissions, int draftSubmissions) {
    }
}
