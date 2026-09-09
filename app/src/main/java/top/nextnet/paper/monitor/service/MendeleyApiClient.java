package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import top.nextnet.paper.monitor.model.UserSettings;

@ApplicationScoped
public class MendeleyApiClient {
    static final int PAGE_SIZE = 100;
    static final String API = "https://api.mendeley.com";
    static final String DOCUMENT = "application/vnd.mendeley-document.1+json";
    static final String FOLDER = "application/vnd.mendeley-folder.1+json";
    static final String FILE = "application/vnd.mendeley-file.1+json";
    static final String ANNOTATION = "application/vnd.mendeley-annotation.1+json";
    private final HttpClient client;
    private final MendeleyAuthService auth;

    @Inject
    public MendeleyApiClient(MendeleyAuthService auth) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(), auth);
    }

    MendeleyApiClient(HttpClient client, MendeleyAuthService auth) { this.client = client; this.auth = auth; }

    public List<Map<String, Object>> folders(UserSettings settings) throws IOException {
        return getPages(settings, paginated(API + "/folders"), FOLDER);
    }

    public Map<String, Object> createFolder(UserSettings settings, String name, String parentId) throws IOException {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        if (parentId != null && !parentId.isBlank()) body.put("parent_id", parentId);
        return object(send(settings, "POST", API + "/folders", FOLDER, FOLDER,
                JsonCodec.stringify(body).getBytes(StandardCharsets.UTF_8), Map.of()));
    }

    public List<Map<String, Object>> documents(UserSettings settings) throws IOException {
        return getPages(settings, paginated(API + "/documents?view=all"), DOCUMENT);
    }

    public Map<String, Object> document(UserSettings settings, String id) throws IOException {
        return object(send(settings, "GET", API + "/documents/" + path(id) + "?view=all", null, DOCUMENT, null, Map.of()));
    }

    public Map<String, Object> createDocument(UserSettings settings, Map<String, Object> data) throws IOException {
        return object(send(settings, "POST", API + "/documents", DOCUMENT, DOCUMENT,
                JsonCodec.stringify(data).getBytes(StandardCharsets.UTF_8), Map.of()));
    }

    public Map<String, Object> updateDocument(UserSettings settings, String id, Map<String, Object> data,
            String unmodifiedSince) throws IOException {
        String conditionalDate = ifUnmodifiedSince(unmodifiedSince);
        Map<String, String> headers = conditionalDate == null ? Map.of()
                : Map.of("If-Unmodified-Since", conditionalDate);
        return object(send(settings, "PATCH", API + "/documents/" + path(id), DOCUMENT, DOCUMENT,
                JsonCodec.stringify(data).getBytes(StandardCharsets.UTF_8), headers));
    }

    public List<String> folderDocumentIds(UserSettings settings, String folderId) throws IOException {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : getPages(settings,
                paginated(API + "/folders/" + path(folderId) + "/documents"), DOCUMENT)) {
            String id = value(row.get("id"));
            if (id != null) ids.add(id);
        }
        return ids;
    }

    public void addToFolder(UserSettings settings, String folderId, String documentId) throws IOException {
        try {
            send(settings, "POST", API + "/folders/" + path(folderId) + "/documents", DOCUMENT, DOCUMENT,
                    JsonCodec.stringify(Map.of("id", documentId)).getBytes(StandardCharsets.UTF_8), Map.of());
        } catch (MendeleyApiException error) {
            if (!isExistingFolderMembership(error)) throw error;
        }
    }

    static boolean isExistingFolderMembership(MendeleyApiException error) {
        return error.status == 409
                && error.body.toLowerCase(Locale.ROOT).contains("already exists")
                && error.body.toLowerCase(Locale.ROOT).contains("folder");
    }

    public void removeFromFolder(UserSettings settings, String folderId, String documentId) throws IOException {
        send(settings, "DELETE", API + "/folders/" + path(folderId) + "/documents/" + path(documentId),
                null, DOCUMENT, null, Map.of());
    }

    public List<Map<String, Object>> files(UserSettings settings, String documentId) throws IOException {
        return getPages(settings, paginated(API + "/files?document_id=" + query(documentId)), FILE);
    }

    public List<Map<String, Object>> annotations(UserSettings settings, String documentId) throws IOException {
        return getPages(settings, paginated(API + "/annotations?document_id=" + query(documentId)), ANNOTATION);
    }

    public String documentNote(UserSettings settings, String documentId) throws IOException {
        return annotations(settings, documentId).stream().filter(MendeleyApiClient::isDocumentNote)
                .map(row -> value(row.get("text"))).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    public void updateDocumentNote(UserSettings settings, String documentId, String profileId, String text) throws IOException {
        Map<String, Object> existing = annotations(settings, documentId).stream()
                .filter(MendeleyApiClient::isDocumentNote).findFirst().orElse(null);
        if (existing != null) {
            send(settings, "PATCH", API + "/annotations/" + path(value(existing.get("id"))), ANNOTATION, ANNOTATION,
                    JsonCodec.stringify(Map.of("text", text == null ? "" : text)).getBytes(StandardCharsets.UTF_8), Map.of());
            return;
        }
        if (text == null || text.isBlank()) return;
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("text", text); body.put("privacy_level", "private"); body.put("document_id", documentId);
        if (profileId != null) body.put("profile_id", profileId);
        body.put("color", Map.of("r", 255, "g", 255, "b", 255));
        send(settings, "POST", API + "/annotations", ANNOTATION, ANNOTATION,
                JsonCodec.stringify(body).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    public byte[] downloadFile(UserSettings settings, String fileId) throws IOException {
        return send(settings, "GET", API + "/files/" + path(fileId), null, "application/pdf", null, Map.of()).body();
    }

    public void uploadPdf(UserSettings settings, String documentId, Path pdf, String fileName) throws IOException {
        byte[] bytes = Files.readAllBytes(pdf);
        send(settings, "POST", API + "/files", "application/pdf", FILE, bytes, Map.of(
                "Content-Disposition", "attachment; filename=\"" + safeFileName(fileName) + "\"",
                "Link", "<" + API + "/documents/" + documentId + ">; rel=\"document\""));
    }

    private List<Map<String, Object>> getPages(UserSettings settings, String firstUrl, String accept) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        String url = firstUrl;
        while (url != null) {
            HttpResponse<byte[]> response = send(settings, "GET", url, null, accept, null, Map.of());
            Object parsed = JsonCodec.parse(new String(response.body(), StandardCharsets.UTF_8));
            if (!(parsed instanceof List<?> list)) throw new IOException("Mendeley returned an invalid collection");
            for (Object item : list) if (item instanceof Map<?, ?> map) rows.add(cast(map));
            url = nextLink(response.headers().firstValue("Link").orElse(null));
        }
        return rows;
    }

    private HttpResponse<byte[]> send(UserSettings settings, String method, String url, String contentType,
            String accept, byte[] body, Map<String, String> extraHeaders) throws IOException {
        String accessToken = auth.accessToken(settings);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + accessToken);
        if (accept != null) builder.header("Accept", accept);
        if (contentType != null) builder.header("Content-Type", contentType);
        extraHeaders.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MendeleyApiException(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Mendeley request interrupted", e);
        }
    }

    private static Map<String, Object> object(HttpResponse<byte[]> response) throws IOException {
        Object parsed = JsonCodec.parse(new String(response.body(), StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> map)) throw new IOException("Mendeley returned an invalid object");
        return cast(map);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Map<?, ?> map) { return (Map<String, Object>) map; }
    private static String nextLink(String value) {
        if (value == null) return null;
        for (String part : value.split(",")) {
            if (part.contains("rel=\"next\"") || part.contains("rel=next")) {
                int start = part.indexOf('<'), end = part.indexOf('>');
                if (start >= 0 && end > start) return part.substring(start + 1, end);
            }
        }
        return null;
    }
    private static String path(String value) { return query(value).replace("+", "%20"); }
    private static String query(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static String paginated(String url) { return url + (url.contains("?") ? "&" : "?") + "limit=" + PAGE_SIZE; }
    /** HTTP conditional headers require an RFC 1123 date, whereas Mendeley documents expose ISO-8601 timestamps. */
    static String ifUnmodifiedSince(String remoteModifiedAt) {
        if (remoteModifiedAt == null || remoteModifiedAt.isBlank()) return null;
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.parse(remoteModifiedAt).atOffset(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
    private static String value(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
    private static boolean isDocumentNote(Map<String, Object> row) {
        Object positions = row.get("positions");
        return value(row.get("filehash")) == null && (!(positions instanceof List<?> list) || list.isEmpty());
    }
    private static String safeFileName(String value) { return (value == null ? "paper.pdf" : value).replace("\"", "").replace("\r", "").replace("\n", ""); }

    public static class MendeleyApiException extends IOException {
        public final int status;
        public final String body;
        public MendeleyApiException(int status, String body) {
            super("Mendeley API HTTP " + status + (body.isBlank() ? "" : ": " + body));
            this.status = status;
            this.body = body;
        }
    }
}
