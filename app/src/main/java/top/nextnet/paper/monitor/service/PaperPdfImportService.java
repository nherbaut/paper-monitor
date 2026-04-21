package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import top.nextnet.paper.monitor.model.Paper;

@ApplicationScoped
public class PaperPdfImportService {

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
        return List.of(paper.openAccessLink, paper.sourceLink).stream()
                .map(this::normalizeArxivPdfUrl)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    public ImportedPdf importSupportedPdf(Paper paper) throws IOException, InterruptedException {
        String pdfUrl = supportedPdfUrl(paper)
                .orElseThrow(() -> new IllegalArgumentException("This paper does not expose a supported remote PDF source"));
        DownloadedPdf downloadedPdf = downloadPdf(pdfUrl, paper == null ? null : paper.title);
        Path tempFile = Files.createTempFile("paper-monitor-import-", ".pdf");
        try {
            Files.write(tempFile, downloadedPdf.content());
            PaperStorageService.StoredPdf storedPdf = paperStorageService.storePdf(tempFile, downloadedPdf.fileName());
            return new ImportedPdf(storedPdf, pdfUrl);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public DownloadedPdf downloadPdf(String pdfUrl, String paperTitle) throws IOException, InterruptedException {
        if (pdfUrl == null || pdfUrl.isBlank()) {
            throw new IllegalArgumentException("Remote PDF URL is required");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(pdfUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/pdf")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Remote PDF download failed with status " + response.statusCode());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String contentDisposition = response.headers().firstValue("Content-Disposition").orElse("");
        String lowerType = contentType.toLowerCase(Locale.ROOT);
        boolean looksLikePdf = lowerType.contains("application/pdf")
                || contentDisposition.toLowerCase(Locale.ROOT).contains(".pdf")
                || pdfUrl.toLowerCase(Locale.ROOT).endsWith(".pdf")
                || startsWithPdfMagic(response.body());
        if (!looksLikePdf) {
            throw new IOException("Remote URL did not return a PDF");
        }
        return new DownloadedPdf(suggestedFileName(pdfUrl, paperTitle), response.body());
    }

    private Optional<String> normalizeArxivPdfUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(candidate.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || !host.toLowerCase(Locale.ROOT).endsWith("arxiv.org")) {
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

    private boolean startsWithPdfMagic(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return false;
        }
        return new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("%PDF");
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
