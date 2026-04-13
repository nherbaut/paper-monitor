package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.LogicalFeedAccessGrant;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PaperEvent;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.AppUserRepository;
import top.nextnet.paper.monitor.repo.FeedRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedAccessGrantRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import top.nextnet.paper.monitor.repo.PaperEventRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;
import top.nextnet.paper.monitor.repo.UserSessionRepository;
import top.nextnet.paper.monitor.repo.UserSettingsRepository;

@ApplicationScoped
public class BackupService {

    private static final Pattern NOTE_ASSET_PATTERN = Pattern.compile("/assets/([^)\\s\"'>]+)");

    private final LogicalFeedRepository logicalFeedRepository;
    private final FeedRepository feedRepository;
    private final PaperRepository paperRepository;
    private final PaperEventRepository paperEventRepository;
    private final PaperStorageService paperStorageService;
    private final AppUserRepository appUserRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final UserSessionRepository userSessionRepository;
    private final LogicalFeedAccessGrantRepository logicalFeedAccessGrantRepository;

    public BackupService(
            LogicalFeedRepository logicalFeedRepository,
            FeedRepository feedRepository,
            PaperRepository paperRepository,
            PaperEventRepository paperEventRepository,
            PaperStorageService paperStorageService,
            AppUserRepository appUserRepository,
            UserSettingsRepository userSettingsRepository,
            UserSessionRepository userSessionRepository,
            LogicalFeedAccessGrantRepository logicalFeedAccessGrantRepository
    ) {
        this.logicalFeedRepository = logicalFeedRepository;
        this.feedRepository = feedRepository;
        this.paperRepository = paperRepository;
        this.paperEventRepository = paperEventRepository;
        this.paperStorageService = paperStorageService;
        this.appUserRepository = appUserRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.userSessionRepository = userSessionRepository;
        this.logicalFeedAccessGrantRepository = logicalFeedAccessGrantRepository;
    }

    @Transactional
    public byte[] exportZip() throws IOException {
        return exportZip(null, null);
    }

    @Transactional
    public byte[] exportZip(List<LogicalFeed> scopedLogicalFeeds, AppUser currentUser) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        Map<String, Object> exportData = exportData(scopedLogicalFeeds, currentUser);
        List<String> storedPaths = exportStoredPaths(exportData);
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream, StandardCharsets.UTF_8)) {
            writeJsonEntry(zipOutputStream, "data.json", JsonCodec.stringify(exportData));
            for (String storedPath : storedPaths) {
                var file = paperStorageService.resolve(storedPath);
                String relativePath = paperStorageService.root().relativize(file).toString().replace('\\', '/');
                zipOutputStream.putNextEntry(new ZipEntry("files/" + relativePath));
                try (InputStream inputStream = java.nio.file.Files.newInputStream(file)) {
                    inputStream.transferTo(zipOutputStream);
                }
                zipOutputStream.closeEntry();
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    @Transactional
    public void importZip(InputStream zipInputStream) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String json = null;

        try (ZipInputStream inputStream = new ZipInputStream(zipInputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = inputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] content = inputStream.readAllBytes();
                if ("data.json".equals(entry.getName())) {
                    json = new String(content, StandardCharsets.UTF_8);
                } else if (entry.getName().startsWith("files/")) {
                    files.put(entry.getName().substring("files/".length()), content);
                }
            }
        }

        if (json == null) {
            throw new IllegalArgumentException("Backup archive does not contain data.json");
        }

        restoreData(json, files);
    }

    private Map<String, Object> exportData(List<LogicalFeed> scopedLogicalFeeds, AppUser currentUser) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        List<LogicalFeed> logicalFeeds = scopedLogicalFeeds == null ? logicalFeedRepository.findAll().list() : scopedLogicalFeeds;
        List<Long> logicalFeedIds = logicalFeeds.stream().map((logicalFeed) -> logicalFeed.id).toList();
        List<Feed> feeds = logicalFeedIds.isEmpty()
                ? List.of()
                : feedRepository.find("logicalFeed.id in ?1", logicalFeedIds).list();
        List<Paper> papers = logicalFeedIds.isEmpty()
                ? List.of()
                : paperRepository.find("logicalFeed.id in ?1", logicalFeedIds).list();
        List<Long> paperIds = papers.stream().map((paper) -> paper.id).toList();
        List<PaperEvent> paperEvents = paperIds.isEmpty()
                ? List.of()
                : paperEventRepository.find("paper.id in ?1", paperIds).list();
        List<LogicalFeedAccessGrant> grants = logicalFeedIds.isEmpty()
                ? List.of()
                : logicalFeedAccessGrantRepository.find("logicalFeed.id in ?1", logicalFeedIds).list();
        data.put("logicalFeeds", exportLogicalFeeds(logicalFeeds));
        data.put("feeds", exportFeeds(feeds));
        data.put("papers", exportPapers(papers));
        data.put("paperEvents", exportPaperEvents(paperEvents));
        data.put("users", exportUsers(logicalFeeds, grants, currentUser));
        data.put("userSettings", exportUserSettings(currentUser));
        data.put("logicalFeedAccessGrants", exportLogicalFeedAccessGrants(grants));
        return data;
    }

    private List<Map<String, Object>> exportLogicalFeedAccessGrants(List<LogicalFeedAccessGrant> source) {
        List<Map<String, Object>> grants = new ArrayList<>();
        for (LogicalFeedAccessGrant grant : source) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("logicalFeedId", grant.logicalFeed.id);
            item.put("userId", grant.user.id);
            item.put("role", grant.role);
            item.put("createdAt", grant.createdAt == null ? null : grant.createdAt.toString());
            grants.add(item);
        }
        return grants;
    }

    private List<Map<String, Object>> exportUsers(List<LogicalFeed> logicalFeeds, List<LogicalFeedAccessGrant> grants, AppUser currentUser) {
        LinkedHashMap<Long, AppUser> includedUsers = new LinkedHashMap<>();
        if (currentUser != null && currentUser.id != null) {
            includedUsers.put(currentUser.id, currentUser);
        }
        for (LogicalFeed logicalFeed : logicalFeeds) {
            if (logicalFeed.owner != null && logicalFeed.owner.id != null) {
                includedUsers.put(logicalFeed.owner.id, logicalFeed.owner);
            }
        }
        for (LogicalFeedAccessGrant grant : grants) {
            if (grant.user != null && grant.user.id != null) {
                includedUsers.put(grant.user.id, grant.user);
            }
        }
        List<Map<String, Object>> users = new ArrayList<>();
        for (AppUser user : includedUsers.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", user.id);
            item.put("username", user.username);
            item.put("displayName", user.displayName);
            item.put("email", user.email);
            item.put("authProvider", user.authProvider);
            item.put("oidcIssuer", user.oidcIssuer);
            item.put("oidcSubject", user.oidcSubject);
            item.put("passwordSalt", user.passwordSalt);
            item.put("passwordHash", user.passwordHash);
            item.put("admin", user.admin);
            item.put("createdAt", user.createdAt == null ? null : user.createdAt.toString());
            item.put("lastLoginAt", user.lastLoginAt == null ? null : user.lastLoginAt.toString());
            users.add(item);
        }
        return users;
    }

    private List<Map<String, Object>> exportUserSettings(AppUser currentUser) {
        List<Map<String, Object>> settings = new ArrayList<>();
        if (currentUser == null || currentUser.id == null) {
            return settings;
        }
        for (UserSettings item : userSettingsRepository.find("user.id", currentUser.id).list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", item.user.id);
            row.put("voice", item.voice);
            row.put("speedMultiplier", item.speedMultiplier);
            settings.add(row);
        }
        return settings;
    }

    private List<Map<String, Object>> exportLogicalFeeds(List<LogicalFeed> source) {
        List<Map<String, Object>> logicalFeeds = new ArrayList<>();
        for (LogicalFeed logicalFeed : source) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", logicalFeed.id);
            item.put("name", logicalFeed.name);
            item.put("description", logicalFeed.description);
            item.put("workflowStates", logicalFeed.workflowStates);
            item.put("gitRepoToken", logicalFeed.gitRepoToken);
            item.put("lastProcessedGitCommit", logicalFeed.lastProcessedGitCommit);
            item.put("ownerId", logicalFeed.owner == null ? null : logicalFeed.owner.id);
            logicalFeeds.add(item);
        }
        return logicalFeeds;
    }

    private List<Map<String, Object>> exportFeeds(List<Feed> source) {
        List<Map<String, Object>> feeds = new ArrayList<>();
        for (Feed feed : source) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", feed.id);
            item.put("name", feed.name);
            item.put("url", feed.url);
            item.put("pollIntervalMinutes", feed.pollIntervalMinutes);
            item.put("defaultPaperStatus", feed.defaultPaperStatus);
            item.put("lastPolledAt", feed.lastPolledAt == null ? null : feed.lastPolledAt.toString());
            item.put("lastError", feed.lastError);
            item.put("logicalFeedId", feed.logicalFeed.id);
            feeds.add(item);
        }
        return feeds;
    }

    private List<Map<String, Object>> exportPapers(List<Paper> source) {
        List<Map<String, Object>> papers = new ArrayList<>();
        for (Paper paper : source) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", paper.id);
            item.put("title", paper.title);
            item.put("sourceLink", paper.sourceLink);
            item.put("openAccessLink", paper.openAccessLink);
            item.put("uploadedPdfPath", paper.uploadedPdfPath);
            item.put("uploadedPdfFileName", paper.uploadedPdfFileName);
            item.put("summary", paper.summary);
            item.put("notes", paper.notes);
            item.put("authors", paper.authors);
            item.put("publisher", paper.publisher);
            item.put("publishedOn", paper.publishedOn == null ? null : paper.publishedOn.toString());
            item.put("status", paper.status);
            item.put("discoveredAt", paper.discoveredAt.toString());
            item.put("feedId", paper.feed.id);
            item.put("logicalFeedId", paper.logicalFeed.id);
            papers.add(item);
        }
        return papers;
    }

    private List<Map<String, Object>> exportPaperEvents(List<PaperEvent> source) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (PaperEvent event : source) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", event.id);
            item.put("type", event.type);
            item.put("details", event.details);
            item.put("happenedAt", event.happenedAt == null ? null : event.happenedAt.toString());
            item.put("paperId", event.paper.id);
            events.add(item);
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    private List<String> exportStoredPaths(Map<String, Object> exportData) {
        List<String> paths = new ArrayList<>();
        for (Object item : asList(exportData.get("papers"))) {
            Map<?, ?> paper = asMap(item);
            String uploadedPdfPath = stringValue(paper.get("uploadedPdfPath"));
            if (uploadedPdfPath != null && !uploadedPdfPath.isBlank()) {
                paths.add(uploadedPdfPath);
            }
            String notes = stringValue(paper.get("notes"));
            if (notes != null && !notes.isBlank()) {
                Matcher matcher = NOTE_ASSET_PATTERN.matcher(notes);
                while (matcher.find()) {
                    paths.add(matcher.group(1));
                }
            }
        }
        return paths.stream().distinct().toList();
    }

    private void restoreData(String json, Map<String, byte[]> files) throws IOException {
        Object parsed = JsonCodec.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Invalid backup JSON");
        }

        paperEventRepository.deleteAll();
        paperRepository.deleteAll();
        feedRepository.deleteAll();
        logicalFeedRepository.deleteAll();
        userSessionRepository.deleteAll();
        userSettingsRepository.deleteAll();
        logicalFeedAccessGrantRepository.deleteAll();
        appUserRepository.deleteAll();
        paperStorageService.clearAll();

        Map<Long, AppUser> usersByOldId = new LinkedHashMap<>();
        for (Object value : asList(root.get("users"))) {
            Map<?, ?> item = asMap(value);
            AppUser user = new AppUser();
            user.username = stringValue(item.get("username"));
            user.displayName = stringValue(item.get("displayName"));
            user.email = stringValue(item.get("email"));
            user.authProvider = stringValue(item.get("authProvider"));
            user.oidcIssuer = stringValue(item.get("oidcIssuer"));
            user.oidcSubject = stringValue(item.get("oidcSubject"));
            user.passwordSalt = stringValue(item.get("passwordSalt"));
            user.passwordHash = stringValue(item.get("passwordHash"));
            user.admin = boolValue(item.get("admin"));
            user.createdAt = instantValue(item.get("createdAt"));
            user.lastLoginAt = instantValue(item.get("lastLoginAt"));
            appUserRepository.persist(user);
            usersByOldId.put(longValue(item.get("id")), user);
        }

        for (Object value : asList(root.get("userSettings"))) {
            Map<?, ?> item = asMap(value);
            UserSettings settings = new UserSettings();
            settings.user = usersByOldId.get(longValue(item.get("userId")));
            settings.voice = stringValue(item.get("voice"));
            settings.speedMultiplier = doubleValue(item.get("speedMultiplier"));
            if (settings.user != null) {
                userSettingsRepository.persist(settings);
            }
        }

        Map<Long, LogicalFeed> logicalFeedsByOldId = new LinkedHashMap<>();
        for (Object value : asList(root.get("logicalFeeds"))) {
            Map<?, ?> item = asMap(value);
            LogicalFeed logicalFeed = new LogicalFeed();
            logicalFeed.name = stringValue(item.get("name"));
            logicalFeed.description = stringValue(item.get("description"));
            logicalFeed.workflowStates = stringValue(item.get("workflowStates"));
            logicalFeed.gitRepoToken = stringValue(item.get("gitRepoToken"));
            logicalFeed.lastProcessedGitCommit = stringValue(item.get("lastProcessedGitCommit"));
            logicalFeed.owner = usersByOldId.get(longValue(item.get("ownerId")));
            logicalFeedRepository.persist(logicalFeed);
            logicalFeedsByOldId.put(longValue(item.get("id")), logicalFeed);
        }

        for (Object value : asList(root.get("logicalFeedAccessGrants"))) {
            Map<?, ?> item = asMap(value);
            LogicalFeedAccessGrant grant = new LogicalFeedAccessGrant();
            grant.logicalFeed = logicalFeedsByOldId.get(longValue(item.get("logicalFeedId")));
            grant.user = usersByOldId.get(longValue(item.get("userId")));
            grant.role = stringValue(item.get("role"));
            grant.createdAt = instantValue(item.get("createdAt"));
            if (grant.logicalFeed != null && grant.user != null && grant.role != null) {
                logicalFeedAccessGrantRepository.persist(grant);
            }
        }

        Map<Long, Feed> feedsByOldId = new LinkedHashMap<>();
        for (Object value : asList(root.get("feeds"))) {
            Map<?, ?> item = asMap(value);
            Feed feed = new Feed();
            feed.name = stringValue(item.get("name"));
            feed.url = stringValue(item.get("url"));
            feed.pollIntervalMinutes = intValue(item.get("pollIntervalMinutes"));
            feed.defaultPaperStatus = stringValue(item.get("defaultPaperStatus"));
            feed.lastPolledAt = instantValue(item.get("lastPolledAt"));
            feed.lastError = stringValue(item.get("lastError"));
            feed.logicalFeed = logicalFeedsByOldId.get(longValue(item.get("logicalFeedId")));
            feedRepository.persist(feed);
            feedsByOldId.put(longValue(item.get("id")), feed);
        }

        Map<Long, Paper> papersByOldId = new LinkedHashMap<>();
        for (Object value : asList(root.get("papers"))) {
            Map<?, ?> item = asMap(value);
            Paper paper = new Paper();
            paper.title = stringValue(item.get("title"));
            paper.sourceLink = stringValue(item.get("sourceLink"));
            paper.openAccessLink = stringValue(item.get("openAccessLink"));
            paper.uploadedPdfPath = stringValue(item.get("uploadedPdfPath"));
            paper.uploadedPdfFileName = stringValue(item.get("uploadedPdfFileName"));
            paper.summary = stringValue(item.get("summary"));
            paper.notes = stringValue(item.get("notes"));
            paper.authors = stringValue(item.get("authors"));
            paper.publisher = stringValue(item.get("publisher"));
            paper.publishedOn = localDateValue(item.get("publishedOn"));
            paper.status = stringValue(item.get("status"));
            paper.discoveredAt = instantValue(item.get("discoveredAt"));
            paper.feed = feedsByOldId.get(longValue(item.get("feedId")));
            paper.logicalFeed = logicalFeedsByOldId.get(longValue(item.get("logicalFeedId")));
            paperRepository.persist(paper);
            papersByOldId.put(longValue(item.get("id")), paper);
        }

        for (Object value : asList(root.get("paperEvents"))) {
            Map<?, ?> item = asMap(value);
            PaperEvent event = new PaperEvent();
            event.type = stringValue(item.get("type"));
            event.details = stringValue(item.get("details"));
            event.happenedAt = instantValue(item.get("happenedAt"));
            event.paper = papersByOldId.get(longValue(item.get("paperId")));
            if (event.paper != null && event.type != null && event.happenedAt != null) {
                paperEventRepository.persist(event);
            }
        }

        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            try (InputStream inputStream = new java.io.ByteArrayInputStream(entry.getValue())) {
                paperStorageService.write(entry.getKey(), inputStream);
            }
        }
    }

    private void writeJsonEntry(ZipOutputStream zipOutputStream, String name, String json) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(name));
        zipOutputStream.write(json.getBytes(StandardCharsets.UTF_8));
        zipOutputStream.closeEntry();
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Invalid backup JSON list");
        }
        return (List<Object>) list;
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Invalid backup JSON object");
        }
        return (Map<?, ?>) map;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private boolean boolValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Instant instantValue(Object value) {
        String string = stringValue(value);
        return string == null ? null : Instant.parse(string);
    }

    private LocalDate localDateValue(Object value) {
        String string = stringValue(value);
        return string == null ? null : LocalDate.parse(string);
    }
}
