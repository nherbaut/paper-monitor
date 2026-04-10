package top.nextnet.paper.monitor.web;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
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
import java.nio.file.Files;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
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
import top.nextnet.paper.monitor.service.BackupService;
import top.nextnet.paper.monitor.service.FeedPollingService;
import top.nextnet.paper.monitor.service.PaperEventService;
import top.nextnet.paper.monitor.service.PaperStorageService;

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
    private final PaperStorageService paperStorageService;
    private final BackupService backupService;

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
            PaperStorageService paperStorageService,
            BackupService backupService
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
        this.paperStorageService = paperStorageService;
        this.backupService = backupService;
    }

    @GET
    @Transactional
    public TemplateInstance index(@jakarta.ws.rs.QueryParam("paperId") Long paperId) {
        List<Paper> papers = new ArrayList<>(paperRepository.findAllForReader());
        if (paperId != null && papers.stream().noneMatch((paper) -> paper.id.equals(paperId))) {
            paperRepository.findForReader(paperId).ifPresent((paper) -> papers.add(0, paper));
        }
        List<LogicalFeed> logicalFeeds = logicalFeedRepository.findAll().list();
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
        List<Feed> feeds = feedRepository.findAll().list();
        return admin.data("logicalFeeds", logicalFeeds)
                .data("feeds", feeds)
                .data("recentPapers", paperRepository.findRecent(30));
    }

    @GET
    @Path("/logs")
    @Transactional
    public TemplateInstance logs() {
        return logs.data("events", paperEventRepository.findRecent(300));
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
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = name == null ? null : name.trim();
        logicalFeed.description = normalize(description);
        logicalFeed.workflowStates = normalizeWorkflowStates(workflowStates);
        logicalFeedRepository.persist(logicalFeed);
        return seeOther("/admin");
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
        LogicalFeed logicalFeed = logicalFeedRepository.findById(id);
        if (logicalFeed == null) {
            throw new NotFoundException();
        }
        logicalFeed.name = name == null ? null : name.trim();
        logicalFeed.description = normalize(description);
        logicalFeed.workflowStates = normalizeWorkflowStates(workflowStates);
        return seeOther("/admin");
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
        feed.logicalFeed = logicalFeed;
        feed.defaultPaperStatus = normalizeFeedDefaultStatus(logicalFeed, defaultPaperStatus);
        return seeOther("/admin");
    }

    @POST
    @Path("/feeds/{id}/poll")
    public Response pollFeed(@jakarta.ws.rs.PathParam("id") Long id) {
        feedPollingService.pollFeedById(id);
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

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeWorkflowStates(String value) {
        if (value == null) {
            throw new WebApplicationException("workflowStates is required", Response.Status.BAD_REQUEST);
        }
        List<String> normalizedStates = value.lines()
                .map(String::trim)
                .filter(state -> !state.isEmpty())
                .map(this::normalizePaperStatus)
                .distinct()
                .toList();
        if (normalizedStates.isEmpty()) {
            throw new WebApplicationException("At least one workflow state is required", Response.Status.BAD_REQUEST);
        }
        return String.join("\n", normalizedStates);
    }

    private String normalizePaperStatus(String status) {
        if (status == null) {
            throw new IllegalArgumentException();
        }
        String normalized = status.trim().toUpperCase().replace(' ', '_');
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException();
        }
        return normalized;
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

    private void populatePaperCounts(List<LogicalFeed> logicalFeeds) {
        Map<Long, Map<String, Long>> countsByLogicalFeed = paperRepository.countByLogicalFeedAndStatus();
        for (LogicalFeed logicalFeed : logicalFeeds) {
            logicalFeed.paperCountsByState = new LinkedHashMap<>();
            for (String state : logicalFeed.workflowStateList()) {
                long count = countsByLogicalFeed
                        .getOrDefault(logicalFeed.id, Map.of())
                        .getOrDefault(state, 0L);
                logicalFeed.paperCountsByState.put(state, count);
            }
        }
    }
}
