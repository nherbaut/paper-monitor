package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@ApplicationScoped
public class PaperStorageService {

    private final Path storageRoot;

    public PaperStorageService(@ConfigProperty(name = "paper-monitor.storage.root", defaultValue = "uploads") String storageRoot) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public StoredPdf storePdf(FileUpload upload) throws IOException {
        if (upload == null) {
            throw new IllegalArgumentException("PDF upload is required");
        }

        String originalFileName = upload.fileName();
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("PDF filename is required");
        }

        if (!originalFileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are supported");
        }

        Files.createDirectories(storageRoot);

        String storedFileName = UUID.randomUUID() + ".pdf";
        Path target = storageRoot.resolve(storedFileName);
        Files.copy(upload.uploadedFile(), target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredPdf(storedFileName, originalFileName);
    }

    public StoredAsset storeImage(FileUpload upload) throws IOException {
        if (upload == null) {
            throw new IllegalArgumentException("Image upload is required");
        }

        String originalFileName = upload.fileName();
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("Image filename is required");
        }

        String extension = extractExtension(originalFileName);
        if (!isSupportedImageExtension(extension)) {
            throw new IllegalArgumentException("Only PNG, JPG, GIF, and WebP images are supported");
        }

        Files.createDirectories(storageRoot);

        String storedFileName = UUID.randomUUID() + "." + extension;
        Path target = storageRoot.resolve(storedFileName);
        Files.copy(upload.uploadedFile(), target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredAsset(storedFileName, originalFileName);
    }

    public Path resolve(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new IllegalArgumentException("Stored PDF path is required");
        }
        Path resolved = storageRoot.resolve(storedPath).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Stored PDF path is invalid");
        }
        return resolved;
    }

    public void deleteIfExists(String storedPath) throws IOException {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        Files.deleteIfExists(resolve(storedPath));
    }

    public List<Path> listStoredFiles() throws IOException {
        Files.createDirectories(storageRoot);
        try (var stream = Files.walk(storageRoot)) {
            return stream.filter(Files::isRegularFile).sorted().toList();
        }
    }

    public Path root() throws IOException {
        Files.createDirectories(storageRoot);
        return storageRoot;
    }

    public void clearAll() throws IOException {
        Files.createDirectories(storageRoot);
        try (var stream = Files.walk(storageRoot)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(storageRoot))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    public void write(String storedPath, InputStream inputStream) throws IOException {
        Path target = resolve(storedPath);
        Files.createDirectories(target.getParent());
        Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public InputStream open(String storedPath) throws IOException {
        return Files.newInputStream(resolve(storedPath), StandardOpenOption.READ);
    }

    private String extractExtension(String fileName) {
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart < 0 || extensionStart == fileName.length() - 1) {
            throw new IllegalArgumentException("File extension is required");
        }
        return fileName.substring(extensionStart + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isSupportedImageExtension(String extension) {
        return extension.equals("png")
                || extension.equals("jpg")
                || extension.equals("jpeg")
                || extension.equals("gif")
                || extension.equals("webp");
    }

    public record StoredPdf(String storedPath, String originalFileName) {
    }

    public record StoredAsset(String storedPath, String originalFileName) {
    }
}
