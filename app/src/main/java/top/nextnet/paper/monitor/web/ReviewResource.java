package top.nextnet.paper.monitor.web;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.reactive.RestForm;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.Review;
import top.nextnet.paper.monitor.model.ReviewSubmission;
import top.nextnet.paper.monitor.service.CurrentUserContext;
import top.nextnet.paper.monitor.service.JsonCodec;
import top.nextnet.paper.monitor.service.LogicalFeedAccessService;
import top.nextnet.paper.monitor.service.PaperDataExtractorService;
import top.nextnet.paper.monitor.service.ReviewService;

@Path("/")
@ApplicationScoped
public class ReviewResource {

    private final Template review;
    private final Template reviewPaper;
    private final CurrentUserContext currentUserContext;
    private final LogicalFeedAccessService logicalFeedAccessService;
    private final ReviewService reviewService;
    private final PaperDataExtractorService paperDataExtractorService;

    public ReviewResource(
            @Location("review") Template review,
            @Location("review-paper") Template reviewPaper,
            CurrentUserContext currentUserContext,
            LogicalFeedAccessService logicalFeedAccessService,
            ReviewService reviewService,
            PaperDataExtractorService paperDataExtractorService
    ) {
        this.review = review;
        this.reviewPaper = reviewPaper;
        this.currentUserContext = currentUserContext;
        this.logicalFeedAccessService = logicalFeedAccessService;
        this.reviewService = reviewService;
        this.paperDataExtractorService = paperDataExtractorService;
    }

    @GET
    @Path("/api/review-templates")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ReviewTemplateView> reviewTemplates() {
        requireCurrentUser();
        return paperDataExtractorService.listReviewTemplates().stream()
                .map((template) -> new ReviewTemplateView(template.id(), template.title()))
                .toList();
    }

    @GET
    @Path("/api/reviews")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public List<ReviewSummaryView> reviews() {
        AppUser currentUser = requireCurrentUser();
        return reviewService.reviewsForOwner(currentUser).stream()
                .map((item) -> new ReviewSummaryView(
                        item.id,
                        item.title,
                        item.logicalFeed.id,
                        item.logicalFeed.name,
                        reviewService.selectedStates(item)))
                .toList();
    }

    @POST
    @Path("/api/reviews")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public ReviewSummaryView createReview(
            @RestForm("logicalFeedId") Long logicalFeedId,
            @RestForm("templateId") String templateId,
            @RestForm("selectedStates") List<String> selectedStates
    ) {
        AppUser currentUser = requireCurrentUser();
        LogicalFeed logicalFeed = logicalFeedAccessService.requireReadableLogicalFeed(logicalFeedId, currentUser);
        Review review = reviewService.createOrReplaceReview(currentUser, logicalFeed, selectedStates, templateId);
        return new ReviewSummaryView(review.id, review.title, review.logicalFeed.id, review.logicalFeed.name,
                reviewService.selectedStates(review));
    }

    @GET
    @Path("/reviews/{id}")
    @Transactional
    public TemplateInstance review(@PathParam("id") Long id) {
        AppUser currentUser = requireCurrentUser();
        Review reviewEntity = reviewService.requireReview(id, currentUser);
        List<Paper> papers = reviewService.papersInLiveScope(reviewEntity);
        Map<Long, ReviewSubmission> submissions = reviewService.submissionsByPaperId(reviewEntity);
        List<ReviewRowView> rows = new ArrayList<>();
        for (Paper paper : papers) {
            ReviewSubmission submission = submissions.get(paper.id);
            rows.add(new ReviewRowView(
                    paper.id,
                    paper.title,
                    paper.status,
                    paper.uploadedPdfPath != null,
                    submission != null,
                    submission == null ? null : submission.updatedAt));
        }
        return review.data("review", reviewEntity)
                .data("logicalFeed", reviewEntity.logicalFeed)
                .data("selectedStates", reviewService.selectedStates(reviewEntity))
                .data("rows", rows)
                .data("currentUser", currentUser);
    }

    @GET
    @Path("/reviews/{id}/papers/{paperId}")
    @Transactional
    public TemplateInstance reviewPaper(
            @PathParam("id") Long id,
            @PathParam("paperId") Long paperId
    ) {
        AppUser currentUser = requireCurrentUser();
        Review reviewEntity = reviewService.requireReview(id, currentUser);
        ReviewService.ReviewPaperContext context = reviewService.requireReviewPaper(reviewEntity, paperId);
        Map<String, Object> formSchema = reviewService.formSchema(reviewEntity);
        Map<String, Object> values = reviewService.submissionValues(context.submission());
        return reviewPaper.data("review", reviewEntity)
                .data("paper", context.paper())
                .data("savedAt", context.submission() == null ? null : context.submission().updatedAt)
                .data("formSchemaBase64", encodeBase64(JsonCodec.stringify(formSchema)))
                .data("valuesBase64", encodeBase64(JsonCodec.stringify(values)))
                .data("paperSnapshotBase64", encodeBase64(JsonCodec.stringify(reviewService.paperSnapshot(context.paper()))));
    }

    @POST
    @Path("/api/reviews/{id}/papers/{paperId}/submission")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> saveSubmission(
            @PathParam("id") Long id,
            @PathParam("paperId") Long paperId,
            Map<String, Object> payload
    ) {
        AppUser currentUser = requireCurrentUser();
        Review reviewEntity = reviewService.requireReview(id, currentUser);
        ReviewService.ReviewPaperContext context = reviewService.requireReviewPaper(reviewEntity, paperId);
        Map<String, Object> values = objectMap(payload == null ? null : payload.get("values"));
        ReviewSubmission submission = reviewService.saveSubmission(reviewEntity, context.paper(), values);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("saved", true);
        response.put("updatedAt", submission.updatedAt.toString());
        return response;
    }

    @DELETE
    @Path("/api/reviews/{id}/papers/{paperId}/submission")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> resetSubmission(
            @PathParam("id") Long id,
            @PathParam("paperId") Long paperId
    ) {
        AppUser currentUser = requireCurrentUser();
        Review reviewEntity = reviewService.requireReview(id, currentUser);
        ReviewService.ReviewPaperContext context = reviewService.requireReviewPaper(reviewEntity, paperId);
        reviewService.resetSubmission(reviewEntity, context.paper());
        return Map.of("reset", true);
    }

    private AppUser requireCurrentUser() {
        AppUser user = currentUserContext.user();
        if (user == null) {
            throw new WebApplicationException("Authentication is required", Status.UNAUTHORIZED);
        }
        return user;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> cast = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            cast.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return cast;
    }

    private String encodeBase64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public record ReviewTemplateView(String id, String title) {
    }

    public record ReviewSummaryView(Long id, String title, Long logicalFeedId, String logicalFeedName, List<String> selectedStates) {
    }

    public record ReviewRowView(
            Long paperId,
            String paperTitle,
            String state,
            boolean hasPdf,
            boolean hasSubmission,
            Instant updatedAt
    ) {
    }
}
