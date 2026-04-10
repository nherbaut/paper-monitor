package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DoiMetadataService {

    private final HttpClient httpClient;
    private final String doiServiceBaseUrl;

    public DoiMetadataService(
            @ConfigProperty(name = "paper-monitor.doi-metadata.base-url",
                    defaultValue = "https://scholar.miage.dev/doi/") String doiServiceBaseUrl
    ) {
        this.httpClient = HttpClient.newHttpClient();
        this.doiServiceBaseUrl = doiServiceBaseUrl.endsWith("/")
                ? doiServiceBaseUrl
                : doiServiceBaseUrl + "/";
    }

    public DoiMetadata fetch(String doi) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(doiServiceBaseUrl + URLEncoder.encode(doi, StandardCharsets.UTF_8)))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("DOI metadata request interrupted", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("DOI metadata lookup failed with HTTP " + response.statusCode());
        }

        Object parsed = JsonCodec.parse(response.body());
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException("DOI metadata service returned invalid JSON");
        }

        String preciseDate = stringValue(map.get("x-precise-date"));
        LocalDate publishedOn = null;
        if (preciseDate != null && preciseDate.length() >= 10) {
            publishedOn = LocalDate.parse(preciseDate.substring(0, 10));
        }

        return new DoiMetadata(
                normalizeUrl(stringValue(map.get("doi"))),
                stringValue(map.get("title")),
                stringValue(map.get("X-authors")),
                stringValue(map.get("pubtitle")),
                stringValue(map.get("X-abstract")),
                normalizeUrl(stringValue(map.get("X-OA-URL"))),
                publishedOn
        );
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record DoiMetadata(
            String doiUrl,
            String title,
            String authors,
            String publisher,
            String summary,
            String openAccessUrl,
            LocalDate publishedOn
    ) {
    }
}
