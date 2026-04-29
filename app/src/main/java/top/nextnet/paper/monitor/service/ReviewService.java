package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        ReviewSubmission submission = reviewSubmissionRepository.findByReviewAndPaper(review, paper).orElseGet(ReviewSubmission::new);
        submission.review = review;
        submission.paper = paper;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("review_id", review.id);
        payload.put("review_template_id", review.templateId);
        payload.put("paper", paperSnapshot(paper));
        payload.put("values", values == null ? Map.of() : values);

        submission.payloadJson = JsonCodec.stringify(payload);
        submission.updatedAt = Instant.now();
        if (submission.id == null) {
            reviewSubmissionRepository.persist(submission);
        }
        return submission;
    }

    @Transactional
    public void resetSubmission(Review review, Paper paper) {
        reviewSubmissionRepository.findByReviewAndPaper(review, paper).ifPresent(ReviewSubmission::delete);
    }

    public Map<String, Object> submissionValues(ReviewSubmission submission) {
        if (submission == null || submission.payloadJson == null || submission.payloadJson.isBlank()) {
            return Map.of();
        }
        Map<String, Object> payload = asObjectMap(JsonCodec.parse(submission.payloadJson));
        return asObjectMap(payload.get("values"));
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
}
