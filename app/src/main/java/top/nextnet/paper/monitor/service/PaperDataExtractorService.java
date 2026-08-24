package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import top.nextnet.paper.monitor.model.AppUser;

@ApplicationScoped
public class PaperDataExtractorService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final String baseUrl;
    private final String internalApiToken;
    private static final String DEFAULT_BASE_URL = "http://localhost:8091";

    public PaperDataExtractorService(
            @ConfigProperty(name = "paper-monitor.pde.api-base-url", defaultValue = "") String apiBaseUrl,
            @ConfigProperty(name = "paper-monitor.pde.base-url", defaultValue = "") String legacyBaseUrl,
            @ConfigProperty(name = "paper-monitor.pde.internal-api-token", defaultValue = "") String internalApiToken
    ) {
        this.baseUrl = trimTrailingSlash(firstNonBlank(apiBaseUrl, legacyBaseUrl, DEFAULT_BASE_URL));
        this.internalApiToken = internalApiToken == null ? "" : internalApiToken.trim();
    }

    public List<ReviewTemplateSummary> listReviewTemplates(AppUser user) {
        Object payload = getJson("/api/review-designs", user);
        if (!(payload instanceof List<?> rows)) {
            return List.of();
        }
        List<ReviewTemplateSummary> templates = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> item)) {
                continue;
            }
            templates.add(new ReviewTemplateSummary(
                    stringValue(item.get("id")),
                    stringValue(item.get("title")),
                    stringValue(item.get("derivation_id")),
                    integerValue(item.get("revision")),
                    booleanValue(item.get("is_latest_revision"), true),
                    booleanValue(item.get("owned_by_current_user"), false),
                    booleanValue(item.get("can_write"), false)));
        }
        return templates.stream().filter(ReviewTemplateSummary::latestRevision).toList();
    }

    public ReviewTemplateDetail loadReviewTemplate(String templateId, AppUser user) {
        return detail(getJson("/api/review-designs/" + urlEncode(templateId), user));
    }

    public ReviewTemplateDetail deriveReviewTemplate(
            String templateId,
            String title,
            List<Map<String, Object>> researchQuestions,
            AppUser user
    ) {
        return detail(postJson(
                "/api/review-designs/" + urlEncode(templateId) + "/derivations",
                Map.of("title", title, "research_questions", researchQuestions),
                user));
    }

    public ReviewTemplateDetail reviseReviewTemplate(
            String templateId,
            String title,
            List<Map<String, Object>> researchQuestions,
            AppUser user
    ) {
        return detail(postJson(
                "/api/review-designs/" + urlEncode(templateId) + "/revisions",
                Map.of("title", title, "research_questions", researchQuestions),
                user));
    }

    public Optional<ReviewTemplateDetail> findMatchingDerivation(
            String baseTemplateId,
            String title,
            List<Map<String, Object>> researchQuestions,
            AppUser user
    ) {
        for (ReviewTemplateSummary summary : listReviewTemplates(user)) {
            if (!summary.ownedByCurrentUser()
                    || summary.derivationId() == null
                    || !Objects.equals(normalizeText(title), normalizeText(summary.title()))) {
                continue;
            }
            ReviewTemplateDetail candidate;
            try {
                candidate = loadReviewTemplate(summary.id(), user);
            } catch (WebApplicationException exception) {
                if (exception.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                    continue;
                }
                throw exception;
            }
            Map<String, Object> design = candidate.reviewDesign();
            if (Objects.equals(baseTemplateId, stringValue(design.get("derived_from_review_design_id")))
                    && matchingResearchQuestions(researchQuestions, design.get("research_questions"))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private ReviewTemplateDetail detail(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Unexpected review template payload");
        }
        return new ReviewTemplateDetail(
                stringValue(map.get("id")),
                copyObjectMap(map.get("review_design")),
                copyObjectMap(map.get("form_schema")),
                copyObjectMap(map.get("review_json_schema")),
                copyObjectMap(map.get("review_linkml_schema")));
    }

    private Object getJson(String path, AppUser user) {
        HttpRequest request = requestBuilder(path, user)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return sendJson(request);
    }

    private Object postJson(String path, Map<String, Object> payload, AppUser user) {
        byte[] requestBody = JsonCodec.stringify(payload).getBytes(StandardCharsets.UTF_8);
        HttpRequest request = requestBuilder(path, user)
                .timeout(Duration.ofSeconds(120))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();
        return sendJson(request);
    }

    static boolean matchingResearchQuestions(List<Map<String, Object>> requested, Object candidateValue) {
        if (!(candidateValue instanceof List<?> candidates) || requested.size() != candidates.size()) {
            return false;
        }
        for (int index = 0; index < requested.size(); index++) {
            if (!(candidates.get(index) instanceof Map<?, ?> candidate)) {
                return false;
            }
            Map<String, Object> expected = requested.get(index);
            if (!Objects.equals(normalizeText(expected.get("question")), normalizeText(candidate.get("question")))
                    || booleanValue(expected.get("required"), false)
                    != booleanValue(candidate.get("required"), false)) {
                return false;
            }
        }
        return true;
    }

    private HttpRequest.Builder requestBuilder(String path, AppUser user) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("X-PDE-Internal-Token", internalApiToken);
        if (user != null) {
            builder.header("X-Forwarded-User-Id", String.valueOf(user.id));
            builder.header("X-Forwarded-Username", safeHeader(user.username));
            builder.header("X-Forwarded-Display-Name", safeHeader(user.displayLabel()));
            builder.header("X-Forwarded-Email", safeHeader(user.email));
            builder.header("X-Forwarded-Admin", String.valueOf(user.isAdmin()));
        }
        return builder;
    }

    private Object sendJson(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                Response.Status status = switch (response.statusCode()) {
                    case 400, 409, 422 -> Response.Status.BAD_REQUEST;
                    case 403 -> Response.Status.FORBIDDEN;
                    case 404 -> Response.Status.NOT_FOUND;
                    default -> Response.Status.BAD_GATEWAY;
                };
                String message = upstreamErrorMessage(response.statusCode(), response.body());
                Log.warnf("Paper Data Extractor request %s returned %d: %s",
                        request.uri().getPath(), response.statusCode(), message);
                throw new WebApplicationException(Response.status(status)
                        .type("text/plain; charset=UTF-8")
                        .entity(message)
                        .build());
            }
            return JsonCodec.parse(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new WebApplicationException("Paper Data Extractor is unavailable: " + e.getMessage(),
                    Response.Status.BAD_GATEWAY);
        }
    }

    static String upstreamErrorMessage(int statusCode, String body) {
        if (body == null || body.isBlank()) {
            return "Paper Data Extractor returned " + statusCode;
        }
        String message = body.trim();
        try {
            Object payload = JsonCodec.parse(message);
            if (payload instanceof Map<?, ?> map && map.get("detail") != null) {
                message = String.valueOf(map.get("detail")).trim();
            }
        } catch (RuntimeException ignored) {
            // Preserve a non-JSON upstream error as plain text.
        }
        if (message.isBlank()) {
            return "Paper Data Extractor returned " + statusCode;
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String normalizeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeHeader(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value == null ? DEFAULT_BASE_URL : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record ReviewTemplateSummary(
            String id,
            String title,
            String derivationId,
            Integer revision,
            boolean latestRevision,
            boolean ownedByCurrentUser,
            boolean canWrite
    ) {
    }

    public record ReviewTemplateDetail(
            String id,
            Map<String, Object> reviewDesign,
            Map<String, Object> formSchema,
            Map<String, Object> reviewJsonSchema,
            Map<String, Object> reviewLinkmlSchema
    ) {
    }
}
