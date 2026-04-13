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

        HttpRequest request = HttpRequest.newBuilder(URI.create(pdfUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/pdf")
                .GET()
                .build();

        Path tempFile = Files.createTempFile("paper-monitor-import-", ".pdf");
        try {
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Remote PDF download failed with status " + response.statusCode());
            }
            PaperStorageService.StoredPdf storedPdf = paperStorageService.storePdf(tempFile, suggestedFileName(pdfUrl, paper));
            return new ImportedPdf(storedPdf, pdfUrl);
        } finally {
            Files.deleteIfExists(tempFile);
        }
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

    private String suggestedFileName(String pdfUrl, Paper paper) {
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
        String title = paper == null || paper.title == null || paper.title.isBlank()
                ? "paper"
                : paper.title.replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        return (title.isBlank() ? "paper" : title) + ".pdf";
    }

    public record ImportedPdf(PaperStorageService.StoredPdf storedPdf, String sourceUrl) {
    }
}
