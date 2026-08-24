package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.PaperFeedSetupDraft;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import top.nextnet.paper.monitor.repo.FeedRepository;
import top.nextnet.paper.monitor.repo.PaperFeedSetupDraftRepository;
import top.nextnet.paper.monitor.rss.RssPaperItem;
import top.nextnet.paper.monitor.rss.RssParser;

@ApplicationScoped
public class SetupWizardService {

    private static final int PREVIEW_LIMIT = 10;
    private static final Duration PREVIEW_POLL_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration PREVIEW_POLL_INTERVAL = Duration.ofSeconds(5);

    private final ScholarService scholarService;
    private final FeedFetcher feedFetcher;
    private final RssParser rssParser;
    private final PaperFeedSetupDraftRepository draftRepository;
    private final LogicalFeedRepository logicalFeedRepository;
    private final FeedRepository feedRepository;
    private final AuthService authService;

    @Inject
    SetupWizardService self;

    public SetupWizardService(
            ScholarService scholarService,
            FeedFetcher feedFetcher,
            RssParser rssParser,
            PaperFeedSetupDraftRepository draftRepository,
            LogicalFeedRepository logicalFeedRepository,
            FeedRepository feedRepository,
            AuthService authService
    ) {
        this.scholarService = scholarService;
        this.feedFetcher = feedFetcher;
        this.rssParser = rssParser;
        this.draftRepository = draftRepository;
        this.logicalFeedRepository = logicalFeedRepository;
        this.feedRepository = feedRepository;
        this.authService = authService;
    }

    public List<Map<String, Object>> recentQueries() {
        return collapseDuplicateQueries(scholarService.history());
    }

    public PreviewResult preview(AppUser user, String draftId, long queryId) {
        Map<String, Object> selected = scholarService.history().stream()
                .filter(row -> longValue(row.get("queryId")) == queryId)
                .findFirst()
                .orElseThrow(() -> new WebApplicationException("Scholar query was not found", Response.Status.NOT_FOUND));
        Map<String, Object> created = scholarService.createFeedFromHistory(queryId);
        String rssUrl = stringValue(created.get("rssUrl"));
        if (rssUrl == null) {
            rssUrl = stringValue(selected.get("rssUrl"));
        }
        if (rssUrl == null) {
            throw new WebApplicationException("Scholar did not provide an RSS URL", Response.Status.BAD_GATEWAY);
        }

        List<RssPaperItem> papers = fetchPreview(rssUrl);
        List<Map<String, Object>> previewPapers = papers.stream()
                .limit(PREVIEW_LIMIT)
                .map(SetupWizardService::paperMap)
                .toList();
        PaperFeedSetupDraft draft = self.savePreview(
                user,
                draftId,
                queryId,
                stringValue(selected.get("query")),
                nullableLong(selected.get("count")),
                rssUrl,
                papers.size(),
                previewPapers);
        return new PreviewResult(draft.id, rssUrl, papers.size(), previewPapers);
    }

    private List<RssPaperItem> fetchPreview(String rssUrl) {
        Exception lastError = null;
        long deadline = System.nanoTime() + PREVIEW_POLL_TIMEOUT.toNanos();
        while (true) {
            try {
                List<RssPaperItem> papers = rssParser.parse(feedFetcher.fetch(rssUrl));
                if (!papers.isEmpty()) {
                    return papers;
                }
                lastError = null;
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                lastError = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                if (lastError == null) {
                    return List.of();
                }
                break;
            }
            try {
                long sleepMillis = Math.min(
                        PREVIEW_POLL_INTERVAL.toMillis(),
                        Math.max(1L, Duration.ofNanos(remainingNanos).toMillis()));
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = e;
                break;
            }
        }
        String detail = lastError == null || lastError.getMessage() == null ? "RSS is not ready" : lastError.getMessage();
        throw new WebApplicationException("Scholar RSS preview failed: " + detail, Response.Status.BAD_GATEWAY);
    }

    @Transactional
    public PaperFeedSetupDraft savePreview(
            AppUser user,
            String draftId,
            long queryId,
            String query,
            Long count,
            String rssUrl,
            int availableCount,
            List<Map<String, Object>> previewPapers
    ) {
        draftRepository.deleteExpired();
        PaperFeedSetupDraft draft = draftRepository.findOwned(draftId, user).orElseGet(() -> {
            PaperFeedSetupDraft created = new PaperFeedSetupDraft();
            created.id = UUID.randomUUID().toString();
            created.user = user;
            created.expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
            draftRepository.persist(created);
            return created;
        });
        draft.scholarQueryId = queryId;
        draft.scholarQuery = query;
        draft.scholarReportedCount = count;
        draft.rssUrl = rssUrl;
        draft.availableCount = availableCount;
        draft.previewJson = JsonCodec.stringify(previewPapers);
        draft.previewConfirmed = false;
        draft.updatedAt = Instant.now();
        draft.expiresAt = draft.updatedAt.plus(24, ChronoUnit.HOURS);
        return draft;
    }

    @Transactional
    public Map<String, Object> draft(AppUser user, String draftId) {
        return draftMap(requireDraft(user, draftId));
    }

    @Transactional
    public Map<String, Object> saveTitle(AppUser user, String draftId, String title) {
        PaperFeedSetupDraft draft = requireDraft(user, draftId);
        if (!draft.previewConfirmed) {
            throw new WebApplicationException("Confirm the paper preview before continuing", Response.Status.BAD_REQUEST);
        }
        String normalized = required(title, "Paper feed title is required");
        if (normalized.length() > 120) {
            throw new WebApplicationException("Paper feed title must be 120 characters or fewer", Response.Status.BAD_REQUEST);
        }
        LogicalFeed existing = logicalFeedRepository.find("name", normalized).firstResult();
        if (existing != null) {
            throw new WebApplicationException("A paper feed with this title already exists", Response.Status.CONFLICT);
        }
        draft.title = normalized;
        touch(draft);
        return draftMap(draft);
    }

    @Transactional
    public Map<String, Object> confirmPreview(AppUser user, String draftId, boolean confirmed) {
        PaperFeedSetupDraft draft = requireDraft(user, draftId);
        if (confirmed) {
            draft.previewConfirmed = true;
        } else {
            draft.scholarQueryId = null;
            draft.scholarQuery = null;
            draft.scholarReportedCount = null;
            draft.rssUrl = null;
            draft.availableCount = null;
            draft.previewJson = null;
            draft.previewConfirmed = false;
            draft.title = null;
            draft.workflowType = null;
        }
        touch(draft);
        return draftMap(draft);
    }

    @Transactional
    public Map<String, Object> saveDrive(
            AppUser user,
            String draftId,
            boolean enabled,
            String folderId,
            String folderName
    ) {
        PaperFeedSetupDraft draft = requireDraft(user, draftId);
        if (enabled && (folderId == null || folderId.isBlank())) {
            throw new WebApplicationException("Choose a Google Drive folder", Response.Status.BAD_REQUEST);
        }
        draft.driveEnabled = enabled;
        draft.driveFolderId = enabled ? folderId.trim() : null;
        draft.driveFolderName = enabled ? required(folderName, "Google Drive folder name is required") : null;
        touch(draft);
        return draftMap(draft);
    }

    @Transactional
    public Map<String, Object> saveWorkflow(AppUser user, String draftId, String workflowType, String customWorkflow) {
        PaperFeedSetupDraft draft = requireDraft(user, draftId);
        String normalized = required(workflowType, "Choose a classification workflow").toUpperCase(Locale.ROOT);
        if (!Set.of("MIAGE", "PRISMA", "CUSTOM").contains(normalized)) {
            throw new WebApplicationException("Unknown classification workflow", Response.Status.BAD_REQUEST);
        }
        draft.workflowType = normalized;
        draft.customWorkflow = "CUSTOM".equals(normalized)
                ? normalizeCustomWorkflow(customWorkflow)
                : null;
        touch(draft);
        return draftMap(draft);
    }

    @Transactional
    public CompletionResult complete(AppUser user, String draftId, String customWorkflow) {
        PaperFeedSetupDraft draft = requireDraft(user, draftId);
        required(draft.rssUrl, "Choose and confirm a Scholar query first");
        if (!draft.previewConfirmed) {
            throw new WebApplicationException("Confirm the paper preview before completing setup", Response.Status.BAD_REQUEST);
        }
        required(draft.title, "Paper feed title is required");
        String workflowType = required(draft.workflowType, "Choose a classification workflow");
        String workflowYaml = switch (workflowType) {
            case "MIAGE" -> QuickSetupWorkflows.KANBAN;
            case "PRISMA" -> QuickSetupWorkflows.PRISMA;
            case "CUSTOM" -> normalizeCustomWorkflow(
                    draft.customWorkflow == null ? customWorkflow : draft.customWorkflow,
                    "Custom workflow YAML is required");
            default -> throw new WebApplicationException("Unknown classification workflow", Response.Status.BAD_REQUEST);
        };
        if (logicalFeedRepository.find("name", draft.title).firstResult() != null) {
            throw new WebApplicationException("A paper feed with this title already exists", Response.Status.CONFLICT);
        }

        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = draft.title;
        logicalFeed.description = "Created from MIAGE Scholar query " + draft.scholarQueryId;
        logicalFeed.workflowStates = workflowYaml;
        logicalFeed.owner = user;
        logicalFeed.publicReadable = false;
        logicalFeed.notifyOnNewRssPapers = true;
        logicalFeed.publicShareToken = UUID.randomUUID().toString();
        logicalFeedRepository.persist(logicalFeed);

        Feed feed = new Feed();
        feed.name = draft.title + " RSS";
        feed.url = draft.rssUrl;
        feed.pollIntervalMinutes = 1440;
        feed.logicalFeed = logicalFeed;
        feedRepository.persist(feed);

        if (draft.driveEnabled) {
            UserSettings settings = authService.ensureSettings(user);
            if (!settings.hasGoogleDriveConnection()) {
                throw new WebApplicationException("Connect Google Drive before completing setup", Response.Status.BAD_REQUEST);
            }
            settings.googleDriveFolderId = draft.driveFolderId;
            settings.googleDriveFolderName = draft.driveFolderName;
            settings.googleDriveSyncEnabled = true;
            settings.googleDriveLastSyncError = null;
        }
        draftRepository.delete(draft);
        feedRepository.flush();
        return new CompletionResult(logicalFeed.id, feed.id);
    }

    public static List<Map<String, Object>> collapseDuplicateQueries(List<Map<String, Object>> history) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (history == null) {
            return result;
        }
        for (Map<String, Object> row : history) {
            String query = stringValue(row.get("query"));
            String key = query == null ? "id:" + row.get("queryId") : query.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                result.add(row);
            }
        }
        return result;
    }

    private PaperFeedSetupDraft requireDraft(AppUser user, String draftId) {
        return draftRepository.findOwned(draftId, user)
                .orElseThrow(() -> new WebApplicationException("Setup draft was not found or has expired", Response.Status.NOT_FOUND));
    }

    private void touch(PaperFeedSetupDraft draft) {
        draft.updatedAt = Instant.now();
        draft.expiresAt = draft.updatedAt.plus(24, ChronoUnit.HOURS);
    }

    private static Map<String, Object> paperMap(RssPaperItem paper) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", paper.title());
        result.put("link", paper.link());
        result.put("openAccessLink", paper.openAccessLink());
        result.put("summary", paper.summary());
        result.put("authors", paper.authors());
        result.put("publisher", paper.publisher());
        result.put("publishedOn", paper.publishedOn() == null ? null : paper.publishedOn().toString());
        return result;
    }

    private static Map<String, Object> draftMap(PaperFeedSetupDraft draft) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", draft.id);
        result.put("queryId", draft.scholarQueryId);
        result.put("query", draft.scholarQuery);
        result.put("reportedCount", draft.scholarReportedCount);
        result.put("rssUrl", draft.rssUrl);
        result.put("availableCount", draft.availableCount);
        result.put("previewConfirmed", draft.previewConfirmed);
        result.put("papers", draft.previewJson == null ? List.of() : JsonCodec.parse(draft.previewJson));
        result.put("title", draft.title);
        result.put("driveEnabled", draft.driveEnabled);
        result.put("driveFolderId", draft.driveFolderId);
        result.put("driveFolderName", draft.driveFolderName);
        result.put("workflowType", draft.workflowType);
        result.put("customWorkflow", draft.customWorkflow);
        result.put("expiresAt", draft.expiresAt.toString());
        return result;
    }

    private static String required(String value, String message) {
        String normalized = stringValue(value);
        if (normalized == null) {
            throw new WebApplicationException(message, Response.Status.BAD_REQUEST);
        }
        return normalized;
    }

    private static String normalizeCustomWorkflow(String value) {
        return normalizeCustomWorkflow(value, "Custom workflow YAML is required");
    }

    private static String normalizeCustomWorkflow(String value, String message) {
        try {
            return WorkflowStateConfig.parse(required(value, message)).toYaml();
        } catch (WebApplicationException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Invalid custom workflow: " + e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static long longValue(Object value) {
        Long result = nullableLong(value);
        return result == null ? Long.MIN_VALUE : result;
    }

    private static Long nullableLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            String normalized = stringValue(value);
            return normalized == null ? null : Long.parseLong(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record PreviewResult(String draftId, String rssUrl, int availableCount, List<Map<String, Object>> papers) {
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("draftId", draftId);
            result.put("rssUrl", rssUrl);
            result.put("availableCount", availableCount);
            result.put("papers", papers);
            return result;
        }
    }

    public record CompletionResult(Long logicalFeedId, Long feedId) {
    }
}
