package top.nextnet.paper.monitor.web;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PdfCapture;
import top.nextnet.paper.monitor.repo.PaperRepository;
import top.nextnet.paper.monitor.repo.PdfCaptureRepository;
import top.nextnet.paper.monitor.service.CurrentUserContext;
import top.nextnet.paper.monitor.service.GoogleDriveSyncService;
import top.nextnet.paper.monitor.service.JsonCodec;
import top.nextnet.paper.monitor.service.LogicalFeedAccessService;
import top.nextnet.paper.monitor.service.PaperEventService;
import top.nextnet.paper.monitor.service.PaperGitSyncService;
import top.nextnet.paper.monitor.service.PaperStorageService;

@Path("/")
public class PdfCaptureResource {

    private static final Duration CAPTURE_TTL = Duration.ofMinutes(10);
    private static final long MAX_PDF_BYTES = 50L * 1024L * 1024L;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CurrentUserContext currentUserContext;
    private final LogicalFeedAccessService logicalFeedAccessService;
    private final PaperRepository paperRepository;
    private final PdfCaptureRepository pdfCaptureRepository;
    private final PaperStorageService paperStorageService;
    private final PaperEventService paperEventService;
    private final PaperGitSyncService paperGitSyncService;
    private final GoogleDriveSyncService googleDriveSyncService;

    public PdfCaptureResource(
            CurrentUserContext currentUserContext,
            LogicalFeedAccessService logicalFeedAccessService,
            PaperRepository paperRepository,
            PdfCaptureRepository pdfCaptureRepository,
            PaperStorageService paperStorageService,
            PaperEventService paperEventService,
            PaperGitSyncService paperGitSyncService,
            GoogleDriveSyncService googleDriveSyncService
    ) {
        this.currentUserContext = currentUserContext;
        this.logicalFeedAccessService = logicalFeedAccessService;
        this.paperRepository = paperRepository;
        this.pdfCaptureRepository = pdfCaptureRepository;
        this.paperStorageService = paperStorageService;
        this.paperEventService = paperEventService;
        this.paperGitSyncService = paperGitSyncService;
        this.googleDriveSyncService = googleDriveSyncService;
    }

    @POST
    @Path("/papers/{id}/pdf-captures")
    @Transactional
    @Produces(MediaType.APPLICATION_JSON)
    public Response createCapture(@PathParam("id") Long paperId) {
        AppUser user = requireCurrentUser();
        Paper paper = paperRepository.findById(paperId);
        if (paper == null) {
            throw new NotFoundException();
        }
        if (!logicalFeedAccessService.canAdmin(paper.logicalFeed, user)) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }

        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant now = Instant.now();

        PdfCapture capture = new PdfCapture();
        capture.tokenHash = sha256(token);
        capture.paper = paper;
        capture.createdBy = user;
        capture.createdAt = now;
        capture.expiresAt = now.plus(CAPTURE_TTL);
        capture.status = "ARMED";
        pdfCaptureRepository.persist(capture);
        pdfCaptureRepository.flush();

        String providerUrl = preferredProviderUrl(paper);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("captureId", capture.id);
        payload.put("paperId", paper.id);
        payload.put("paperTitle", paper.title);
        payload.put("providerUrl", providerUrl);
        payload.put("uploadUrl", "/api/pdf-captures/upload");
        payload.put("uploadToken", token);
        payload.put("expiresAt", capture.expiresAt.toString());
        return json(payload);
    }

    @GET
    @Path("/papers/{paperId}/pdf-captures/{captureId}")
    @Transactional
    @Produces(MediaType.APPLICATION_JSON)
    public Response captureStatus(
            @PathParam("paperId") Long paperId,
            @PathParam("captureId") Long captureId
    ) {
        AppUser user = requireCurrentUser();
        PdfCapture capture = pdfCaptureRepository.findById(captureId);
        if (capture == null || capture.paper == null || !paperId.equals(capture.paper.id)) {
            throw new NotFoundException();
        }
        if (!logicalFeedAccessService.canAdmin(capture.paper.logicalFeed, user)) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        return json(statusPayload(capture));
    }

    @POST
    @Path("/api/pdf-captures/upload")
    @Transactional
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadCapture(
            @HeaderParam("Authorization") String authorization,
            @RestForm("pdf") FileUpload pdf,
            @RestForm("sourceUrl") String sourceUrl
    ) {
        String token = bearerToken(authorization);
        PdfCapture capture = pdfCaptureRepository.findByTokenHashForUpdate(sha256(token))
                .orElseThrow(() -> new WebApplicationException("Invalid capture token", Response.Status.UNAUTHORIZED));
        Instant now = Instant.now();
        if (capture.consumedAt != null) {
            throw new WebApplicationException("Capture token has already been used", Response.Status.CONFLICT);
        }
        if (!capture.expiresAt.isAfter(now)) {
            capture.status = "EXPIRED";
            throw new WebApplicationException("Capture token has expired", Response.Status.GONE);
        }

        validatePdf(pdf);
        PaperStorageService.StoredPdf storedPdf;
        try {
            storedPdf = paperStorageService.storePdf(pdf);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        } catch (IOException e) {
            throw new WebApplicationException("Failed to store captured PDF", Response.Status.INTERNAL_SERVER_ERROR);
        }

        Paper paper = capture.paper;
        String previousPath = paper.uploadedPdfPath;
        paper.uploadedPdfPath = storedPdf.storedPath();
        paper.uploadedPdfFileName = storedPdf.originalFileName();
        capture.consumedAt = now;
        capture.status = "UPLOADED";
        capture.capturedSourceUrl = normalizeUrl(sourceUrl);
        paperEventService.log(paper, "PDF_UPLOADED", "Captured PDF from browser extension");
        try {
            paperStorageService.deleteIfExists(previousPath);
        } catch (IOException e) {
            capture.error = "Previous PDF could not be removed";
        }
        paperGitSyncService.syncLogicalFeed(paper.logicalFeed);
        googleDriveSyncService.syncPaperForUser(capture.createdBy, paper);

        Map<String, Object> payload = statusPayload(capture);
        payload.put("pdfUrl", "/papers/" + paper.id + "/pdf");
        return json(payload);
    }

    @POST
    @Path("/api/pdf-captures/failure")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response failCapture(
            @HeaderParam("Authorization") String authorization,
            @RestForm("error") String error
    ) {
        String token = bearerToken(authorization);
        PdfCapture capture = pdfCaptureRepository.findByTokenHashForUpdate(sha256(token))
                .orElseThrow(() -> new WebApplicationException("Invalid capture token", Response.Status.UNAUTHORIZED));
        Instant now = Instant.now();
        if (capture.consumedAt != null) {
            return json(statusPayload(capture));
        }
        capture.consumedAt = now;
        capture.status = "FAILED";
        capture.error = normalizeError(error);
        return json(statusPayload(capture));
    }

    private void validatePdf(FileUpload pdf) {
        if (pdf == null || pdf.uploadedFile() == null) {
            throw new WebApplicationException("PDF upload is required", Response.Status.BAD_REQUEST);
        }
        String fileName = pdf.fileName();
        if (fileName == null || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            throw new WebApplicationException("Captured filename must end with .pdf", Response.Status.BAD_REQUEST);
        }
        try {
            long size = Files.size(pdf.uploadedFile());
            if (size <= 4 || size > MAX_PDF_BYTES) {
                throw new WebApplicationException("Captured PDF must be between 5 bytes and 50 MiB",
                        Response.Status.REQUEST_ENTITY_TOO_LARGE);
            }
            byte[] magic = new byte[4];
            try (var input = Files.newInputStream(pdf.uploadedFile())) {
                if (input.read(magic) != magic.length
                        || !"%PDF".equals(new String(magic, StandardCharsets.US_ASCII))) {
                    throw new WebApplicationException("Captured file is not a PDF", Response.Status.BAD_REQUEST);
                }
            }
        } catch (IOException e) {
            throw new WebApplicationException("Failed to validate captured PDF", Response.Status.BAD_REQUEST);
        }
    }

    private Map<String, Object> statusPayload(PdfCapture capture) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("captureId", capture.id);
        payload.put("paperId", capture.paper.id);
        payload.put("status", capture.effectiveStatus(Instant.now()));
        payload.put("expiresAt", capture.expiresAt.toString());
        payload.put("consumedAt", capture.consumedAt == null ? null : capture.consumedAt.toString());
        payload.put("sourceUrl", capture.capturedSourceUrl);
        payload.put("error", capture.error);
        return payload;
    }

    private Response json(Map<String, Object> payload) {
        return Response.ok(JsonCodec.stringify(payload), MediaType.APPLICATION_JSON).build();
    }

    private AppUser requireCurrentUser() {
        AppUser user = currentUserContext.user();
        if (user == null) {
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        }
        return user;
    }

    private String preferredProviderUrl(Paper paper) {
        String openAccess = normalizeUrl(paper.openAccessLink);
        return openAccess == null ? normalizeUrl(paper.sourceLink) : openAccess;
    }

    private String normalizeUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
            return null;
        }
        return normalized;
    }

    private String normalizeError(String value) {
        if (value == null || value.isBlank()) {
            return "Browser extension capture failed";
        }
        String normalized = value.trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new WebApplicationException("Capture token is required", Response.Status.UNAUTHORIZED);
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw new WebApplicationException("Capture token is required", Response.Status.UNAUTHORIZED);
        }
        return token;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
