package top.nextnet.paper.monitor.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PaperAnalysisJob;
import top.nextnet.paper.monitor.model.Review;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.AppUserRepository;
import top.nextnet.paper.monitor.repo.PaperAnalysisJobRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;

@ApplicationScoped
public class PaperAnalysisService {
    private static final Logger LOG = Logger.getLogger(PaperAnalysisService.class);
    private static final int TOTAL_STEPS = 3;

    private final PaperAnalysisJobRepository jobs;
    private final PaperRepository papers;
    private final AppUserRepository users;
    private final LogicalFeedAccessService access;
    private final ReviewService reviews;
    private final PaperDataExtractorService pde;
    private final PaperStorageService storage;
    private final AuthService auth;
    private final PaperEventService events;
    private final ManagedExecutor executor;

    public PaperAnalysisService(
            PaperAnalysisJobRepository jobs,
            PaperRepository papers,
            AppUserRepository users,
            LogicalFeedAccessService access,
            ReviewService reviews,
            PaperDataExtractorService pde,
            PaperStorageService storage,
            AuthService auth,
            PaperEventService events,
            ManagedExecutor executor
    ) {
        this.jobs = jobs;
        this.papers = papers;
        this.users = users;
        this.access = access;
        this.reviews = reviews;
        this.pde = pde;
        this.storage = storage;
        this.auth = auth;
        this.events = events;
        this.executor = executor;
    }

    public Map<String, Object> start(AppUser user, Long paperId, Long reviewId, String trigger) {
        Long jobId = QuarkusTransaction.requiringNew().call(() -> {
            Paper paper = papers.findForReader(paperId).orElseThrow(NotFoundException::new);
            if (!access.canAdmin(paper.logicalFeed, user)) {
                throw new ForbiddenException();
            }
            if (paper.uploadedPdfPath == null || paper.uploadedPdfPath.isBlank()) {
                throw new BadRequestException("Attach a PDF before analyzing this paper with OpenAI");
            }
            Path pdfPath = storage.resolve(paper.uploadedPdfPath);
            if (!Files.isRegularFile(pdfPath)) {
                throw new BadRequestException("The attached PDF could not be found");
            }
            Optional<PaperAnalysisJob> active = jobs.findActiveByUserAndPaper(user.id, paper.id);
            if (active.isPresent()) {
                return active.get().id;
            }
            Review review = resolveReview(user, paper, reviewId);
            PaperAnalysisJob job = new PaperAnalysisJob();
            job.userId = user.id;
            job.paperId = paper.id;
            job.reviewId = review == null ? null : review.id;
            job.paperTitle = paper.title;
            job.status = "QUEUED";
            job.phase = "Waiting for background worker";
            job.trigger = truncate(trigger == null ? "paper-menu" : trigger.strip(), 24);
            job.completedSteps = 0;
            job.totalSteps = TOTAL_STEPS;
            job.startedAt = Instant.now();
            jobs.persist(job);
            jobs.flush();
            return job.id;
        });
        executor.execute(() -> run(jobId));
        return status(user, jobId);
    }

    public Map<String, Object> status(AppUser user, Long jobId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            PaperAnalysisJob job = jobs.findByIdAndUser(jobId, user.id).orElseThrow(NotFoundException::new);
            return jobView(job, true);
        });
    }

    public Map<String, Object> latest(AppUser user, Long paperId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Paper paper = papers.findForReader(paperId).orElseThrow(NotFoundException::new);
            if (!access.canAdmin(paper.logicalFeed, user)) {
                throw new ForbiddenException();
            }
            return jobs.findLatestByUserAndPaper(user.id, paper.id)
                    .map(job -> jobView(job, true))
                    .orElseGet(() -> idleView(paperId));
        });
    }

    void markInterruptedJobsFailed(@Observes StartupEvent ignored) {
        QuarkusTransaction.requiringNew().run(() -> {
            for (PaperAnalysisJob job : jobs.findInterrupted()) {
                job.status = "FAILED";
                job.phase = "Analysis interrupted";
                job.error = "Paper analysis was interrupted by an application restart. Retry the analysis.";
                job.finishedAt = Instant.now();
            }
        });
    }

    private Review resolveReview(AppUser user, Paper paper, Long reviewId) {
        if (reviewId == null) {
            return reviews.reviewForPaper(user, paper).orElse(null);
        }
        Review review = reviews.requireReview(reviewId, user);
        reviews.requireReviewPaper(review, paper.id);
        return review;
    }

    private void run(Long jobId) {
        try {
            AnalysisInput input = prepare(jobId);
            PaperDataExtractorService.PaperAnalysisResult result = pde.analyzePaper(
                    input.pdfPath(), input.fileName(), input.paper(), input.reviewDesign(),
                    input.formSchema(), input.user());
            update(jobId, "SAVING", "Saving structured abstract and review draft", 2, null, false);
            finish(jobId, result);
            LOG.infof("OpenAI paper analysis job %d completed", jobId);
        } catch (Exception error) {
            LOG.errorf(error, "OpenAI paper analysis job %d failed", jobId);
            update(jobId, "FAILED", "Paper analysis failed", null, rootMessage(error), true);
        }
    }

    private AnalysisInput prepare(Long jobId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            PaperAnalysisJob job = jobs.findById(jobId);
            if (job == null) {
                throw new NotFoundException("Paper analysis job no longer exists");
            }
            Paper paper = papers.findForReader(job.paperId).orElseThrow(NotFoundException::new);
            AppUser user = users.findByIdOptional(job.userId).orElseThrow(NotFoundException::new);
            UserSettings settings = auth.ensureSettings(user);
            Review review = job.reviewId == null ? null : reviews.requireReview(job.reviewId, user);
            job.status = "ANALYZING";
            job.phase = "Uploading and analyzing the paper PDF";
            job.completedSteps = 1;
            Map<String, Object> reviewDesign = review == null
                    ? Map.of() : objectMap(JsonCodec.parse(review.reviewDesignJson));
            Map<String, Object> formSchema = review == null ? Map.of() : reviews.formSchema(review);
            return new AnalysisInput(
                    storage.resolve(paper.uploadedPdfPath),
                    pdfFileName(paper),
                    reviews.paperSnapshot(paper),
                    reviewDesign,
                    formSchema,
                    new PaperDataExtractorService.OpenAiRequestContext(
                            user.id, user.username, user.displayLabel(), user.email, user.isAdmin(),
                            settings.pdeOpenAiApiKey == null ? "" : settings.pdeOpenAiApiKey,
                            settings.effectivePdeOpenAiExtractionQuota(),
                            settings.effectivePdeOpenAiExtractionCallsUsed()));
        });
    }

    private void finish(Long jobId, PaperDataExtractorService.PaperAnalysisResult result) {
        QuarkusTransaction.requiringNew().run(() -> {
            PaperAnalysisJob job = jobs.findById(jobId);
            if (job == null) {
                throw new NotFoundException("Paper analysis job no longer exists");
            }
            Paper paper = papers.findForReaderForUpdate(job.paperId).orElseThrow(NotFoundException::new);
            paper.notes = appendStructuredAbstract(paper.notes, result.structuredAbstractMarkdown(), Instant.now());
            events.log(paper, "NOTES_CHANGED", "Appended OpenAI structured abstract");
            job.notesUpdated = true;
            if (job.reviewId != null) {
                AppUser user = users.findByIdOptional(job.userId).orElse(null);
                if (user != null) {
                    try {
                        Review review = reviews.requireReview(job.reviewId, user);
                        reviews.replaceWithDraft(review, paper, result.reviewValues());
                        job.reviewDraftUpdated = true;
                    } catch (NotFoundException ignored) {
                        LOG.warnf("Review %d was removed while paper analysis job %d was running",
                                job.reviewId, job.id);
                    }
                }
            }
            job.status = "COMPLETED";
            job.phase = job.reviewDraftUpdated
                    ? "Structured abstract and review draft ready"
                    : "Structured abstract ready";
            job.completedSteps = TOTAL_STEPS;
            job.error = null;
            job.finishedAt = Instant.now();
        });
    }

    private void update(Long jobId, String status, String phase, Integer completed, String error, boolean finished) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                PaperAnalysisJob job = jobs.findById(jobId);
                if (job == null) {
                    return;
                }
                job.status = status;
                job.phase = phase;
                if (completed != null) {
                    job.completedSteps = completed;
                }
                job.error = truncate(error, 2000);
                if (finished) {
                    job.finishedAt = Instant.now();
                }
            });
        } catch (RuntimeException persistenceError) {
            LOG.warnf(persistenceError, "Could not update OpenAI paper analysis job %d", jobId);
        }
    }

    private Map<String, Object> jobView(PaperAnalysisJob job, boolean includeResult) {
        Map<String, Object> result = new LinkedHashMap<>();
        String status = job.status == null ? "FAILED" : job.status;
        result.put("jobId", job.id);
        result.put("paperId", job.paperId);
        result.put("paperTitle", job.paperTitle);
        result.put("reviewId", job.reviewId);
        result.put("status", status);
        result.put("phase", job.phase == null ? "Not running" : job.phase);
        result.put("completed", job.completedSteps == null ? 0 : job.completedSteps);
        result.put("total", job.totalSteps == null ? TOTAL_STEPS : job.totalSteps);
        result.put("running", isRunning(status));
        result.put("error", job.error == null ? "" : job.error);
        result.put("startedAt", job.startedAt == null ? "" : job.startedAt.toString());
        result.put("finishedAt", job.finishedAt == null ? "" : job.finishedAt.toString());
        result.put("notesUpdated", job.notesUpdated);
        result.put("reviewDraftUpdated", job.reviewDraftUpdated);
        if (includeResult && "COMPLETED".equals(status)) {
            Paper paper = papers.findForReader(job.paperId).orElse(null);
            result.put("notes", paper == null || paper.notes == null ? "" : paper.notes);
            result.put("reviewValues", completedReviewValues(job, paper));
        }
        return result;
    }

    private Map<String, Object> completedReviewValues(PaperAnalysisJob job, Paper paper) {
        if (job.reviewId == null || paper == null) {
            return Map.of();
        }
        AppUser user = users.findByIdOptional(job.userId).orElse(null);
        if (user == null) {
            return Map.of();
        }
        try {
            Review review = reviews.requireReview(job.reviewId, user);
            return reviews.submissionValues(review, paper);
        } catch (NotFoundException ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> idleView(Long paperId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", null);
        result.put("paperId", paperId);
        result.put("status", "IDLE");
        result.put("phase", "Not running");
        result.put("completed", 0);
        result.put("total", TOTAL_STEPS);
        result.put("running", false);
        result.put("error", "");
        return result;
    }

    static String appendStructuredAbstract(String existingNotes, String structuredAbstract, Instant generatedAt) {
        String section = "## OpenAI structured abstract\n\n"
                + "_Generated " + generatedAt + "_\n\n"
                + structuredAbstract.strip();
        if (existingNotes == null || existingNotes.isBlank()) {
            return section;
        }
        return existingNotes.stripTrailing() + "\n\n" + section;
    }

    private static boolean isRunning(String status) {
        return "QUEUED".equals(status) || "ANALYZING".equals(status) || "SAVING".equals(status);
    }

    private static String pdfFileName(Paper paper) {
        String name = paper.uploadedPdfFileName;
        return name == null || name.isBlank() ? "paper-" + paper.id + ".pdf" : name;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String truncate(String value, int maximum) {
        if (value == null) {
            return null;
        }
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String rootMessage(Throwable error) {
        if (error instanceof WebApplicationException web && web.getResponse() != null
                && web.getResponse().hasEntity()) {
            try {
                String entity = web.getResponse().readEntity(String.class);
                if (entity != null && !entity.isBlank()) {
                    return entity;
                }
            } catch (RuntimeException ignored) {
                // Fall through to the exception chain.
            }
        }
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record AnalysisInput(
            Path pdfPath,
            String fileName,
            Map<String, Object> paper,
            Map<String, Object> reviewDesign,
            Map<String, Object> formSchema,
            PaperDataExtractorService.OpenAiRequestContext user
    ) {
    }
}
