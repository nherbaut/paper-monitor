package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import top.nextnet.paper.monitor.model.Paper;

@ApplicationScoped
public class PaperPdfImportService {

    private static final long MINIMUM_ARXIV_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final ReentrantLock ARXIV_DOWNLOAD_LOCK = new ReentrantLock();
    private static long lastArxivRequestNanos;

    private static final Pattern ARXIV_DOI_PATTERN = Pattern.compile(
            "(?i)^10\\.48550/arxiv\\.(.+)$");
    private static final Pattern ARXIV_ID_PATTERN = Pattern.compile(
            "(?i)^(?:[0-9]{4}\\.[0-9]{4,5}|[a-z0-9.-]+/[0-9]{7})(?:v[0-9]+)?$");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final PaperStorageService paperStorageService;

    public PaperPdfImportService(PaperStorageService paperStorageService) {
        this.paperStorageService = paperStorageService;
    }

    public Optional<String> supportedPdfUrl(Paper paper) {
        if (paper == null) {
            return Optional.empty();
        }
        return supportedPdfUrl(paper.openAccessLink, paper.sourceLink);
    }

    public Optional<String> supportedPdfUrl(String... candidates) {
        if (candidates == null) {
            return Optional.empty();
        }
        return Arrays.stream(candidates)
                .map(this::normalizeArxivPdfUrl)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    public ImportedPdf importSupportedPdf(Paper paper) throws IOException, InterruptedException {
        String pdfUrl = supportedPdfUrl(paper)
                .orElseThrow(() -> new IllegalArgumentException("This paper does not expose a supported remote PDF source"));
        return importPdfRespectfully(pdfUrl, paper == null ? null : paper.title);
    }

    public ImportedPdf importPdfRespectfully(String pdfUrl, String paperTitle) throws IOException, InterruptedException {
        ARXIV_DOWNLOAD_LOCK.lockInterruptibly();
        try {
            long remaining = MINIMUM_ARXIV_INTERVAL_NANOS - (System.nanoTime() - lastArxivRequestNanos);
            if (lastArxivRequestNanos != 0L && remaining > 0L) {
                TimeUnit.NANOSECONDS.sleep(remaining);
            }
            lastArxivRequestNanos = System.nanoTime();
            return importPdf(pdfUrl, paperTitle);
        } finally {
            ARXIV_DOWNLOAD_LOCK.unlock();
        }
    }

    public ImportedPdf importPdf(String pdfUrl, String paperTitle) throws IOException, InterruptedException {
        if (pdfUrl == null || pdfUrl.isBlank()) {
            throw new IllegalArgumentException("Remote PDF URL is required");
        }
        Path tempFile = Files.createTempFile("paper-monitor-import-", ".pdf");
        try {
            HttpRequest request = pdfRequest(pdfUrl);
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            byte[] magic;
            try (var input = Files.newInputStream(tempFile)) {
                magic = input.readNBytes(1024);
            }
            validatePdfResponse(response.statusCode(), magic);
            PaperStorageService.StoredPdf storedPdf = paperStorageService.storePdf(
                    tempFile, suggestedFileName(pdfUrl, paperTitle));
            return new ImportedPdf(storedPdf, pdfUrl);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public DownloadedPdf downloadPdf(String pdfUrl, String paperTitle) throws IOException, InterruptedException {
        if (pdfUrl == null || pdfUrl.isBlank()) {
            throw new IllegalArgumentException("Remote PDF URL is required");
        }
        HttpRequest request = pdfRequest(pdfUrl);
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        validatePdfResponse(response.statusCode(), response.body());
        return new DownloadedPdf(suggestedFileName(pdfUrl, paperTitle), response.body());
    }

    private HttpRequest pdfRequest(String pdfUrl) {
        return HttpRequest.newBuilder(URI.create(pdfUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/pdf")
                .header("User-Agent", "PaperMonitor/1.0")
                .GET()
                .build();
    }

    private void validatePdfResponse(int statusCode, byte[] content) throws IOException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("Remote PDF download failed with status " + statusCode);
        }
        if (!startsWithPdfMagic(content)) {
            throw new IOException("Remote URL did not return a PDF");
        }
    }

    private Optional<String> normalizeArxivPdfUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        String normalizedCandidate = candidate.trim();
        Optional<String> doiPdfUrl = normalizeArxivDoiPdfUrl(normalizedCandidate);
        if (doiPdfUrl.isPresent()) {
            return doiPdfUrl;
        }
        URI uri;
        try {
            uri = URI.create(normalizedCandidate);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (!isHost(host, "arxiv.org")) {
            return Optional.empty();
        }
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        if (path.startsWith("/abs/")) {
            String identifier = path.substring("/abs/".length());
            return Optional.of("https://arxiv.org/pdf/" + identifier + ".pdf");
        }
        if (path.startsWith("/pdf/")) {
            return Optional.of(path.endsWith(".pdf") ? candidate.trim() : "https://arxiv.org" + path + ".pdf");
        }
        return Optional.empty();
    }

    private Optional<String> normalizeArxivDoiPdfUrl(String candidate) {
        String doi = candidate;
        try {
            URI uri = URI.create(candidate);
            String host = uri.getHost();
            if (isHost(host, "doi.org")) {
                doi = uri.getPath();
                if (doi != null && doi.startsWith("/")) {
                    doi = doi.substring(1);
                }
            } else if (host != null) {
                return Optional.empty();
            }
        } catch (IllegalArgumentException ignored) {
            // The candidate may be a bare DOI rather than a URL.
        }
        if (doi == null) {
            return Optional.empty();
        }
        doi = doi.replaceFirst("(?i)^doi:\\s*", "");
        Matcher matcher = ARXIV_DOI_PATTERN.matcher(doi);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String identifier = matcher.group(1);
        if (!ARXIV_ID_PATTERN.matcher(identifier).matches()) {
            return Optional.empty();
        }
        return Optional.of("https://arxiv.org/pdf/" + identifier + ".pdf");
    }

    private boolean startsWithPdfMagic(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return false;
        }
        int limit = Math.min(bytes.length - 3, 1024);
        for (int index = 0; index < limit; index++) {
            if (bytes[index] == '%' && bytes[index + 1] == 'P'
                    && bytes[index + 2] == 'D' && bytes[index + 3] == 'F') {
                return true;
            }
        }
        return false;
    }

    private boolean isHost(String candidate, String expected) {
        if (candidate == null) return false;
        String normalized = candidate.toLowerCase(Locale.ROOT);
        return normalized.equals(expected) || normalized.endsWith("." + expected);
    }

    private String suggestedFileName(String pdfUrl, Paper paper) {
        return suggestedFileName(pdfUrl, paper == null ? null : paper.title);
    }

    private String suggestedFileName(String pdfUrl, String paperTitle) {
        String fromUrl = URI.create(pdfUrl).getPath();
        if (fromUrl != null) {
            int lastSlash = fromUrl.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < fromUrl.length() - 1) {
                String fileName = fromUrl.substring(lastSlash + 1);
                if (fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    return fileName;
                }
            }
        }
        String title = paperTitle == null || paperTitle.isBlank()
                ? "paper"
                : paperTitle.replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        return (title.isBlank() ? "paper" : title) + ".pdf";
    }

    public record ImportedPdf(PaperStorageService.StoredPdf storedPdf, String sourceUrl) {
    }

    public record DownloadedPdf(String fileName, byte[] content) {
    }
}
