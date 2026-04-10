package top.nextnet.paper.monitor.web;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.HttpHeaders;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.repo.PaperEventRepository;
import top.nextnet.paper.monitor.repo.FeedRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;
import top.nextnet.paper.monitor.repo.TtsSettingsRepository;
import top.nextnet.paper.monitor.service.BackupService;
import top.nextnet.paper.monitor.service.FeedPollingService;
import top.nextnet.paper.monitor.service.PaperEventService;
import top.nextnet.paper.monitor.service.PaperGitSyncService;
import top.nextnet.paper.monitor.service.PaperStorageService;
import top.nextnet.paper.monitor.service.TtsService;
import top.nextnet.paper.monitor.service.WorkflowStateConfig;
import top.nextnet.paper.monitor.model.TtsSettings;

@Path("/")
@ApplicationScoped
public class HomeResource {

    private final Template home;
    private final Template admin;
    private final Template logs;
    private final LogicalFeedRepository logicalFeedRepository;
    private final FeedRepository feedRepository;
    private final PaperRepository paperRepository;
    private final PaperEventRepository paperEventRepository;
    private final FeedPollingService feedPollingService;
    private final PaperEventService paperEventService;
    private final PaperGitSyncService paperGitSyncService;
    private final PaperStorageService paperStorageService;
    private final TtsService ttsService;
    private final BackupService backupService;
    private final TtsSettingsRepository ttsSettingsRepository;
    private final String baseUrl;

    public HomeResource(
            @Location("home") Template home,
            @Location("admin") Template admin,
            @Location("logs") Template logs,
            LogicalFeedRepository logicalFeedRepository,
            FeedRepository feedRepository,
            PaperRepository paperRepository,
            PaperEventRepository paperEventRepository,
            FeedPollingService feedPollingService,
            PaperEventService paperEventService,
            PaperGitSyncService paperGitSyncService,
            PaperStorageService paperStorageService,
            TtsService ttsService,
            BackupService backupService,
            TtsSettingsRepository ttsSettingsRepository,
            @ConfigProperty(name = "paper-monitor.base-url", defaultValue = "http://localhost:8080") String baseUrl
    ) {
        this.home = home;
        this.admin = admin;
        this.logs = logs;
        this.logicalFeedRepository = logicalFeedRepository;
        this.feedRepository = feedRepository;
        this.paperRepository = paperRepository;
        this.paperEventRepository = paperEventRepository;
        this.feedPollingService = feedPollingService;
        this.paperEventService = paperEventService;
        this.paperGitSyncService = paperGitSyncService;
        this.paperStorageService = paperStorageService;
        this.ttsService = ttsService;
        this.backupService = backupService;
        this.ttsSettingsRepository = ttsSettingsRepository;
        this.baseUrl = baseUrl == null ? "http://localhost:8080" : baseUrl.trim();
    }

    @GET
    @Transactional
    public TemplateInstance index(@jakarta.ws.rs.QueryParam("paperId") Long paperId) {
        List<LogicalFeed> logicalFeeds = logicalFeedRepository.findAll().list();
        paperGitSyncService.syncLogicalFeeds(logicalFeeds);
        List<Paper> papers = new ArrayList<>(paperRepository.findAllForReader());
        if (paperId != null && papers.stream().noneMatch((paper) -> paper.id.equals(paperId))) {
            paperRepository.findForReader(paperId).ifPresent((paper) -> papers.add(0, paper));
        }
        populatePaperCounts(logicalFeeds);
        return home.data("recentPapers", papers)
                .data("initialPaperId", paperId)
                .data("logicalFeeds", logicalFeeds);
    }

    @GET
    @Path("/admin")
    @Transactional
    public TemplateInstance admin() {
        List<LogicalFeed> logicalFeeds = logicalFeedRepository.findAll().list();
        paperGitSyncService.syncLogicalFeeds(logicalFeeds);
        List<Feed> feeds = feedRepository.findAll().list();
        List<String> ttsVoices = List.of();
        String ttsVoicesError = null;
        try {
            ttsVoices = ttsService.availableVoices();
        } catch (IOException e) {
            ttsVoicesError = e.getMessage();
        }
        return admin.data("logicalFeeds", logicalFeeds)
                .data("feeds", feeds)
                .data("ttsSettings", getOrCreateTtsSettings())
                .data("ttsVoices", ttsVoices)
                .data("ttsVoicesError", ttsVoicesError)
                .data("recentPapers", paperRepository.findRecent(30));
    }

    @POST
    @Path("/admin/tts")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateTtsSettings(
            @RestForm("voice") String voice,
            @RestForm("speedMultiplier") Double speedMultiplier
    ) {
        TtsSettings settings = getOrCreateTtsSettings();
        settings.voice = normalize(voice);
        settings.speedMultiplier = normalizeSpeedMultiplier(speedMultiplier);
        return seeOther("/admin");
    }

    @GET
    @Path("/logs")
    @Transactional
    public TemplateInstance logs() {
        return logs.data("events", paperEventRepository.findRecent(300));
    }

    @POST
    @Path("/tts/speak")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces("audio/wav")
    public Response speak(
            @RestForm("text") String text,
            @RestForm("voice") String voice
    ) {
        try {
            byte[] audio = ttsService.speak(text, voice);
            return Response.ok(audio, "audio/wav").build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(e.getMessage())
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(e.getMessage())
                    .build();
        }
    }

    @GET
    @Path("/admin/export")
    public Response exportBackup() {
        try {
            byte[] zip = backupService.exportZip();
            return Response.ok(zip, "application/zip")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"paper-monitor-backup.zip\"")
                    .build();
        } catch (IOException e) {
            throw new WebApplicationException("Failed to export backup", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Path("/exports/tab")
    @Transactional
    @Produces("text/markdown")
    public Response exportTab(
            @jakarta.ws.rs.QueryParam("logicalFeedId") Long logicalFeedId,
            @jakarta.ws.rs.QueryParam("status") String status,
            @DefaultValue("md") @jakarta.ws.rs.QueryParam("format") String format
    ) {
        LogicalFeed logicalFeed = requireLogicalFeed(logicalFeedId);
        String normalizedStatus = normalizePaperStatus(status);
        if (!logicalFeed.workflowStateList().contains(normalizedStatus)) {
            if (!logicalFeed.topLevelWorkflowStateList().contains(normalizedStatus)) {
                throw new WebApplicationException("status must belong to the selected logical feed workflow",
                    Response.Status.BAD_REQUEST);
            }
        }

        List<Paper> papers = paperRepository.findForTabExport(logicalFeed, logicalFeed.workflowConfig().leafStates(), normalizedStatus);
        String markdown = renderTabExportMarkdown(logicalFeed, normalizedStatus, papers);
        String normalizedFormat = normalizeExportFormat(format);
        String baseFileName = slug(logicalFeed.name) + "-" + slug(normalizedStatus.toLowerCase()) + "-export";

        if ("md".equals(normalizedFormat)) {
            return Response.ok(markdown, "text/markdown; charset=UTF-8")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + baseFileName + ".md\"")
                    .build();
        }

        try {
            byte[] converted = convertMarkdownWithPandoc(markdown, normalizedFormat);
            return Response.ok(converted, exportContentType(normalizedFormat))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + baseFileName + "." + normalizedFormat + "\"")
                    .build();
        } catch (IOException e) {
            Log.errorf(e, "Tab export failed for logicalFeedId=%d status=%s format=%s",
                    logicalFeed.id, normalizedStatus, normalizedFormat);
            return Response.status(Response.Status.BAD_GATEWAY)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(e.getMessage())
                    .build();
        }
    }

    @POST
    @Path("/admin/import")
    @Transactional
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response importBackup(@RestForm("backupZip") FileUpload backupZip) {
        if (backupZip == null) {
            throw new WebApplicationException("Backup zip is required", Response.Status.BAD_REQUEST);
        }
        try (var inputStream = java.nio.file.Files.newInputStream(backupZip.uploadedFile())) {
            backupService.importZip(inputStream);
            return seeOther("/admin");
        } catch (IOException e) {
            throw new WebApplicationException("Failed to import backup", Response.Status.INTERNAL_SERVER_ERROR);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/logical-feeds")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createLogicalFeed(
            @RestForm("name") String name,
            @RestForm("description") String description,
            @RestForm("workflowStates") String workflowStates
    ) {
        try {
            LogicalFeed logicalFeed = new LogicalFeed();
            logicalFeed.name = name == null ? null : name.trim();
            logicalFeed.description = normalize(description);
            logicalFeed.workflowStates = normalizeWorkflowStates(workflowStates);
            logicalFeedRepository.persist(logicalFeed);
            return seeOther("/admin");
        } catch (WebApplicationException e) {
            return rethrowOrPlainText(e);
        }
    }

    @POST
    @Path("/logical-feeds/{id}/update")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateLogicalFeed(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("name") String name,
            @RestForm("description") String description,
            @RestForm("workflowStates") String workflowStates
    ) {
        try {
            LogicalFeed logicalFeed = logicalFeedRepository.findById(id);
            if (logicalFeed == null) {
                throw new NotFoundException();
            }
            logicalFeed.name = name == null ? null : name.trim();
            logicalFeed.description = normalize(description);
            logicalFeed.workflowStates = normalizeWorkflowStates(workflowStates);
            return seeOther("/admin");
        } catch (WebApplicationException e) {
            return rethrowOrPlainText(e);
        }
    }

    @POST
    @Path("/logical-feeds/{id}/delete")
    @Transactional
    public Response deleteLogicalFeed(@jakarta.ws.rs.PathParam("id") Long id) {
        LogicalFeed logicalFeed = logicalFeedRepository.findById(id);
        if (logicalFeed != null && logicalFeed.feeds.isEmpty()) {
            logicalFeed.delete();
        }
        return seeOther("/admin");
    }

    @POST
    @Path("/feeds")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createFeed(
            @RestForm("name") String name,
            @RestForm("url") String url,
            @RestForm("pollIntervalMinutes") Integer pollIntervalMinutes,
            @RestForm("logicalFeedId") Long logicalFeedId,
            @RestForm("defaultPaperStatus") String defaultPaperStatus
    ) {
        LogicalFeed logicalFeed = requireLogicalFeed(logicalFeedId);
        Feed feed = new Feed();
        feed.name = name == null ? null : name.trim();
        feed.url = url == null ? null : url.trim();
        feed.pollIntervalMinutes = pollIntervalMinutes == null ? 60 : pollIntervalMinutes;
        feed.logicalFeed = logicalFeed;
        feed.defaultPaperStatus = normalizeFeedDefaultStatus(logicalFeed, defaultPaperStatus);
        feedRepository.persist(feed);
        paperGitSyncService.syncLogicalFeed(logicalFeed);
        return seeOther("/admin");
    }

    @POST
    @Path("/feeds/{id}/update")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateFeed(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("name") String name,
            @RestForm("url") String url,
            @RestForm("pollIntervalMinutes") Integer pollIntervalMinutes,
            @RestForm("logicalFeedId") Long logicalFeedId,
            @RestForm("defaultPaperStatus") String defaultPaperStatus
    ) {
        Feed feed = feedRepository.findById(id);
        if (feed == null) {
            throw new NotFoundException();
        }
        LogicalFeed logicalFeed = requireLogicalFeed(logicalFeedId);
        feed.name = name == null ? null : name.trim();
        feed.url = url == null ? null : url.trim();
        feed.pollIntervalMinutes = pollIntervalMinutes == null ? 60 : pollIntervalMinutes;
        LogicalFeed previousLogicalFeed = feed.logicalFeed;
        feed.logicalFeed = logicalFeed;
        feed.defaultPaperStatus = normalizeFeedDefaultStatus(logicalFeed, defaultPaperStatus);
        if (previousLogicalFeed != null) {
            paperGitSyncService.syncLogicalFeed(previousLogicalFeed);
        }
        paperGitSyncService.syncLogicalFeed(logicalFeed);
        return seeOther("/admin");
    }

    @POST
    @Path("/feeds/{id}/poll")
    public Response pollFeed(@jakarta.ws.rs.PathParam("id") Long id) {
        feedPollingService.pollFeedById(id);
        Feed feed = feedRepository.findById(id);
        if (feed != null) {
            paperGitSyncService.syncLogicalFeed(feed.logicalFeed);
        }
        return seeOther("/admin");
    }

    @POST
    @Path("/feeds/{id}/delete")
    @Transactional
    public Response deleteFeed(@jakarta.ws.rs.PathParam("id") Long id) {
        Feed feed = feedRepository.findById(id);
        if (feed != null) {
            feed.delete();
        }
        return seeOther("/admin");
    }

    @POST
    @Path("/papers/upload")
    @Transactional
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPaper(
            @RestForm("pdf") FileUpload pdf,
            @RestForm("logicalFeedId") Long logicalFeedId,
            @RestForm("title") String title,
            @RestForm("authors") String authors,
            @RestForm("publisher") String publisher,
            @RestForm("publishedOn") LocalDate publishedOn,
            @RestForm("summary") String summary,
            @RestForm("sourceLink") String sourceLink
    ) {
        LogicalFeed logicalFeed = requireLogicalFeed(logicalFeedId);
        PaperStorageService.StoredPdf storedPdf;
        try {
            storedPdf = paperStorageService.storePdf(pdf);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        } catch (Exception e) {
            throw new WebApplicationException("Failed to store PDF", Response.Status.INTERNAL_SERVER_ERROR);
        }

        Paper paper = new Paper();
        paper.title = normalize(title);
        if (paper.title == null) {
            paper.title = stripPdfSuffix(storedPdf.originalFileName());
        }
        paper.sourceLink = normalize(sourceLink);
        if (paper.sourceLink == null) {
            paper.sourceLink = "upload:" + UUID.randomUUID();
        }
        paper.summary = normalize(summary);
        paper.authors = normalize(authors);
        paper.publisher = normalize(publisher);
        paper.publishedOn = publishedOn;
        paper.status = logicalFeed.initialPaperStatus();
        paper.discoveredAt = Instant.now();
        paper.feed = getOrCreateManualUploadFeed(logicalFeed);
        paper.logicalFeed = logicalFeed;
        paper.uploadedPdfPath = storedPdf.storedPath();
        paper.uploadedPdfFileName = storedPdf.originalFileName();
        paperRepository.persist(paper);
        paperEventService.log(paper, "PDF_UPLOADED", "Uploaded from admin");
        paperGitSyncService.syncLogicalFeed(logicalFeed);
        return seeOther("/admin");
    }

    @GET
    @Path("/papers/{id}/pdf")
    @Produces("application/pdf")
    public Response downloadPaperPdf(
            @jakarta.ws.rs.PathParam("id") Long id,
            @DefaultValue("inline") @jakarta.ws.rs.QueryParam("disposition") String disposition
    ) {
        Paper paper = paperRepository.findById(id);
        if (paper == null || paper.uploadedPdfPath == null) {
            throw new NotFoundException();
        }

        java.nio.file.Path pdfPath = paperStorageService.resolve(paper.uploadedPdfPath);
        if (!Files.exists(pdfPath)) {
            throw new NotFoundException();
        }

        String fileName = paper.uploadedPdfFileName == null ? "paper.pdf" : paper.uploadedPdfFileName;
        try {
            Response.ResponseBuilder response = Response.ok(Files.newInputStream(pdfPath), "application/pdf");
            if ("attachment".equalsIgnoreCase(disposition)) {
                response.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
            }
            return response.build();
        } catch (IOException e) {
            throw new WebApplicationException("Failed to read PDF", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("/papers/{id}/pdf")
    @Transactional
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPaperPdf(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("pdf") FileUpload pdf
    ) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }

        PaperStorageService.StoredPdf storedPdf;
        try {
            storedPdf = paperStorageService.storePdf(pdf);
            paperStorageService.deleteIfExists(paper.uploadedPdfPath);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        } catch (Exception e) {
            throw new WebApplicationException("Failed to store PDF", Response.Status.INTERNAL_SERVER_ERROR);
        }

        paper.uploadedPdfPath = storedPdf.storedPath();
        paper.uploadedPdfFileName = storedPdf.originalFileName();
        paperEventService.log(paper, "PDF_UPLOADED", "Attached PDF " + storedPdf.originalFileName());
        paperGitSyncService.syncLogicalFeed(paper.logicalFeed);
        return Response.noContent().build();
    }

    @POST
    @Path("/papers/{id}/notes")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updatePaperNotes(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("notes") String notes
    ) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }
        paper.notes = notes;
        return Response.noContent().build();
    }

    @POST
    @Path("/papers/{id}/status")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updatePaperStatus(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("status") String status
    ) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }
        try {
            String normalizedStatus = normalizePaperStatus(status);
            if (!logicalFeedWorkflow(logicalFeedRepository.findById(paper.logicalFeed.id)).contains(normalizedStatus)) {
                throw new IllegalArgumentException();
            }
            String previousStatus = paper.status;
            if (normalizedStatus.equals(previousStatus)) {
                return Response.noContent().build();
            }
            paper.status = normalizedStatus;
            paperEventService.log(paper, "STATE_CHANGED", previousStatus + " -> " + normalizedStatus);
            paperGitSyncService.syncLogicalFeed(paper.logicalFeed);
        } catch (Exception e) {
            throw new WebApplicationException("Invalid paper status", Response.Status.BAD_REQUEST);
        }
        return Response.noContent().build();
    }

    @POST
    @Path("/papers/{id}/notes/images")
    @Transactional
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadNoteImage(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("image") FileUpload image
    ) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }

        try {
            PaperStorageService.StoredAsset storedAsset = paperStorageService.storeImage(image);
            String assetPath = "/assets/" + storedAsset.storedPath();
            return Response.ok(assetPath, MediaType.TEXT_PLAIN).build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        } catch (Exception e) {
            throw new WebApplicationException("Failed to store image", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Path("/assets/{name}")
    public Response getAsset(@jakarta.ws.rs.PathParam("name") String name) {
        java.nio.file.Path assetPath = paperStorageService.resolve(name);
        if (!Files.exists(assetPath)) {
            throw new NotFoundException();
        }

        try {
            String contentType = Files.probeContentType(assetPath);
            if (contentType == null) {
                contentType = MediaType.APPLICATION_OCTET_STREAM;
            }
            return Response.ok(Files.newInputStream(assetPath), contentType).build();
        } catch (IOException e) {
            throw new WebApplicationException("Failed to read asset", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private Response seeOther(String location) {
        return Response.seeOther(URI.create(location)).build();
    }

    private Response rethrowOrPlainText(WebApplicationException exception) {
        Response response = exception.getResponse();
        if (response == null || response.getStatus() >= 500) {
            throw exception;
        }
        String message = exception.getMessage();
        return Response.status(response.getStatus())
                .type(MediaType.TEXT_PLAIN)
                .entity(message == null ? "" : message)
                .build();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Double normalizeSpeedMultiplier(Double speedMultiplier) {
        if (speedMultiplier == null) {
            return 1.1d;
        }
        if (speedMultiplier != 1.1d
                && speedMultiplier != 1.2d
                && speedMultiplier != 1.3d
                && speedMultiplier != 1.5d) {
            throw new WebApplicationException("Invalid TTS speed multiplier", Response.Status.BAD_REQUEST);
        }
        return speedMultiplier;
    }

    private TtsSettings getOrCreateTtsSettings() {
        TtsSettings settings = ttsSettingsRepository.first();
        if (settings != null) {
            if (settings.speedMultiplier == null) {
                settings.speedMultiplier = 1.1d;
            }
            return settings;
        }

        settings = new TtsSettings();
        settings.speedMultiplier = 1.1d;
        ttsSettingsRepository.persist(settings);
        return settings;
    }

    private String normalizeWorkflowStates(String value) {
        try {
            return WorkflowStateConfig.parse(value).toYaml();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    private String normalizePaperStatus(String status) {
        if (status == null) {
            throw new IllegalArgumentException();
        }
        String trimmed = status.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException();
        }
        if (!trimmed.contains("/")) {
            return WorkflowStateConfig.normalizeStateSegment(trimmed);
        }
        String[] segments = trimmed.split("/");
        List<String> normalizedSegments = new ArrayList<>();
        for (String segment : segments) {
            normalizedSegments.add(WorkflowStateConfig.normalizeStateSegment(segment));
        }
        return String.join("/", normalizedSegments);
    }

    private List<String> logicalFeedWorkflow(LogicalFeed logicalFeed) {
        if (logicalFeed == null) {
            return List.of();
        }
        return logicalFeed.workflowStateList();
    }

    private LogicalFeed requireLogicalFeed(Long logicalFeedId) {
        if (logicalFeedId == null) {
            throw new WebApplicationException("logicalFeedId is required", Response.Status.BAD_REQUEST);
        }
        LogicalFeed logicalFeed = logicalFeedRepository.findById(logicalFeedId);
        if (logicalFeed == null) {
            throw new WebApplicationException("logicalFeedId does not reference an existing logical feed",
                    Response.Status.BAD_REQUEST);
        }
        return logicalFeed;
    }

    private Feed getOrCreateManualUploadFeed(LogicalFeed logicalFeed) {
        String syntheticUrl = "upload://logical-feed/" + logicalFeed.id;
        return feedRepository.findByUrl(syntheticUrl).orElseGet(() -> {
            Feed feed = new Feed();
            feed.name = "Manual upload: " + logicalFeed.name;
            feed.url = syntheticUrl;
            feed.pollIntervalMinutes = 525600;
            feed.logicalFeed = logicalFeed;
            feedRepository.persist(feed);
            return feed;
        });
    }

    private String stripPdfSuffix(String fileName) {
        if (fileName == null) {
            return "Uploaded paper";
        }
        return fileName.toLowerCase().endsWith(".pdf")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    private String normalizeFeedDefaultStatus(LogicalFeed logicalFeed, String status) {
        String normalized = normalize(status);
        if (normalized == null) {
            return null;
        }
        String normalizedStatus = normalizePaperStatus(normalized);
        if (!logicalFeedWorkflow(logicalFeed).contains(normalizedStatus)) {
            throw new WebApplicationException("defaultPaperStatus must belong to the selected logical feed workflow",
                    Response.Status.BAD_REQUEST);
        }
        return normalizedStatus;
    }

    private String renderTabExportMarkdown(LogicalFeed logicalFeed, String status, List<Paper> papers) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(escapeMarkdownText(logicalFeed.name)).append("\n\n");
        markdown.append("* export date: ").append(ZonedDateTime.now()).append("\n");
        markdown.append("* number of papers: ").append(papers.size()).append("\n");
        markdown.append("* state: ").append(escapeMarkdownText(status)).append("\n\n");

        for (Paper paper : papers) {
            markdown.append("# ").append(escapeMarkdownText(paper.title)).append("\n\n");
            if (paper.uploadedPdfPath != null && !paper.uploadedPdfPath.isBlank()) {
                markdown.append("* [pdf version](")
                        .append(normalizeBaseUrl())
                        .append("/papers/")
                        .append(paper.id)
                        .append("/pdf?disposition=attachment")
                        .append(")\n");
            }
            markdown.append("* authors: ").append(escapeMarkdownText(stringOrDefault(paper.authors, "Unknown authors"))).append("\n");
            markdown.append("* publication date: ").append(escapeMarkdownText(stringOrDefault(paper.publishedOn, "unknown"))).append("\n");
            markdown.append("* venue: ").append(escapeMarkdownText(stringOrDefault(paper.publisher, "Unknown venue"))).append("\n");
            if (paper.sourceLink != null && !paper.sourceLink.startsWith("upload:")) {
                markdown.append("* source: ").append(paper.sourceLink).append("\n");
            }
            if (paper.openAccessLink != null && !paper.openAccessLink.isBlank()) {
                markdown.append("* open access: ").append(paper.openAccessLink).append("\n");
            }
            markdown.append("\n## abstract\n\n");
            markdown.append(stringOrDefault(paper.summary, "No abstract available.")).append("\n\n");
            markdown.append("## Notes\n\n");
            markdown.append(indentMarkdownHeadings(stringOrDefault(paper.notes, "No notes yet."))).append("\n\n");
        }
        return markdown.toString().trim() + "\n";
    }

    private String indentMarkdownHeadings(String markdown) {
        return markdown.lines()
                .map((line) -> {
                    int depth = 0;
                    while (depth < line.length() && line.charAt(depth) == '#') {
                        depth++;
                    }
                    if (depth == 0 || depth >= line.length() || !Character.isWhitespace(line.charAt(depth))) {
                        return line;
                    }
                    return "#".repeat(Math.min(6, depth + 2)) + line.substring(depth);
                })
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String normalizeBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String normalizeExportFormat(String format) {
        String normalized = format == null ? "md" : format.trim().toLowerCase();
        if (!List.of("md", "pdf", "rtf").contains(normalized)) {
            throw new WebApplicationException("Unsupported export format", Response.Status.BAD_REQUEST);
        }
        return normalized;
    }

    private String exportContentType(String format) {
        return switch (format) {
            case "pdf" -> "application/pdf";
            case "rtf" -> "application/rtf";
            default -> "text/markdown; charset=UTF-8";
        };
    }

    private byte[] convertMarkdownWithPandoc(String markdown, String format) throws IOException {
        java.nio.file.Path inputFile = Files.createTempFile("paper-monitor-export-", ".md");
        java.nio.file.Path outputFile = Files.createTempFile("paper-monitor-export-", "." + format);
        try {
            Files.writeString(inputFile, markdown, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>();
            command.add("pandoc");
            command.add(inputFile.toString());
            if ("pdf".equals(format)) {
                command.add("--pdf-engine=xelatex");
            }
            command.add("-o");
            command.add(outputFile.toString());

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            byte[] processOutput = process.getInputStream().readAllBytes();
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Pandoc conversion interrupted", e);
            }

            if (exitCode != 0) {
                String detail = new String(processOutput, StandardCharsets.UTF_8).trim();
                throw new IOException(detail.isEmpty() ? "Pandoc conversion failed" : detail);
            }

            return Files.readAllBytes(outputFile);
        } finally {
            Files.deleteIfExists(inputFile);
            Files.deleteIfExists(outputFile);
        }
    }

    private String escapeMarkdownText(Object value) {
        String text = String.valueOf(value == null ? "" : value).replace('\n', ' ').trim();
        return text.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("#", "\\#");
    }

    private String slug(String value) {
        String normalized = value == null ? "export" : value.toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "export" : normalized;
    }

    private String stringOrDefault(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private void populatePaperCounts(List<LogicalFeed> logicalFeeds) {
        Map<Long, Map<String, Long>> countsByLogicalFeed = paperRepository.countByLogicalFeedAndStatus();
        for (LogicalFeed logicalFeed : logicalFeeds) {
            logicalFeed.paperCountsByState = new LinkedHashMap<>();
            Map<String, Long> exactCounts = countsByLogicalFeed.getOrDefault(logicalFeed.id, Map.of());
            for (String state : logicalFeed.topLevelWorkflowStateList()) {
                long count = exactCounts.entrySet().stream()
                        .filter((entry) -> entry.getKey().equals(state) || entry.getKey().startsWith(state + "/"))
                        .mapToLong(Map.Entry::getValue)
                        .sum();
                logicalFeed.paperCountsByState.put(state, count);
            }
        }
    }
}
