package top.nextnet.paper.monitor.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.repo.FeedRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;

@ApplicationScoped
public class PaperGitSyncService {

    private static final String MANAGED_COMMIT_PREFIX = "[paper-monitor]";
    private static final Pattern DOI_PATTERN = Pattern.compile(
            "\\b10\\.\\d{4,9}/[-._;()/:A-Z0-9]+\\b",
            Pattern.CASE_INSENSITIVE);

    private final Path gitRoot;
    private final String baseUrl;
    private final PaperRepository paperRepository;
    private final FeedRepository feedRepository;
    private final PaperStorageService paperStorageService;
    private final PaperEventService paperEventService;
    private final DoiMetadataService doiMetadataService;

    public PaperGitSyncService(
            @ConfigProperty(name = "paper-monitor.git.root", defaultValue = "git-remotes") String gitRoot,
            @ConfigProperty(name = "paper-monitor.base-url", defaultValue = "http://localhost:8080") String baseUrl,
            PaperRepository paperRepository,
            FeedRepository feedRepository,
            PaperStorageService paperStorageService,
            PaperEventService paperEventService,
            DoiMetadataService doiMetadataService
    ) {
        this.gitRoot = Path.of(gitRoot).toAbsolutePath().normalize();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.paperRepository = paperRepository;
        this.feedRepository = feedRepository;
        this.paperStorageService = paperStorageService;
        this.paperEventService = paperEventService;
        this.doiMetadataService = doiMetadataService;
    }

    public void syncLogicalFeeds(List<LogicalFeed> logicalFeeds) {
        for (LogicalFeed logicalFeed : logicalFeeds) {
            syncLogicalFeed(logicalFeed);
        }
    }

    public void syncLogicalFeed(LogicalFeed logicalFeed) {
        logicalFeed.gitSyncError = null;
        logicalFeed.gitRepoUrl = null;

        if (logicalFeed.gitRepoToken == null || logicalFeed.gitRepoToken.isBlank()) {
            logicalFeed.gitRepoToken = UUID.randomUUID().toString();
        }

        Path repoPath = repoPath(logicalFeed.gitRepoToken);
        try {
            ensureRepo(repoPath, logicalFeed);
            runGit(repoPath, "reset", "--hard", "HEAD");
            importCommittedChanges(logicalFeed, repoPath);
            exportWorkingTree(logicalFeed, repoPath);
            logicalFeed.gitRepoUrl = repoUrl(logicalFeed.gitRepoToken);
        } catch (IOException e) {
            logicalFeed.gitSyncError = "Git mirror sync failed: " + e.getMessage();
            Log.errorf(e, "Failed to sync git export for %s", logicalFeed.name);
        }
    }

    private void ensureRepo(Path repoPath, LogicalFeed logicalFeed) throws IOException {
        Files.createDirectories(repoPath);
        if (!Files.exists(repoPath.resolve(".git"))) {
            runGit(repoPath.getParent(), "init", repoPath.getFileName().toString());
            runGit(repoPath, "config", "user.name", "Paper Monitor");
            runGit(repoPath, "config", "user.email", "paper-monitor@local");
            runGit(repoPath, "config", "receive.denyCurrentBranch", "updateInstead");
            runGit(repoPath, "config", "http.receivepack", "true");
            runGit(repoPath, "config", "http.uploadpack", "true");
            writeReadme(repoPath, logicalFeed);
            ensureStateDirectories(repoPath, logicalFeed);
            commitIfNeeded(repoPath, MANAGED_COMMIT_PREFIX + " Initialize logical feed mirror");
            logicalFeed.lastProcessedGitCommit = resolveHead(repoPath);
        }
    }

    private void importCommittedChanges(LogicalFeed logicalFeed, Path repoPath) throws IOException {
        List<String> commits = commitsToProcess(repoPath, logicalFeed.lastProcessedGitCommit);
        for (String commit : commits) {
            processCommit(logicalFeed, repoPath, commit);
            logicalFeed.lastProcessedGitCommit = commit;
        }
        if (commits.isEmpty() && logicalFeed.lastProcessedGitCommit == null) {
            logicalFeed.lastProcessedGitCommit = resolveHead(repoPath);
        }
    }

    private List<String> commitsToProcess(Path repoPath, String lastProcessedCommit) throws IOException {
        String range;
        if (lastProcessedCommit == null || lastProcessedCommit.isBlank()) {
            range = "HEAD";
        } else if (commitExists(repoPath, lastProcessedCommit)) {
            range = lastProcessedCommit + "..HEAD";
        } else {
            range = "HEAD";
        }
        String output = runGit(repoPath, "rev-list", "--reverse", range).trim();
        if (output.isEmpty()) {
            return List.of();
        }
        List<String> commits = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String commit = line.trim();
            if (!commit.isEmpty()) {
                commits.add(commit);
            }
        }
        return commits;
    }

    private boolean commitExists(Path repoPath, String commit) {
        try {
            runGit(repoPath, "rev-parse", "--verify", commit + "^{commit}");
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void processCommit(LogicalFeed logicalFeed, Path repoPath, String commit) throws IOException {
        String message = commitMessage(repoPath, commit).trim();
        if (message.startsWith(MANAGED_COMMIT_PREFIX)) {
            return;
        }

        List<GitChange> changes = commitChanges(repoPath, commit);
        List<GitChange> pdfChanges = changes.stream()
                .filter((change) -> change.involvesPdf())
                .toList();
        List<GitChange> noteChanges = changes.stream()
                .filter((change) -> change.involvesNotes())
                .toList();

        if (pdfChanges.size() == 1 && pdfChanges.get(0).isSingleNewUnmanagedPdf()) {
            String doi = extractDoi(message);
            if (doi != null) {
                importNewPaperFromDoiCommit(logicalFeed, repoPath, commit, pdfChanges.get(0), doi);
                return;
            }
        }

        for (GitChange change : pdfChanges) {
            applyPdfChange(logicalFeed, repoPath, commit, change);
        }
        for (GitChange change : noteChanges) {
            applyNotesChange(logicalFeed, repoPath, commit, change);
        }
    }

    private void applyPdfChange(LogicalFeed logicalFeed, Path repoPath, String commit, GitChange change) throws IOException {
        String targetPath = change.newPath != null ? change.newPath : change.oldPath;
        if (targetPath == null) {
            return;
        }

        String targetState = stateFromPath(logicalFeed, targetPath);
        if (targetState == null) {
            return;
        }

        Long paperId = parsePaperId(fileName(targetPath));
        if (paperId == null) {
            return;
        }

        Paper paper = paperRepository.findById(paperId);
        if (paper == null || paper.logicalFeed == null || !logicalFeed.id.equals(paper.logicalFeed.id)) {
            return;
        }

        if (!targetState.equals(paper.status)) {
            String previousStatus = paper.status;
            paper.status = targetState;
            paperEventService.log(paper, "STATE_CHANGED", previousStatus + " -> " + targetState + " (git)");
        }

        if (change.newPath != null) {
            importPdfBlob(repoPath, commit, change.newPath, paper, "Imported PDF from git commit " + commit);
        }
    }

    private void applyNotesChange(LogicalFeed logicalFeed, Path repoPath, String commit, GitChange change) throws IOException {
        String targetPath = change.newPath != null ? change.newPath : change.oldPath;
        if (targetPath == null) {
            return;
        }

        String targetState = stateFromPath(logicalFeed, targetPath);
        if (targetState == null) {
            return;
        }

        Long paperId = parsePaperId(fileName(targetPath));
        if (paperId == null) {
            return;
        }

        Paper paper = paperRepository.findById(paperId);
        if (paper == null || paper.logicalFeed == null || !logicalFeed.id.equals(paper.logicalFeed.id)) {
            return;
        }

        if (!targetState.equals(paper.status)) {
            String previousStatus = paper.status;
            paper.status = targetState;
            paperEventService.log(paper, "STATE_CHANGED", previousStatus + " -> " + targetState + " (git)");
        }

        if (change.newPath != null) {
            importNotesBlob(repoPath, commit, change.newPath, paper);
        }
    }

    private void importNewPaperFromDoiCommit(
            LogicalFeed logicalFeed,
            Path repoPath,
            String commit,
            GitChange change,
            String doi
    ) throws IOException {
        String state = stateFromPath(logicalFeed, change.newPath);
        if (state == null) {
            return;
        }

        DoiMetadataService.DoiMetadata metadata = doiMetadataService.fetch(doi);
        String fileName = fileName(change.newPath);

        Paper paper = new Paper();
        paper.title = nonBlank(metadata.title(), stripPdfSuffix(fileName));
        paper.sourceLink = nonBlank(metadata.doiUrl(), "doi:" + doi);
        paper.openAccessLink = metadata.openAccessUrl();
        paper.summary = metadata.summary();
        paper.authors = metadata.authors();
        paper.publisher = metadata.publisher();
        paper.publishedOn = metadata.publishedOn();
        paper.status = state;
        paper.discoveredAt = Instant.now();
        paper.feed = getOrCreateManualUploadFeed(logicalFeed);
        paper.logicalFeed = logicalFeed;
        paperRepository.persist(paper);

        importPdfBlob(repoPath, commit, change.newPath, paper, "Imported PDF from DOI commit " + doi);
        paperEventService.log(paper, "FETCH", "Created from git commit DOI " + doi);
    }

    private void importPdfBlob(Path repoPath, String commit, String repoFilePath, Paper paper, String eventDetails) throws IOException {
        Path tempFile = Files.createTempFile("paper-monitor-git-", ".pdf");
        try {
            byte[] data = gitShowBytes(repoPath, commit + ":" + repoFilePath);
            Files.write(tempFile, data);
            String originalFileName = originalFileName(fileName(repoFilePath), paper);
            String previousPath = paper.uploadedPdfPath;
            PaperStorageService.StoredPdf storedPdf = paperStorageService.storePdf(tempFile, originalFileName);
            paper.uploadedPdfPath = storedPdf.storedPath();
            paper.uploadedPdfFileName = storedPdf.originalFileName();
            paperEventService.log(paper, "PDF_UPLOADED", eventDetails);
            paperStorageService.deleteIfExists(previousPath);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void importNotesBlob(Path repoPath, String commit, String repoFilePath, Paper paper) throws IOException {
        byte[] data = gitShowBytes(repoPath, commit + ":" + repoFilePath);
        paper.notes = new String(data);
    }

    private void exportWorkingTree(LogicalFeed logicalFeed, Path repoPath) throws IOException {
        ensureStateDirectories(repoPath, logicalFeed);
        clearManagedPdfFiles(repoPath, logicalFeed);
        writeReadme(repoPath, logicalFeed);
        for (String state : logicalFeed.workflowStateList()) {
            Path stateDirectory = repoPath.resolve(pathForState(state));
            for (Paper paper : paperRepository.findByLogicalFeedAndStatus(logicalFeed, state)) {
                Files.writeString(stateDirectory.resolve(repoNotesFileName(paper)), nonBlank(paper.notes, ""));
                if (paper.uploadedPdfPath == null) {
                    continue;
                }
                Path source = paperStorageService.resolve(paper.uploadedPdfPath);
                if (!Files.exists(source)) {
                    continue;
                }
                Files.copy(source, stateDirectory.resolve(repoFileName(paper)), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        commitIfNeeded(repoPath, MANAGED_COMMIT_PREFIX + " Sync logical feed " + logicalFeed.name);
    }

    private void ensureStateDirectories(Path repoPath, LogicalFeed logicalFeed) throws IOException {
        for (String state : logicalFeed.workflowStateList()) {
            Files.createDirectories(repoPath.resolve(pathForState(state)));
        }
    }

    private void clearManagedPdfFiles(Path repoPath, LogicalFeed logicalFeed) throws IOException {
        for (String state : logicalFeed.workflowStateList()) {
            Path stateDirectory = repoPath.resolve(pathForState(state));
            if (!Files.exists(stateDirectory)) {
                continue;
            }
            try (var stream = Files.list(stateDirectory)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    if (file.getFileName().toString().startsWith("paper-")
                            && (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")
                            || file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
    }

    private void writeReadme(Path repoPath, LogicalFeed logicalFeed) throws IOException {
        String content = "# PDF mirror\n\n"
                + "Logical feed: " + logicalFeed.name + "\n\n"
                + "Each workflow leaf state is represented by a directory path in the repository.\n"
                + "Managed PDF files use the pattern `paper-<id>--<name>.pdf`.\n"
                + "Managed notes files use the pattern `paper-<id>--<name>.md`.\n"
                + "Editing a managed notes file updates the paper notes in the application.\n"
                + "Moving a managed PDF between state directories changes the paper state.\n"
                + "Moving a managed notes file between state directories changes the paper state.\n"
                + "Committing a single new PDF with a DOI in the commit message creates a new paper.\n";
        Files.writeString(repoPath.resolve("README.md"), content);
    }

    private String resolveHead(Path repoPath) throws IOException {
        String head = runGit(repoPath, "rev-parse", "HEAD").trim();
        return head.isEmpty() ? null : head;
    }

    private List<GitChange> commitChanges(Path repoPath, String commit) throws IOException {
        String output = runGit(repoPath, "diff-tree", "--root", "--find-renames", "--no-commit-id", "--name-status", "-r", commit);
        List<GitChange> changes = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t");
            if (parts.length < 2) {
                continue;
            }
            String status = parts[0];
            if (status.startsWith("R") && parts.length >= 3) {
                changes.add(new GitChange(status, parts[1], parts[2]));
            } else {
                changes.add(new GitChange(status, status.startsWith("D") ? parts[1] : null, status.startsWith("D") ? null : parts[1]));
            }
        }
        return changes;
    }

    private String commitMessage(Path repoPath, String commit) throws IOException {
        return runGit(repoPath, "log", "-1", "--format=%B", commit);
    }

    private byte[] gitShowBytes(Path repoPath, String objectExpression) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder("git", "show", objectExpression)
                    .directory(repoPath.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new IOException("Git is required on the server to use PDF mirrors", e);
        }
        try (InputStream inputStream = process.getInputStream()) {
            byte[] output = inputStream.readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("Git show failed for " + objectExpression);
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git show interrupted", e);
        }
    }

    private void commitIfNeeded(Path repoPath, String message) throws IOException {
        runGit(repoPath, "add", "-A");
        String status = runGit(repoPath, "status", "--porcelain=1", "--find-renames").trim();
        if (!status.isEmpty()) {
            runGit(repoPath, "commit", "-m", buildManagedCommitMessage(status, message));
        }
    }

    private String buildManagedCommitMessage(String statusOutput, String fallbackMessage) {
        Map<Long, ManagedPaperChange> changesByPaper = new LinkedHashMap<>();
        for (String line : statusOutput.split("\\R")) {
            parseManagedStatusLine(line).ifPresent((status) -> {
                Long paperId = firstNonNull(parsePaperId(fileName(status.newPath())), parsePaperId(fileName(status.oldPath())));
                if (paperId == null) {
                    return;
                }
                changesByPaper.computeIfAbsent(paperId, ManagedPaperChange::new).include(status);
            });
        }

        if (changesByPaper.isEmpty()) {
            return fallbackMessage;
        }

        StringBuilder message = new StringBuilder(fallbackMessage);
        for (ManagedPaperChange change : changesByPaper.values()) {
            List<String> details = change.describe();
            if (details.isEmpty()) {
                continue;
            }
            message.append("\n\n- ")
                    .append(change.paperTitle())
                    .append(": ")
                    .append(String.join(", ", details));
        }
        return message.toString();
    }

    private java.util.Optional<ManagedStatusLine> parseManagedStatusLine(String line) {
        if (line == null || line.isBlank() || line.length() < 4) {
            return java.util.Optional.empty();
        }
        String statusCode = line.substring(0, 2);
        String payload = line.substring(3);
        if (statusCode.startsWith("R") || statusCode.startsWith("C")) {
            String[] parts = payload.split("\\t");
            if (parts.length >= 2) {
                return java.util.Optional.of(new ManagedStatusLine(statusCode, parts[0], parts[1]));
            }
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ManagedStatusLine(statusCode, null, payload));
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

    private String repoUrl(String token) {
        return baseUrl + "/git/" + token;
    }

    private String repoFileName(Paper paper) {
        return "paper-" + paper.id + "--" + repoSafeTitleBaseName(paper) + ".pdf";
    }

    private String repoNotesFileName(Paper paper) {
        return "paper-" + paper.id + "--" + repoSafeTitleBaseName(paper) + ".md";
    }

    private String repoSafeTitleBaseName(Paper paper) {
        String sanitized = sanitizeFileName(paper == null ? null : paper.title);
        if (sanitized.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            sanitized = stripPdfSuffix(sanitized);
        }
        if (sanitized.toLowerCase(Locale.ROOT).endsWith(".md")) {
            sanitized = sanitized.substring(0, sanitized.length() - 3);
        }
        return sanitized;
    }

    private String sanitizeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "paper";
        }
        return value.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
    }

    private Long parsePaperId(String fileName) {
        if (fileName == null) {
            return null;
        }
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

    private String extractDoi(String message) {
        Matcher matcher = DOI_PATTERN.matcher(message);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : null;
    }

    private String stateFromPath(LogicalFeed logicalFeed, String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        for (String state : logicalFeed.workflowStateList()) {
            String statePath = pathForState(state);
            if (normalized.startsWith(statePath + "/")) {
                return state;
            }
        }
        return null;
    }

    private String pathForState(String state) {
        return state.replace('/', java.io.File.separatorChar).replace('\\', '/');
    }

    private String fileName(String path) {
        if (path == null) {
            return null;
        }
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private String displayState(String state) {
        return state == null ? "unknown" : state.replace('_', ' ');
    }

    private String originalFileName(String fileName, Paper paper) {
        int separator = fileName.indexOf("--");
        if (separator >= 0 && separator + 2 < fileName.length()) {
            return fileName.substring(separator + 2);
        }
        if (paper.uploadedPdfFileName != null && !paper.uploadedPdfFileName.isBlank()) {
            return paper.uploadedPdfFileName;
        }
        return fileName;
    }

    private String stripPdfSuffix(String fileName) {
        if (fileName == null) {
            return "Imported paper";
        }
        return fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    private Feed getOrCreateManualUploadFeed(LogicalFeed logicalFeed) {
        String syntheticUrl = "upload://logical-feed/" + logicalFeed.id;
        return feedRepository.findByUrl(syntheticUrl).orElseGet(() -> {
            Feed feed = new Feed();
            feed.name = "Manual upload: " + logicalFeed.name;
            feed.url = syntheticUrl;
            feed.pollIntervalMinutes = 525600;
            feed.logicalFeed = logicalFeed;
            feedRepository.persist(feed);
            return feed;
        });
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private final class ManagedPaperChange {
        private final Long paperId;
        private final String paperTitle;
        private boolean notesChanged;
        private boolean pdfChanged;
        private String fromState;
        private String toState;

        private ManagedPaperChange(Long paperId) {
            this.paperId = paperId;
            Paper paper = paperRepository.findById(paperId);
            this.paperTitle = paper == null || paper.title == null || paper.title.isBlank()
                    ? "Paper " + paperId
                    : paper.title;
        }

        void include(ManagedStatusLine line) {
            if (isNotesPath(line.oldPath()) || isNotesPath(line.newPath())) {
                notesChanged = true;
            }
            if (isPdfPath(line.oldPath()) || isPdfPath(line.newPath())) {
                pdfChanged = true;
            }
            String oldState = stateFromManagedPath(line.oldPath());
            String newState = stateFromManagedPath(line.newPath());
            if (oldState != null && newState != null && !oldState.equals(newState)) {
                fromState = oldState;
                toState = newState;
            }
        }

        String paperTitle() {
            return paperTitle;
        }

        List<String> describe() {
            List<String> details = new ArrayList<>();
            if (fromState != null && toState != null) {
                details.add("state changed " + displayState(fromState) + " -> " + displayState(toState));
            }
            if (notesChanged) {
                details.add("notes updated");
            }
            if (pdfChanged) {
                details.add("PDF updated");
            }
            return details;
        }

        private boolean isNotesPath(String path) {
            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".md");
        }

        private boolean isPdfPath(String path) {
            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".pdf");
        }

        private String stateFromManagedPath(String path) {
            if (path == null) {
                return null;
            }
            String normalized = path.replace('\\', '/');
            int separator = normalized.lastIndexOf('/');
            if (separator < 0) {
                return null;
            }
            String directoryPath = normalized.substring(0, separator);
            Paper paper = paperRepository.findById(paperId);
            if (paper == null || paper.logicalFeed == null) {
                return null;
            }
            for (String state : paper.logicalFeed.workflowStateList()) {
                if (pathForState(state).equals(directoryPath)) {
                    return state;
                }
            }
            return null;
        }
    }

    private record ManagedStatusLine(String statusCode, String oldPath, String newPath) {
    }

    private record GitChange(String status, String oldPath, String newPath) {
        boolean involvesPdf() {
            return (oldPath != null && oldPath.toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    || (newPath != null && newPath.toLowerCase(Locale.ROOT).endsWith(".pdf"));
        }

        boolean involvesNotes() {
            return (oldPath != null && oldPath.toLowerCase(Locale.ROOT).endsWith(".md"))
                    || (newPath != null && newPath.toLowerCase(Locale.ROOT).endsWith(".md"));
        }

        boolean isSingleNewUnmanagedPdf() {
            return status.startsWith("A")
                    && newPath != null
                    && newPath.toLowerCase(Locale.ROOT).endsWith(".pdf")
                    && !fileName(newPath).startsWith("paper-");
        }

        private static String fileName(String path) {
            int separator = path.lastIndexOf('/');
            return separator < 0 ? path : path.substring(separator + 1);
        }
    }
}
