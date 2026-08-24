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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
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
import top.nextnet.paper.monitor.service.MarkdownConversionService;
import top.nextnet.paper.monitor.service.PaperDataExtractorService;
import top.nextnet.paper.monitor.service.PaperGitSyncService;
import top.nextnet.paper.monitor.service.ReviewExcelExportService;
import top.nextnet.paper.monitor.service.ReviewReportService;
import top.nextnet.paper.monitor.service.ReviewService;

@Path("/")
@ApplicationScoped
public class ReviewResource {

    private final Template review;
    private final Template reviewPaper;
    private final CurrentUserContext currentUserContext;
    private final LogicalFeedAccessService logicalFeedAccessService;
    private final ReviewService reviewService;
    private final ReviewReportService reviewReportService;
    private final ReviewExcelExportService reviewExcelExportService;
    private final MarkdownConversionService markdownConversionService;
    private final PaperDataExtractorService paperDataExtractorService;
    private final PaperGitSyncService paperGitSyncService;

    public ReviewResource(
            @Location("review") Template review,
            @Location("review-paper") Template reviewPaper,
            CurrentUserContext currentUserContext,
            LogicalFeedAccessService logicalFeedAccessService,
            ReviewService reviewService,
            ReviewReportService reviewReportService,
            ReviewExcelExportService reviewExcelExportService,
            MarkdownConversionService markdownConversionService,
            PaperDataExtractorService paperDataExtractorService,
            PaperGitSyncService paperGitSyncService
    ) {
        this.review = review;
        this.reviewPaper = reviewPaper;
        this.currentUserContext = currentUserContext;
        this.logicalFeedAccessService = logicalFeedAccessService;
        this.reviewService = reviewService;
        this.reviewReportService = reviewReportService;
        this.reviewExcelExportService = reviewExcelExportService;
        this.markdownConversionService = markdownConversionService;
        this.paperDataExtractorService = paperDataExtractorService;
        this.paperGitSyncService = paperGitSyncService;
    }

    @GET
    @Path("/api/review-templates")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ReviewTemplateView> reviewTemplates() {
        AppUser currentUser = requireCurrentUser();
        return paperDataExtractorService.listReviewTemplates(currentUser).stream()
                .map((template) -> new ReviewTemplateView(
                        template.id(),
                        template.title(),
                        template.derivationId(),
                        template.revision(),
                        template.ownedByCurrentUser(),
                        template.canWrite()))
                .toList();
    }

    @GET
    @Path("/api/review-templates/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> reviewTemplate(@PathParam("id") String id) {
        AppUser currentUser = requireCurrentUser();
        return reviewTemplatePayload(paperDataExtractorService.loadReviewTemplate(id, currentUser));
    }

    @POST
    @Path("/api/review-templates/{id}/derivations")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> deriveReviewTemplate(@PathParam("id") String id, Map<String, Object> payload) {
        AppUser currentUser = requireCurrentUser();
        PaperDataExtractorService.ReviewTemplateDetail created = paperDataExtractorService.deriveReviewTemplate(
                id,
                requiredPayloadString(payload, "title", "A review design title is required"),
                objectMapList(payload == null ? null : payload.get("research_questions")),
                currentUser);
        return reviewTemplatePayload(created);
    }

    @POST
    @Path("/api/reviews/{id}/revisions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> reviseReview(
            @PathParam("id") Long id,
            Map<String, Object> payload
    ) {
        AppUser currentUser = requireCurrentUser();
        Review reviewEntity = reviewService.requireReview(id, currentUser);
        logicalFeedAccessService.requireAdminLogicalFeed(reviewEntity.logicalFeed.id, currentUser);
        PaperDataExtractorService.ReviewTemplateDetail currentTemplate =
                paperDataExtractorService.loadReviewTemplate(reviewEntity.templateId, currentUser);
        String title = requiredPayloadString(payload, "title", "A review design title is required");
        List<Map<String, Object>> researchQuestions = objectMapList(
                payload == null ? null : payload.get("research_questions"));
        boolean derivedDesign = hasDerivationId(currentTemplate.reviewDesign());
        PaperDataExtractorService.ReviewTemplateDetail created = derivedDesign
                ? paperDataExtractorService.reviseReviewTemplate(
                        reviewEntity.templateId, title, researchQuestions, currentUser)
                : paperDataExtractorService.deriveReviewTemplate(
                        reviewEntity.templateId, title, researchQuestions, currentUser);
        ReviewService.MigrationResult migration = reviewService.activateRevision(reviewEntity, created);
        Map<String, Object> response = new LinkedHashMap<>(reviewTemplatePayload(created));
        response.put("createdDerivation", !derivedDesign);
        response.put("completedSubmissions", migration.completedSubmissions());
        response.put("draftSubmissions", migration.draftSubmissions());
        return response;
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
        int analyzedCount = 0;
        for (Paper paper : papers) {
            ReviewSubmission submission = submissions.get(paper.id);
            if (submission != null && submission.complete) {
                analyzedCount += 1;
            }
            rows.add(new ReviewRowView(
                    paper.id,
                    paper.title,
                    paper.status,
                    paper.uploadedPdfPath != null,
                    submission != null,
                    submission != null && submission.complete,
                    submission == null ? null : submission.updatedAt));
        }
        int totalCount = rows.size();
        int remainingCount = Math.max(0, totalCount - analyzedCount);
        double analyzedRatio = totalCount == 0 ? 0D : (double) analyzedCount / (double) totalCount;
        Map<String, Object> design = objectMap(JsonCodec.parse(reviewEntity.reviewDesignJson));
        boolean derivedDesign = hasDerivationId(design);
        return review.data("review", reviewEntity)
                .data("logicalFeed", reviewEntity.logicalFeed)
                .data("selectedStates", reviewService.selectedStates(reviewEntity))
                .data("analyzedCount", analyzedCount)
                .data("remainingCount", remainingCount)
                .data("totalCount", totalCount)
                .data("analyzedPercent", Math.round(analyzedRatio * 100.0d))
                .data("analyzedAngle", analyzedRatio * 360.0d)
                .data("rows", rows)
                .data("canCustomizeResearchQuestions",
                        logicalFeedAccessService.canAdmin(reviewEntity.logicalFeed, currentUser))
                .data("derivedReviewDesign", derivedDesign)
                .data("reviewRevision", design.get("revision"))
                .data("researchQuestionsBase64", encodeBase64(JsonCodec.stringify(
                        researchQuestions(design, reviewService.formSchema(reviewEntity)))))
                .data("currentUser", currentUser)
                .data("masquerading", currentUserContext.isMasquerading())
                .data("masqueradeAdminDisplay", currentUserContext.masqueradeAdminDisplayLabel());
    }

    @GET
    @Path("/api/reviews/{id}/report")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> reviewReport(@PathParam("id") Long id) {
        AppUser currentUser = requireCurrentUser();
        Review reviewEntity = reviewService.requireReview(id, currentUser);
        return reviewReportService.aggregate(reviewEntity);
    }

    @GET
    @Path("/api/reviews/{id}/report.md")
    @Produces("text/markdown; charset=UTF-8")
    @Transactional
    public Response reviewReportMarkdown(
            @PathParam("id") Long id,
            @QueryParam("download") @jakarta.ws.rs.DefaultValue("false") boolean download
    ) {
        AppUser currentUser = requireCurrentUser();
        Review reviewEntity = reviewService.requireReview(id, currentUser);
        String markdown = reviewReportService.renderMarkdown(reviewEntity);
        Response.ResponseBuilder response = Response.ok(markdown);
        if (download) {
            response.header("Content-Disposition", "attachment; filename=\"review-" + reviewEntity.id + "-report.md\"");
        }
        return response.build();
    }

    @GET
    @Path("/api/reviews/{id}/report.pdf")
    @Produces("application/pdf")
    @Transactional
    public Response reviewReportPdf(@PathParam("id") Long id) {
        return reviewReportBinary(id, "pdf", "application/pdf");
    }

    @GET
    @Path("/api/reviews/{id}/report.docx")
    @Produces("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    @Transactional
    public Response reviewReportDocx(@PathParam("id") Long id) {
        return reviewReportBinary(id, "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @GET
    @Path("/api/reviews/{id}/export.xlsx")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Transactional
    public Response reviewExportExcel(@PathParam("id") Long id) {
        AppUser currentUser = requireCurrentUser();
        Review reviewEntity = reviewService.requireReview(id, currentUser);
        try {
            byte[] workbook = reviewExcelExportService.exportSurveyWorkbook(reviewEntity);
            return Response.ok(workbook, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; filename=\"review-" + reviewEntity.id + "-survey.xlsx\"")
                    .build();
        } catch (IOException e) {
            throw new WebApplicationException("Failed to build Excel export", Status.BAD_GATEWAY);
        }
    }

    @DELETE
    @Path("/api/reviews/{id}")
    @Transactional
    public Response deleteReview(@PathParam("id") Long id) {
        AppUser currentUser = requireCurrentUser();
        Review reviewEntity = reviewService.requireReview(id, currentUser);
        LogicalFeed logicalFeed = reviewEntity.logicalFeed;
        reviewService.deleteReview(reviewEntity);
        paperGitSyncService.syncLogicalFeed(logicalFeed);
        return Response.noContent().build();
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
        List<Paper> scopedPapers = reviewService.papersInLiveScope(reviewEntity);
        Paper nextPaper = null;
        for (int i = 0; i < scopedPapers.size(); i++) {
            if (scopedPapers.get(i).id.equals(context.paper().id) && i + 1 < scopedPapers.size()) {
                nextPaper = scopedPapers.get(i + 1);
                break;
            }
        }
        context.paper().viewerCanEdit = logicalFeedAccessService.canAdmin(context.paper().logicalFeed, currentUser);
        Map<String, Object> formSchema = reviewService.formSchema(reviewEntity);
        Map<String, Object> values = reviewService.submissionValues(context.submission());
        return reviewPaper.data("review", reviewEntity)
                .data("paper", context.paper())
                .data("nextPaper", nextPaper)
                .data("savedAt", context.submission() == null ? null : context.submission().updatedAt)
                .data("formSchemaBase64", encodeBase64(JsonCodec.stringify(formSchema)))
                .data("valuesBase64", encodeBase64(JsonCodec.stringify(values)))
                .data("paperSnapshotBase64", encodeBase64(JsonCodec.stringify(reviewService.paperSnapshot(context.paper()))))
                .data("notesBase64", encodeBase64(context.paper().notes == null ? "" : context.paper().notes))
                .data("currentUser", currentUser)
                .data("masquerading", currentUserContext.isMasquerading())
                .data("masqueradeAdminDisplay", currentUserContext.masqueradeAdminDisplayLabel());
    }

    @POST
    @Path("/api/reviews/{id}/papers/{paperId}/submission")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response saveSubmission(
            @PathParam("id") Long id,
            @PathParam("paperId") Long paperId,
            Map<String, Object> payload
    ) {
        AppUser currentUser = requireCurrentUser();
        Review reviewEntity = reviewService.requireReview(id, currentUser);
        ReviewService.ReviewPaperContext context = reviewService.requireReviewPaper(reviewEntity, paperId);
        Map<String, Object> values = objectMap(payload == null ? null : payload.get("values"));
        try {
            ReviewSubmission submission = reviewService.saveSubmission(reviewEntity, context.paper(), values);
            paperGitSyncService.syncLogicalFeed(reviewEntity.logicalFeed);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("saved", true);
            response.put("updatedAt", submission.updatedAt.toString());
            return Response.ok(response).build();
        } catch (ReviewService.ReviewValidationException e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("saved", false);
            response.put("message", "Review form validation failed");
            response.put("errors", e.errors());
            return Response.status(Status.BAD_REQUEST).entity(response).build();
        }
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
        paperGitSyncService.syncLogicalFeed(reviewEntity.logicalFeed);
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

    private List<Map<String, Object>> objectMapList(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            result.add(objectMap(row));
        }
        return result;
    }

    private String requiredPayloadString(Map<String, Object> payload, String key, String message) {
        String value = payload == null || payload.get(key) == null ? null : String.valueOf(payload.get(key)).trim();
        if (value == null || value.isBlank()) {
            throw new WebApplicationException(message, Status.BAD_REQUEST);
        }
        return value;
    }

    static boolean hasDerivationId(Map<String, Object> design) {
        if (design == null || design.get("derivation_id") == null) {
            return false;
        }
        return !String.valueOf(design.get("derivation_id")).isBlank();
    }

    private Map<String, Object> reviewTemplatePayload(PaperDataExtractorService.ReviewTemplateDetail detail) {
        Map<String, Object> design = detail.reviewDesign();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", detail.id());
        response.put("title", design.get("title"));
        response.put("derivationId", design.get("derivation_id"));
        response.put("revision", design.get("revision"));
        response.put("researchQuestions", researchQuestions(design, detail.formSchema()));
        return response;
    }

    private List<Map<String, Object>> researchQuestions(
            Map<String, Object> design,
            Map<String, Object> formSchema
    ) {
        List<Map<String, Object>> explicit = objectMapList(design.get("research_questions"));
        if (!explicit.isEmpty()) {
            return explicit;
        }
        List<Map<String, Object>> inferred = new ArrayList<>();
        for (Map<String, Object> field : objectMapList(formSchema.get("fields"))) {
            String id = String.valueOf(field.getOrDefault("id", ""));
            if (!id.matches("rq_[1-9][0-9]*") || !"free_text".equals(field.get("value_type"))) {
                continue;
            }
            String label = String.valueOf(field.getOrDefault("label", "")).trim();
            if (label.matches("(?i)RQ\\s*[1-9][0-9]*")) {
                label = "";
            }
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("key", null);
            question.put("slot_id", id);
            question.put("ordinal", inferred.size() + 1);
            question.put("question", label);
            question.put("required", Boolean.parseBoolean(String.valueOf(field.getOrDefault("required", false))));
            inferred.add(question);
        }
        return inferred;
    }

    private String encodeBase64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Response reviewReportBinary(Long id, String format, String contentType) {
        AppUser currentUser = requireCurrentUser();
        Review reviewEntity = reviewService.requireReview(id, currentUser);
        String markdown = reviewReportService.renderMarkdown(reviewEntity);
        try {
            byte[] payload = markdownConversionService.convertWithPandoc(markdown, format);
            return Response.ok(payload, contentType)
                    .header("Content-Disposition", "attachment; filename=\"review-" + reviewEntity.id + "-report." + format + "\"")
                    .build();
        } catch (IOException e) {
            throw new WebApplicationException("Failed to convert review report: " + e.getMessage(), Status.BAD_GATEWAY);
        }
    }

    public record ReviewTemplateView(
            String id,
            String title,
            String derivationId,
            Integer revision,
            boolean ownedByCurrentUser,
            boolean canWrite
    ) {
    }

    public record ReviewSummaryView(Long id, String title, Long logicalFeedId, String logicalFeedName, List<String> selectedStates) {
    }

    public record ReviewRowView(
            Long paperId,
            String paperTitle,
            String state,
            boolean hasPdf,
            boolean hasSubmission,
            boolean complete,
            Instant updatedAt
    ) {
    }
}
