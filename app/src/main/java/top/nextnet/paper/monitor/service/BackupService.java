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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PaperEvent;
import top.nextnet.paper.monitor.model.TtsSettings;
import top.nextnet.paper.monitor.repo.FeedRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import top.nextnet.paper.monitor.repo.PaperEventRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;
import top.nextnet.paper.monitor.repo.TtsSettingsRepository;

@ApplicationScoped
public class BackupService {

    private final LogicalFeedRepository logicalFeedRepository;
    private final FeedRepository feedRepository;
    private final PaperRepository paperRepository;
    private final PaperEventRepository paperEventRepository;
    private final PaperStorageService paperStorageService;
    private final TtsSettingsRepository ttsSettingsRepository;

    public BackupService(
            LogicalFeedRepository logicalFeedRepository,
            FeedRepository feedRepository,
            PaperRepository paperRepository,
            PaperEventRepository paperEventRepository,
            PaperStorageService paperStorageService,
            TtsSettingsRepository ttsSettingsRepository
    ) {
        this.logicalFeedRepository = logicalFeedRepository;
        this.feedRepository = feedRepository;
        this.paperRepository = paperRepository;
        this.paperEventRepository = paperEventRepository;
        this.paperStorageService = paperStorageService;
        this.ttsSettingsRepository = ttsSettingsRepository;
    }

    @Transactional
    public byte[] exportZip() throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream, StandardCharsets.UTF_8)) {
            writeJsonEntry(zipOutputStream, "data.json", JsonCodec.stringify(exportData()));
            for (var file : paperStorageService.listStoredFiles()) {
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

    private Map<String, Object> exportData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        data.put("logicalFeeds", exportLogicalFeeds());
        data.put("feeds", exportFeeds());
        data.put("papers", exportPapers());
        data.put("paperEvents", exportPaperEvents());
        data.put("ttsSettings", exportTtsSettings());
        return data;
    }

    private Map<String, Object> exportTtsSettings() {
        TtsSettings settings = ttsSettingsRepository.first();
        if (settings == null) {
            return Map.of();
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("voice", settings.voice);
        item.put("speedMultiplier", settings.speedMultiplier);
        return item;
    }

    private List<Map<String, Object>> exportLogicalFeeds() {
        List<Map<String, Object>> logicalFeeds = new ArrayList<>();
        for (LogicalFeed logicalFeed : logicalFeedRepository.findAll().list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", logicalFeed.id);
            item.put("name", logicalFeed.name);
            item.put("description", logicalFeed.description);
            item.put("workflowStates", logicalFeed.workflowStates);
            item.put("gitRepoToken", logicalFeed.gitRepoToken);
            item.put("lastProcessedGitCommit", logicalFeed.lastProcessedGitCommit);
            logicalFeeds.add(item);
        }
        return logicalFeeds;
    }

    private List<Map<String, Object>> exportFeeds() {
        List<Map<String, Object>> feeds = new ArrayList<>();
        for (Feed feed : feedRepository.findAll().list()) {
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

    private List<Map<String, Object>> exportPapers() {
        List<Map<String, Object>> papers = new ArrayList<>();
        for (Paper paper : paperRepository.findAll().list()) {
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

    private List<Map<String, Object>> exportPaperEvents() {
        List<Map<String, Object>> events = new ArrayList<>();
        for (PaperEvent event : paperEventRepository.findAll().list()) {
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

    private void restoreData(String json, Map<String, byte[]> files) throws IOException {
        Object parsed = JsonCodec.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Invalid backup JSON");
        }

        paperEventRepository.deleteAll();
        paperRepository.deleteAll();
        feedRepository.deleteAll();
        logicalFeedRepository.deleteAll();
        ttsSettingsRepository.deleteAll();
        paperStorageService.clearAll();

        Map<Long, LogicalFeed> logicalFeedsByOldId = new LinkedHashMap<>();
        for (Object value : asList(root.get("logicalFeeds"))) {
            Map<?, ?> item = asMap(value);
            LogicalFeed logicalFeed = new LogicalFeed();
            logicalFeed.name = stringValue(item.get("name"));
            logicalFeed.description = stringValue(item.get("description"));
            logicalFeed.workflowStates = stringValue(item.get("workflowStates"));
            logicalFeed.gitRepoToken = stringValue(item.get("gitRepoToken"));
            logicalFeed.lastProcessedGitCommit = stringValue(item.get("lastProcessedGitCommit"));
            logicalFeedRepository.persist(logicalFeed);
            logicalFeedsByOldId.put(longValue(item.get("id")), logicalFeed);
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

        Map<?, ?> ttsSettings = asOptionalMap(root.get("ttsSettings"));
        if (ttsSettings != null) {
            TtsSettings settings = new TtsSettings();
            settings.voice = stringValue(ttsSettings.get("voice"));
            settings.speedMultiplier = doubleValue(ttsSettings.get("speedMultiplier"));
            ttsSettingsRepository.persist(settings);
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

    private Map<?, ?> asOptionalMap(Object value) {
        if (value == null) {
            return null;
        }
        return asMap(value);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
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

    private Instant instantValue(Object value) {
        String string = stringValue(value);
        return string == null ? null : Instant.parse(string);
    }

    private LocalDate localDateValue(Object value) {
        String string = stringValue(value);
        return string == null ? null : LocalDate.parse(string);
    }
}
