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
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    public Review requireReview(Long reviewId, AppUser owner) {
        return reviewRepository.findReadableById(reviewId, owner).orElseThrow(NotFoundException::new);
    }

    @Transactional
    public void deleteReview(Review review) {
        reviewSubmissionRepository.deleteByReview(review);
        review.delete();
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
        PaperDataExtractorService.ReviewTemplateDetail template = paperDataExtractorService.loadReviewTemplate(normalizedTemplateId);
        Map<String, Object> reviewDesign = template.reviewDesign();
        String title = firstNonBlank(
                stringValue(reviewDesign.get("title")),
                normalizedTemplateId);

        Review review = reviewRepository.findByOwnerAndLogicalFeed(owner, logicalFeed).orElseGet(Review::new);
        review.owner = owner;
        review.logicalFeed = logicalFeed;
        review.title = title;
        review.templateId = normalizedTemplateId;
        review.templateTitle = title;
        review.selectedStatesJson = JsonCodec.stringify(normalizedStates);
        review.reviewDesignJson = JsonCodec.stringify(reviewDesign);
        review.formSchemaJson = JsonCodec.stringify(template.formSchema());
        review.reviewJsonSchemaJson = JsonCodec.stringify(template.reviewJsonSchema());
        review.reviewLinkmlSchemaJson = JsonCodec.stringify(template.reviewLinkmlSchema());
        Instant now = Instant.now();
        review.updatedAt = now;
        if (review.createdAt == null) {
            review.createdAt = now;
            reviewRepository.persist(review);
        }
        return review;
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
                .filter((paper) -> selectedStates.contains(paper.topLevelStatus()))
                .toList();
    }

    public Map<Long, ReviewSubmission> submissionsByPaperId(Review review) {
        return reviewSubmissionRepository.findByReviewIndexedByPaperId(review);
    }

    public ReviewPaperContext requireReviewPaper(Review review, Long paperId) {
        Paper paper = paperRepository.findForReader(paperId).orElseThrow(NotFoundException::new);
        if (!Objects.equals(paper.logicalFeed.id, review.logicalFeed.id)) {
            throw new NotFoundException();
        }
        if (!selectedStates(review).contains(paper.topLevelStatus())) {
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
        if (submission.id == null) {
            reviewSubmissionRepository.persist(submission);
        }
        return submission;
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
        List<String> workflow = logicalFeed.topLevelWorkflowStateList();
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
                List<String> submittedValues = stringList(value);
                Set<String> allowed = collectOptionIds(options);
                for (String submittedValue : submittedValues) {
                    if (!allowed.contains(submittedValue)) {
                        context.errors.add(new ValidationError(fieldId, "Unknown value: " + submittedValue));
                    }
                }
                validateSelectedCriteria(options, submittedValues, values, scales, context);
            } else if ("numeric".equals(stringValue(field.get("value_type")))) {
                validateNumericValue(fieldId, value, context.errors);
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
}
