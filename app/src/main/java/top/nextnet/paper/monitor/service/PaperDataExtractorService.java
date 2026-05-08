package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
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

@ApplicationScoped
public class PaperDataExtractorService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private static final String DEFAULT_BASE_URL = "http://localhost:8091";

    public PaperDataExtractorService(
            @ConfigProperty(name = "paper-monitor.pde.api-base-url", defaultValue = "") String apiBaseUrl,
            @ConfigProperty(name = "paper-monitor.pde.base-url", defaultValue = "") String legacyBaseUrl
    ) {
        this.baseUrl = trimTrailingSlash(firstNonBlank(apiBaseUrl, legacyBaseUrl, DEFAULT_BASE_URL));
    }

    public List<ReviewTemplateSummary> listReviewTemplates() {
        Object payload = getJson("/api/review-designs");
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
                    stringValue(item.get("title"))));
        }
        return templates;
    }

    public ReviewTemplateDetail loadReviewTemplate(String templateId) {
        Object payload = getJson("/api/review-designs/" + urlEncode(templateId));
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

    private Object getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WebApplicationException("Paper Data Extractor returned " + response.statusCode(),
                        Response.Status.BAD_GATEWAY);
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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

    public record ReviewTemplateSummary(String id, String title) {
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
