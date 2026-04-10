package top.nextnet.paper.monitor.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.repo.PaperRepository;

@ApplicationScoped
public class PaperGitSyncService {

    private final Path gitRoot;
    private final PaperRepository paperRepository;
    private final PaperStorageService paperStorageService;
    private final PaperEventService paperEventService;

    public PaperGitSyncService(
            @ConfigProperty(name = "paper-monitor.git.root", defaultValue = "git-remotes") String gitRoot,
            PaperRepository paperRepository,
            PaperStorageService paperStorageService,
            PaperEventService paperEventService
    ) {
        this.gitRoot = Path.of(gitRoot).toAbsolutePath().normalize();
        this.paperRepository = paperRepository;
        this.paperStorageService = paperStorageService;
        this.paperEventService = paperEventService;
    }

    public void syncLogicalFeeds(List<LogicalFeed> logicalFeeds) {
        for (LogicalFeed logicalFeed : logicalFeeds) {
            syncLogicalFeed(logicalFeed);
        }
    }

    public void syncLogicalFeed(LogicalFeed logicalFeed) {
        logicalFeed.gitSyncError = null;
        Map<String, String> tokenMap = logicalFeed.stateGitLinkMap();
        boolean changed = false;
        for (String state : logicalFeed.workflowStateList()) {
            if (!tokenMap.containsKey(state)) {
                tokenMap.put(state, UUID.randomUUID().toString());
                changed = true;
            }
        }
        if (changed) {
            logicalFeed.setStateGitLinkMap(tokenMap);
        }

        logicalFeed.gitRemotes = new ArrayList<>();
        for (String state : logicalFeed.workflowStateList()) {
            String token = tokenMap.get(state);
            if (token == null || token.isBlank()) {
                continue;
            }
            Path repoPath = repoPath(token);
            try {
                ensureRepo(repoPath, logicalFeed, state);
                importRepoContents(logicalFeed, state, repoPath);
                exportRepoContents(logicalFeed, state, repoPath);
            } catch (IOException e) {
                logicalFeed.gitSyncError = "Git mirror sync failed: " + e.getMessage();
                Log.errorf(e, "Failed to sync git export for %s / %s", logicalFeed.name, state);
                logicalFeed.gitRemotes = new ArrayList<>();
                return;
            }
            logicalFeed.gitRemotes.add(new LogicalFeed.StateGitRemote(state, repoUrl(repoPath)));
        }
    }

    private void ensureRepo(Path repoPath, LogicalFeed logicalFeed, String state) throws IOException {
        Files.createDirectories(repoPath);
        if (!Files.exists(repoPath.resolve(".git"))) {
            runGit(repoPath.getParent(), "init", repoPath.getFileName().toString());
            runGit(repoPath, "config", "user.name", "Paper Monitor");
            runGit(repoPath, "config", "user.email", "paper-monitor@local");
            runGit(repoPath, "config", "receive.denyCurrentBranch", "updateInstead");
            writeReadme(repoPath, logicalFeed, state);
            commitIfNeeded(repoPath, "Initialize PDF mirror");
        }
    }

    private void importRepoContents(LogicalFeed logicalFeed, String state, Path repoPath) throws IOException {
        try (var files = Files.list(repoPath)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    continue;
                }
                Long paperId = parsePaperId(fileName);
                if (paperId == null) {
                    continue;
                }
                Paper paper = paperRepository.findById(paperId);
                if (paper == null || paper.logicalFeed == null || paper.logicalFeed.id == null) {
                    continue;
                }
                if (!paper.logicalFeed.id.equals(logicalFeed.id) || !state.equals(paper.status)) {
                    continue;
                }
                if (paper.uploadedPdfPath != null) {
                    Path currentPath = paperStorageService.resolve(paper.uploadedPdfPath);
                    if (Files.exists(currentPath) && Files.mismatch(currentPath, file) == -1) {
                        continue;
                    }
                }
                String originalFileName = originalFileName(fileName);
                String oldPath = paper.uploadedPdfPath;
                PaperStorageService.StoredPdf storedPdf = paperStorageService.storePdf(file, originalFileName);
                paper.uploadedPdfPath = storedPdf.storedPath();
                paper.uploadedPdfFileName = storedPdf.originalFileName();
                paperEventService.log(paper, "PDF_UPLOADED", "Imported PDF from git mirror for " + state);
                paperStorageService.deleteIfExists(oldPath);
            }
        }
    }

    private void exportRepoContents(LogicalFeed logicalFeed, String state, Path repoPath) throws IOException {
        writeReadme(repoPath, logicalFeed, state);
        clearManagedPdfFiles(repoPath);
        for (Paper paper : paperRepository.findByLogicalFeedAndStatus(logicalFeed, state)) {
            if (paper.uploadedPdfPath == null) {
                continue;
            }
            Path source = paperStorageService.resolve(paper.uploadedPdfPath);
            if (!Files.exists(source)) {
                continue;
            }
            Files.copy(source, repoPath.resolve(repoFileName(paper)), StandardCopyOption.REPLACE_EXISTING);
        }
        commitIfNeeded(repoPath, "Sync PDFs " + state.toLowerCase(Locale.ROOT) + " " + Instant.now());
    }

    private void clearManagedPdfFiles(Path repoPath) throws IOException {
        try (var files = Files.list(repoPath)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                if (fileName.startsWith("paper-") && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private void writeReadme(Path repoPath, LogicalFeed logicalFeed, String state) throws IOException {
        String content = "# PDF mirror\n\n"
                + "Logical feed: " + logicalFeed.name + "\n"
                + "State: " + state + "\n\n"
                + "Managed PDF files use the pattern `paper-<id>--<name>.pdf`.\n";
        Files.writeString(repoPath.resolve("README.md"), content);
    }

    private void commitIfNeeded(Path repoPath, String message) throws IOException {
        runGit(repoPath, "add", "-A");
        String status = runGit(repoPath, "status", "--porcelain").trim();
        if (!status.isEmpty()) {
            runGit(repoPath, "commit", "-m", message);
        }
    }

    private String runGit(Path directory, String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new IOException("Git is required on the server to use PDF mirrors", e);
        }
        try {
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("Git command failed: " + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command interrupted", e);
        }
    }

    private Path repoPath(String token) {
        return gitRoot.resolve(token);
    }

    private String repoUrl(Path repoPath) {
        return repoPath.toUri().toString();
    }

    private String repoFileName(Paper paper) {
        String baseName = paper.uploadedPdfFileName == null || paper.uploadedPdfFileName.isBlank()
                ? paper.title
                : paper.uploadedPdfFileName;
        String sanitized = sanitizeFileName(baseName);
        if (!sanitized.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            sanitized = sanitized + ".pdf";
        }
        return "paper-" + paper.id + "--" + sanitized;
    }

    private String sanitizeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "paper.pdf";
        }
        return value.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
    }

    private Long parsePaperId(String fileName) {
        if (!fileName.startsWith("paper-")) {
            return null;
        }
        int separator = fileName.indexOf("--");
        if (separator < 0) {
            return null;
        }
        try {
            return Long.parseLong(fileName.substring("paper-".length(), separator));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String originalFileName(String fileName) {
        int separator = fileName.indexOf("--");
        if (separator < 0 || separator + 2 >= fileName.length()) {
            return "paper.pdf";
        }
        return fileName.substring(separator + 2);
    }
}
