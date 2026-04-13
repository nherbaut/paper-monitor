package top.nextnet.paper.monitor.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.UserSettingsRepository;

@ApplicationScoped
public class TtsService {

    private static final Pattern BRACKET_REFERENCE_GROUP = Pattern.compile("(?:\\s*\\[(?:\\d+(?:\\s*,\\s*\\d+)*)])(,\\s*\\[(?:\\d+(?:\\s*,\\s*\\d+)*)])*(?=\\s|[.,;:)]|$)");
    private static final Pattern AUTHOR_YEAR_PAREN = Pattern.compile("\\s*\\((?=[^)]*\\b(?:19|20)\\d{2}[a-z]?\\b)(?:[^()]{0,120})\\)");
    private static final Pattern HYPHENATED_LINEBREAK = Pattern.compile("(?<=[A-Za-z])-(?=\\s+[a-z])");

    private final HttpClient httpClient;
    private final String speakUrl;
    private final String voicesUrl;
    private final String defaultVoice;
    private final UserSettingsRepository userSettingsRepository;
    private final Instance<CurrentUserContext> currentUserContext;

    public TtsService(
            @ConfigProperty(name = "paper-monitor.tts.base-url", defaultValue = "http://localhost:8090") String baseUrl,
            @ConfigProperty(name = "paper-monitor.tts.default-voice", defaultValue = "") String defaultVoice,
            UserSettingsRepository userSettingsRepository,
            Instance<CurrentUserContext> currentUserContext
    ) {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.speakUrl = normalizeBaseUrl(baseUrl) + "/v1/speak";
        this.voicesUrl = normalizeBaseUrl(baseUrl) + "/voices";
        this.defaultVoice = defaultVoice == null ? "" : defaultVoice.trim();
        this.userSettingsRepository = userSettingsRepository;
        this.currentUserContext = currentUserContext;
    }

    public java.util.List<String> availableVoices() throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(voicesUrl))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("TTS voices request interrupted", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = new String(response.body(), StandardCharsets.UTF_8).trim();
            throw new IOException(detail.isEmpty()
                    ? "TTS voices service failed with HTTP " + response.statusCode()
                    : detail);
        }

        Object parsed = JsonCodec.parse(new String(response.body(), StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IOException("Invalid TTS voices response");
        }
        Object voices = root.get("voices");
        if (!(voices instanceof Iterable<?> iterable)) {
            return java.util.List.of();
        }

        java.util.List<String> result = new java.util.ArrayList<>();
        for (Object item : iterable) {
            if (item != null) {
                String voice = String.valueOf(item).trim();
                if (!voice.isEmpty()) {
                    result.add(voice);
                }
            }
        }
        return result;
    }

    public byte[] speak(String text, String voice) throws IOException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text is required");
        }

        String normalizedText = normalizeForSpeech(text);
        if (normalizedText.isBlank()) {
            throw new IllegalArgumentException("Text is empty after speech cleanup");
        }

        UserSettings settings = currentSettings();
        String configuredVoice = settings == null || settings.voice == null ? "" : settings.voice.trim();
        String selectedVoice = voice == null || voice.isBlank()
                ? (configuredVoice.isBlank() ? defaultVoice : configuredVoice)
                : voice.trim();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", normalizedText);
        if (!selectedVoice.isBlank()) {
            payload.put("voice", selectedVoice);
        }
        double speedMultiplier = settings == null ? 1.1d : settings.effectiveSpeedMultiplier();
        payload.put("length_scale", round(1.0d / speedMultiplier));
        String requestBody = JsonCodec.stringify(payload);

        Log.infof("TTS request -> %s body=%s", speakUrl, summarize(requestBody));

        byte[] requestBytes = requestBody.getBytes(StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(speakUrl))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .header("Accept", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("TTS request interrupted", e);
        }

        Log.infof("TTS response <- %s status=%d content-type=%s",
                speakUrl,
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse("unknown"));

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = new String(response.body(), StandardCharsets.UTF_8).trim();
            Log.errorf("TTS error body <- %s %s", speakUrl, summarize(detail));
            throw new IOException(detail.isEmpty()
                    ? "TTS service failed with HTTP " + response.statusCode()
                    : detail);
        }

        return response.body();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8090";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String summarize(String value) {
        if (value == null) {
            return "null";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 500 ? singleLine.substring(0, 500) + "..." : singleLine;
    }

    private double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    private UserSettings currentSettings() {
        if (currentUserContext.isResolvable() && currentUserContext.get().user() != null) {
            return userSettingsRepository.findByUser(currentUserContext.get().user()).orElse(null);
        }
        return null;
    }

    private String normalizeForSpeech(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        normalized = HYPHENATED_LINEBREAK.matcher(normalized).replaceAll("");
        normalized = BRACKET_REFERENCE_GROUP.matcher(normalized).replaceAll("");
        normalized = AUTHOR_YEAR_PAREN.matcher(normalized).replaceAll("");
        normalized = normalized.replaceAll("\\s+([,.;:])", "$1");
        normalized = normalized.replaceAll("([,;:])\\s*([,;:])", "$2");
        normalized = normalized.replaceAll("\\s{2,}", " ");
        normalized = normalized.replaceAll("\\(\\s*\\)", "");
        normalized = normalized.replaceAll("\\s+\\.", ".");
        normalized = normalized.replaceAll("\\s+,", ",");
        normalized = normalized.replaceAll("\\s+\\)", ")");
        normalized = normalized.replaceAll("\\(\\s+", "(");
        normalized = normalized.trim();

        if (normalized.endsWith(",") || normalized.endsWith(";") || normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }

        return normalized;
    }
}
