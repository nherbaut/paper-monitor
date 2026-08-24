package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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
public class ScholarService {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String feedsUrl;
    private final String historyUrl;

    @Inject
    public ScholarService(
            @ConfigProperty(name = "paper-monitor.scholar.base-url", defaultValue = "https://scholar.miage.dev") String baseUrl,
            @ConfigProperty(name = "paper-monitor.scholar.feeds-url", defaultValue = "https://scholar.miage.dev/feeds") String feedsUrl,
            @ConfigProperty(name = "paper-monitor.scholar.history-url", defaultValue = "https://scholar.miage.dev/history") String historyUrl
    ) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), baseUrl, feedsUrl, historyUrl);
    }

    ScholarService(HttpClient httpClient, String baseUrl, String feedsUrl, String historyUrl) {
        this.httpClient = httpClient;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.feedsUrl = feedsUrl;
        this.historyUrl = historyUrl;
    }

    public List<Map<String, Object>> feeds() {
        return normalizeRssUrls(parseFeeds(fetchJson(feedsUrl)));
    }

    public List<Map<String, Object>> history() {
        return normalizeRssUrls(parseHistory(fetchJson(historyUrl)));
    }

    public Map<String, Object> createFeedFromHistory(long queryId) {
        String url = baseUrl + "/history/" + URLEncoder.encode(String.valueOf(queryId), StandardCharsets.UTF_8)
                + "/feed?format=json";
        return parseCreatedFeed(fetchJson(url, "POST"), queryId, baseUrl);
    }

    public static List<Map<String, Object>> parseFeeds(String json) {
        Object parsed = JsonCodec.parse(json);
        if (!(parsed instanceof List<?> list)) {
            throw invalidScholarJson("Scholar feeds response was not a JSON array");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                Object id = map.get("id");
                row.put("id", numberValue(id));
                row.put("query", stringValue(map.get("query")));
                row.put("count", numberValue(map.get("count")));
                row.put("hit", numberValue(map.get("hit")));
                row.put("lastBuildDate", stringValue(map.get("lastBuildDate")));
                row.put("rssUrl", firstString(map, "rss_url", "rssUrl", "url"));
                rows.add(row);
            }
        }
        return rows;
    }

    public static List<Map<String, Object>> parseHistory(String json) {
        Object parsed = JsonCodec.parse(json);
        if (!(parsed instanceof List<?> list)) {
            throw invalidScholarJson("Scholar history response was not a JSON array");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                Object queryId = firstValue(map, "query_id", "queryId", "id");
                row.put("id", numberValue(firstValue(map, "id", "query_id", "queryId")));
                row.put("queryId", numberValue(queryId));
                row.put("query", stringValue(map.get("query")));
                row.put("count", numberValue(map.get("count")));
                row.put("timestamp", stringValue(map.get("timestamp")));
                row.put("fetched", booleanValue(map.get("fetched")));
                row.put("permalinkUrl", firstString(map, "permalink_url", "permalinkUrl"));
                row.put("rssUrl", firstString(map, "rss_url", "rssUrl"));
                row.put("feedCreateUrl", firstString(map, "feed_create_url", "feedCreateUrl"));
                rows.add(row);
            }
        }
        return rows;
    }

    public static Map<String, Object> parseCreatedFeed(String json, long queryId, String baseUrl) {
        Object parsed = JsonCodec.parse(json);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw invalidScholarJson("Scholar feed creation response was not a JSON object");
        }
        Object id = firstValue(map, "id", "feed_id", "feedId");
        String rssUrl = firstString(map, "rss_url", "rssUrl", "url");
        if (rssUrl == null && id != null) {
            rssUrl = trimTrailingSlash(baseUrl) + "/feed/" + id + ".rss";
        }
        if (rssUrl == null) {
            rssUrl = trimTrailingSlash(baseUrl) + "/feed/" + queryId + ".rss";
        }
        rssUrl = normalizeScholarUrl(rssUrl, baseUrl);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", numberValue(id));
        row.put("queryId", queryId);
        row.put("query", stringValue(map.get("query")));
        row.put("count", numberValue(map.get("count")));
        row.put("hit", numberValue(map.get("hit")));
        row.put("lastBuildDate", stringValue(map.get("lastBuildDate")));
        row.put("rssUrl", rssUrl);
        return row;
    }

    private List<Map<String, Object>> normalizeRssUrls(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            String rssUrl = stringValue(row.get("rssUrl"));
            if (rssUrl != null) {
                row.put("rssUrl", normalizeScholarUrl(rssUrl, baseUrl));
            }
        }
        return rows;
    }

    static String normalizeScholarUrl(String url, String baseUrl) {
        try {
            URI candidate = URI.create(url);
            URI base = URI.create(baseUrl);
            if ("https".equalsIgnoreCase(base.getScheme())
                    && "http".equalsIgnoreCase(candidate.getScheme())
                    && base.getHost() != null
                    && base.getHost().equalsIgnoreCase(candidate.getHost())) {
                StringBuilder relative = new StringBuilder(candidate.getRawPath());
                if (candidate.getRawQuery() != null) {
                    relative.append('?').append(candidate.getRawQuery());
                }
                if (candidate.getRawFragment() != null) {
                    relative.append('#').append(candidate.getRawFragment());
                }
                return base.resolve(relative.toString()).toString();
            }
        } catch (IllegalArgumentException ignored) {
            // Preserve the original value so the caller reports the malformed URL.
        }
        return url;
    }

    private String fetchJson(String url) {
        return fetchJson(url, "GET");
    }

    private String fetchJson(String url, String method) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json");
        HttpRequest request = ("POST".equalsIgnoreCase(method)
                ? builder.POST(HttpRequest.BodyPublishers.noBody())
                : builder.GET())
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebApplicationException("Scholar request interrupted", Response.Status.BAD_GATEWAY);
        } catch (IOException | IllegalArgumentException e) {
            throw new WebApplicationException("Scholar request failed: " + e.getMessage(), Response.Status.BAD_GATEWAY);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new WebApplicationException("Scholar request failed with HTTP " + response.statusCode(),
                    Response.Status.BAD_GATEWAY);
        }
        return response.body();
    }

    private static Object firstValue(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstString(Map<?, ?> map, String... keys) {
        Object value = firstValue(map, keys);
        return stringValue(value);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static Object numberValue(Object value) {
        return value instanceof Number ? value : stringValue(value);
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = stringValue(value);
        return normalized == null ? null : Boolean.parseBoolean(normalized);
    }

    private static WebApplicationException invalidScholarJson(String message) {
        return new WebApplicationException(message, Response.Status.BAD_GATEWAY);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
