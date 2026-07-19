package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.GoogleDrivePdfSync;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.GoogleDrivePdfSyncRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;

@ApplicationScoped
public class GoogleDriveSyncService {

    private static final String DRIVE_API_BASE_URL = "https://www.googleapis.com/drive/v3";
    private static final String DRIVE_UPLOAD_BASE_URL = "https://www.googleapis.com/upload/drive/v3";
    private static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
    private static final Pattern DRIVE_FOLDER_PATH = Pattern.compile("/folders/([A-Za-z0-9_-]+)");
    private static final Pattern DRIVE_ID_QUERY = Pattern.compile("[?&]id=([A-Za-z0-9_-]+)");
    private static final Pattern DRIVE_OPEN_QUERY = Pattern.compile("[?&]q=([A-Za-z0-9_-]+)");

    private final HttpClient httpClient;
    private final AuthService authService;
    private final GoogleDriveAuthService googleDriveAuthService;
    private final GoogleDrivePdfSyncRepository googleDrivePdfSyncRepository;
    private final PaperRepository paperRepository;
    private final PaperStorageService paperStorageService;

    @Inject
    public GoogleDriveSyncService(
            AuthService authService,
            GoogleDriveAuthService googleDriveAuthService,
            GoogleDrivePdfSyncRepository googleDrivePdfSyncRepository,
            PaperRepository paperRepository,
            PaperStorageService paperStorageService
    ) {
        this(HttpClient.newHttpClient(), authService, googleDriveAuthService, googleDrivePdfSyncRepository, paperRepository, paperStorageService);
    }

    GoogleDriveSyncService(
            HttpClient httpClient,
            AuthService authService,
            GoogleDriveAuthService googleDriveAuthService,
            GoogleDrivePdfSyncRepository googleDrivePdfSyncRepository,
            PaperRepository paperRepository,
            PaperStorageService paperStorageService
    ) {
        this.httpClient = httpClient;
        this.authService = authService;
        this.googleDriveAuthService = googleDriveAuthService;
        this.googleDrivePdfSyncRepository = googleDrivePdfSyncRepository;
        this.paperRepository = paperRepository;
        this.paperStorageService = paperStorageService;
    }

    @Transactional
    public UserSettings updateSettings(AppUser user, String folderInput, boolean enabled) throws IOException {
        if (user == null) {
            throw new IllegalArgumentException("A signed-in user is required");
        }
        UserSettings settings = authService.ensureSettings(user);
        if (enabled && !settings.hasGoogleDriveConnection()) {
            throw new IllegalArgumentException("Connect Google Drive before enabling sync");
        }
        String folderId = extractFolderId(folderInput);
        if (enabled && folderId == null) {
            throw new IllegalArgumentException("A Google Drive folder URL or ID is required");
        }
        if (enabled && settings.googleDriveGrantedScopes != null && !hasFullDriveScope(settings.googleDriveGrantedScopes)) {
            throw new IllegalArgumentException("Google Drive access was granted without the full Drive scope. Disconnect and reconnect Google Drive, then approve the Drive files permission.");
        }
        if (folderId != null && settings.hasGoogleDriveConnection()) {
            DriveFolder folder = validateFolder(settings, folderId);
            settings.googleDriveFolderId = folder.id();
            settings.googleDriveFolderName = folder.name();
        } else if (folderId == null) {
            settings.googleDriveFolderId = null;
            settings.googleDriveFolderName = null;
        }
        settings.googleDriveSyncEnabled = enabled;
        settings.googleDriveLastSyncError = null;
        return settings;
    }

    @Transactional
    public BackfillResult backfill(AppUser user, List<LogicalFeed> logicalFeeds) {
        UserSettings settings = authService.ensureSettings(user);
        if (!settings.googleDriveReady()) {
            throw new IllegalArgumentException("Google Drive sync is not ready. Connect Google Drive, save a destination folder, and enable sync first.");
        }
        if (logicalFeeds == null || logicalFeeds.isEmpty()) {
            return new BackfillResult(0, 0, 0);
        }
        List<Long> logicalFeedIds = logicalFeeds.stream()
                .filter(logicalFeed -> logicalFeed != null && logicalFeed.id != null)
                .map(logicalFeed -> logicalFeed.id)
                .toList();
        int eligible = 0;
        int synced = 0;
        int failed = 0;
        for (Paper paper : paperRepository.findAllForLogicalFeedIds(logicalFeedIds)) {
            if (paper.uploadedPdfPath == null || paper.uploadedPdfPath.isBlank()) {
                continue;
            }
            eligible += 1;
            if (syncPaperForUser(user, paper)) {
                synced += 1;
            } else {
                failed += 1;
            }
        }
        return new BackfillResult(eligible, synced, failed);
    }

    @Transactional
    public boolean syncPaperForUser(AppUser user, Paper paper) {
        if (user == null || paper == null || paper.uploadedPdfPath == null || paper.uploadedPdfPath.isBlank()) {
            return true;
        }
        UserSettings settings = authService.ensureSettings(user);
        if (!settings.googleDriveReady()) {
            return true;
        }
        GoogleDrivePdfSync sync = googleDrivePdfSyncRepository.findByUserAndPaper(user, paper).orElseGet(() -> {
            GoogleDrivePdfSync created = new GoogleDrivePdfSync();
            created.user = user;
            created.paper = paper;
            googleDrivePdfSyncRepository.persist(created);
            return created;
        });
        try {
            DriveFile driveFile = uploadOrUpdate(settings, sync, paper);
            sync.driveFileId = driveFile.id();
            sync.driveFileName = driveFile.name();
            sync.driveWebViewLink = driveFile.webViewLink();
            sync.driveFolderId = driveFile.folderId();
            sync.driveFolderName = driveFile.folderName();
            sync.syncedStoredPdfPath = paper.uploadedPdfPath;
            sync.syncedOriginalFileName = paper.uploadedPdfFileName;
            sync.syncedAt = Instant.now();
            sync.lastError = null;
            settings.googleDriveLastSyncAt = sync.syncedAt;
            settings.googleDriveLastSyncError = null;
            return true;
        } catch (IOException | IllegalArgumentException e) {
            String message = truncate(e.getMessage(), 2000);
            sync.lastError = message;
            settings.googleDriveLastSyncError = message;
            return false;
        }
    }

    public static String extractFolderId(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        Matcher folderMatcher = DRIVE_FOLDER_PATH.matcher(trimmed);
        if (folderMatcher.find()) {
            return folderMatcher.group(1);
        }
        Matcher idMatcher = DRIVE_ID_QUERY.matcher(trimmed);
        if (idMatcher.find()) {
            return idMatcher.group(1);
        }
        Matcher openMatcher = DRIVE_OPEN_QUERY.matcher(trimmed);
        if (openMatcher.find()) {
            return openMatcher.group(1);
        }
        if (trimmed.matches("[A-Za-z0-9_-]+")) {
            return trimmed;
        }
        throw new IllegalArgumentException("Paste a Google Drive folder URL or folder ID");
    }

    private DriveFolder validateFolder(UserSettings settings, String folderId) throws IOException {
        String accessToken = googleDriveAuthService.accessToken(settings);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_API_BASE_URL + "/files/" + encodePath(folderId)
                        + "?fields=id,name,mimeType,trashed&supportsAllDrives=true"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        Map<String, Object> payload = sendObject(request, "Failed to validate Google Drive folder");
        String mimeType = stringValue(payload.get("mimeType"));
        boolean trashed = Boolean.TRUE.equals(payload.get("trashed"));
        if (!FOLDER_MIME_TYPE.equals(mimeType) || trashed) {
            throw new IOException("The selected Google Drive target is not an available folder");
        }
        return new DriveFolder(stringValue(payload.get("id")), stringValue(payload.get("name")));
    }

    private DriveFile uploadOrUpdate(UserSettings settings, GoogleDrivePdfSync sync, Paper paper) throws IOException {
        String accessToken = googleDriveAuthService.accessToken(settings);
        String fileName = driveFileName(paper);
        DriveFolder targetFolder = ensurePaperFeedFolder(settings, paper, accessToken);
        byte[] pdfBytes = Files.readAllBytes(paperStorageService.resolve(paper.uploadedPdfPath));
        String boundary = "paper-monitor-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, metadata(fileName, targetFolder.id()), pdfBytes);
        String url = sync.driveFileId == null || sync.driveFileId.isBlank()
                ? DRIVE_UPLOAD_BASE_URL + "/files?uploadType=multipart&fields=id,name,webViewLink&supportsAllDrives=true"
                : DRIVE_UPLOAD_BASE_URL + "/files/" + encodePath(sync.driveFileId)
                        + "?uploadType=multipart&fields=id,name,webViewLink&supportsAllDrives=true"
                        + moveQuery(sync.driveFolderId, targetFolder.id());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "multipart/related; boundary=" + boundary);
        HttpRequest request = sync.driveFileId == null || sync.driveFileId.isBlank()
                ? builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
                : builder.method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body)).build();
        Map<String, Object> payload = sendObject(request, "Failed to sync PDF to Google Drive");
        return new DriveFile(
                stringValue(payload.get("id")),
                stringValue(payload.get("name")),
                stringValue(payload.get("webViewLink")),
                targetFolder.id(),
                targetFolder.name());
    }

    private DriveFolder ensurePaperFeedFolder(UserSettings settings, Paper paper, String accessToken) throws IOException {
        String parentFolderId = settings.googleDriveFolderId;
        String folderName = paperFeedFolderName(paper == null ? null : paper.logicalFeed);
        DriveFolder existing = findFolder(accessToken, parentFolderId, folderName);
        if (existing != null) {
            return existing;
        }
        return createFolder(accessToken, parentFolderId, folderName);
    }

    private DriveFolder findFolder(String accessToken, String parentFolderId, String folderName) throws IOException {
        String query = "'" + driveQueryEscape(parentFolderId) + "' in parents"
                + " and name = '" + driveQueryEscape(folderName) + "'"
                + " and mimeType = '" + FOLDER_MIME_TYPE + "'"
                + " and trashed = false";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_API_BASE_URL + "/files?q=" + encodeQueryParam(query)
                        + "&pageSize=1&fields=files(id,name)&supportsAllDrives=true&includeItemsFromAllDrives=true"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        Map<String, Object> payload = sendObject(request, "Failed to find Google Drive paper-feed folder");
        Object files = payload.get("files");
        if (!(files instanceof Iterable<?> iterable)) {
            return null;
        }
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                return new DriveFolder(stringValue(map.get("id")), stringValue(map.get("name")));
            }
        }
        return null;
    }

    private DriveFolder createFolder(String accessToken, String parentFolderId, String folderName) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", folderName);
        metadata.put("mimeType", FOLDER_MIME_TYPE);
        metadata.put("parents", List.of(parentFolderId));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DRIVE_API_BASE_URL + "/files?fields=id,name&supportsAllDrives=true"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.stringify(metadata)))
                .build();
        Map<String, Object> payload = sendObject(request, "Failed to create Google Drive paper-feed folder");
        return new DriveFolder(stringValue(payload.get("id")), stringValue(payload.get("name")));
    }

    private Map<String, Object> metadata(String fileName, String folderId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", fileName);
        metadata.put("mimeType", "application/pdf");
        if (folderId != null && !folderId.isBlank()) {
            metadata.put("parents", List.of(folderId));
        }
        return metadata;
    }

    private byte[] multipartBody(String boundary, Map<String, Object> metadata, byte[] pdfBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        output.write(JsonCodec.stringify(metadata).getBytes(StandardCharsets.UTF_8));
        output.write(("\r\n--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write("Content-Type: application/pdf\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        output.write(pdfBytes);
        output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private Map<String, Object> sendObject(HttpRequest request, String defaultMessage) throws IOException {
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(defaultMessage, e);
        }
        String body = new String(response.body(), StandardCharsets.UTF_8).trim();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() == 403 && body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT")) {
                throw new IOException("Google Drive access was granted with insufficient scopes. Set PAPER_MONITOR_GOOGLE_SCOPES to include https://www.googleapis.com/auth/drive, then disconnect and reconnect Google Drive.");
            }
            throw new IOException(body.isEmpty() ? defaultMessage + " (HTTP " + response.statusCode() + ")" : body);
        }
        Object parsed = JsonCodec.parse(body);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException(defaultMessage + ": invalid JSON payload");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private String driveFileName(Paper paper) {
        String fileName = paper.uploadedPdfFileName;
        if (fileName == null || fileName.isBlank()) {
            return "paper-" + paper.id + ".pdf";
        }
        return fileName.toLowerCase(Locale.ROOT).endsWith(".pdf") ? fileName : fileName + ".pdf";
    }

    static String paperFeedFolderName(LogicalFeed logicalFeed) {
        if (logicalFeed == null || logicalFeed.name == null || logicalFeed.name.isBlank()) {
            return "Paper Feed";
        }
        return logicalFeed.name.trim();
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String encodeQueryParam(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String moveQuery(String previousFolderId, String targetFolderId) {
        if (targetFolderId == null || targetFolderId.isBlank() || targetFolderId.equals(previousFolderId)) {
            return "";
        }
        String query = "&addParents=" + encodeQueryParam(targetFolderId);
        if (previousFolderId != null && !previousFolderId.isBlank()) {
            query += "&removeParents=" + encodeQueryParam(previousFolderId);
        }
        return query;
    }

    static String driveQueryEscape(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("'", "\\'");
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "Google Drive sync failed";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    static boolean hasFullDriveScope(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return false;
        }
        return List.of(scopes.split("\\s+")).contains("https://www.googleapis.com/auth/drive");
    }

    private record DriveFolder(String id, String name) {
    }

    private record DriveFile(String id, String name, String webViewLink, String folderId, String folderName) {
    }

    public record BackfillResult(int eligible, int synced, int failed) {
    }
}
