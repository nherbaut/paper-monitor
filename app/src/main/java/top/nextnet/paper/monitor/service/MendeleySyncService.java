package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.MendeleyFeedSync;
import top.nextnet.paper.monitor.model.MendeleyPaperSync;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.FeedRepository;
import top.nextnet.paper.monitor.repo.MendeleyFeedSyncRepository;
import top.nextnet.paper.monitor.repo.MendeleyPaperSyncRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;

@ApplicationScoped
public class MendeleySyncService {
    private static final Logger LOG = Logger.getLogger(MendeleySyncService.class);
    private final MendeleyApiClient api;
    private final AuthService auth;
    private final MendeleyFeedSyncRepository feeds;
    private final MendeleyPaperSyncRepository links;
    private final PaperRepository papers;
    private final FeedRepository rssFeeds;
    private final PaperStorageService storage;
    private final PaperEventService events;

    public MendeleySyncService(MendeleyApiClient api, AuthService auth, MendeleyFeedSyncRepository feeds,
            MendeleyPaperSyncRepository links, PaperRepository papers, FeedRepository rssFeeds,
            PaperStorageService storage, PaperEventService events) {
        this.api = api; this.auth = auth; this.feeds = feeds; this.links = links; this.papers = papers;
        this.rssFeeds = rssFeeds; this.storage = storage; this.events = events;
    }

    public List<Map<String, Object>> folders(AppUser user) throws IOException {
        return api.folders(settings(user));
    }

    @Transactional
    public Map<String, Object> configure(AppUser user, LogicalFeed logicalFeed, String folderId, String folderName)
            throws IOException {
        if (folderId == null || folderId.isBlank()) throw new BadRequestException("Choose a Mendeley folder");
        UserSettings settings = settings(user);
        Map<String, Object> selected = api.folders(settings).stream()
                .filter(row -> folderId.equals(value(row.get("id")))).findFirst()
                .orElseThrow(() -> new BadRequestException("The selected Mendeley folder is unavailable"));
        MendeleyFeedSync config = feeds.findByUserAndFeed(user, logicalFeed).orElseGet(MendeleyFeedSync::new);
        config.user = user;
        config.logicalFeed = logicalFeed;
        config.rootFolderId = folderId;
        config.rootFolderName = first(value(selected.get("name")), folderName, folderId);
        config.enabled = true;
        config.stateFolderMappingsJson = JsonCodec.stringify(ensureStateFolders(settings, logicalFeed, folderId));
        config.pendingPreviewJson = null;
        config.lastError = null;
        if (config.id == null) feeds.persist(config);
        return configView(config);
    }

    @Transactional
    public Map<String, Object> preview(AppUser user, LogicalFeed logicalFeed) throws IOException {
        MendeleyFeedSync config = requireConfig(user, logicalFeed);
        UserSettings settings = settings(user);
        Map<String, String> stateFolders = ensureStateFolders(settings, logicalFeed, config.rootFolderId);
        config.stateFolderMappingsJson = JsonCodec.stringify(stateFolders);
        Map<String, Set<String>> memberships = folderMemberships(settings, config, stateFolders);
        Map<String, Map<String, Object>> remoteById = new LinkedHashMap<>();
        for (Map<String, Object> document : api.documents(settings)) {
            String id = value(document.get("id"));
            if (id != null && memberships.containsKey(id)) {
                document.put("paper_monitor_notes", api.documentNote(settings, id));
                remoteById.put(id, document);
            }
        }
        List<MendeleyPaperSync> existingLinks = links.findByFeed(config);
        Map<String, MendeleyPaperSync> linkByRemote = new LinkedHashMap<>();
        Map<Long, MendeleyPaperSync> linkByPaper = new LinkedHashMap<>();
        existingLinks.forEach(link -> {
            linkByRemote.put(link.mendeleyDocumentId, link);
            if (link.paper != null) linkByPaper.put(link.paper.id, link);
        });
        List<Paper> localPapers = papers.findAllForReader(logicalFeed);
        Map<String, Paper> localByDoi = new LinkedHashMap<>();
        localPapers.forEach(paper -> { String doi = localDoi(paper); if (doi != null) localByDoi.putIfAbsent(doi, paper); });
        Set<Long> matchedLocal = new LinkedHashSet<>();
        List<Map<String, Object>> actions = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> entry : remoteById.entrySet()) {
            String documentId = entry.getKey();
            Map<String, Object> remote = entry.getValue();
            MendeleyPaperSync link = linkByRemote.get(documentId);
            Paper local = link == null ? localByDoi.get(remoteDoi(remote)) : link.paper;
            List<String> assignedRemoteStates = remoteStates(memberships.get(documentId), stateFolders);
            if (assignedRemoteStates.size() > 1) {
                actions.add(action(local == null ? "IMPORT_CONFLICT" : "CONFLICT",
                        local == null ? null : local.id, documentId,
                        "Document belongs to multiple mapped state folders", remote));
                if (local != null) matchedLocal.add(local.id);
                continue;
            }
            if (local == null) {
                actions.add(action("IMPORT", null, documentId, "New Mendeley document", remote));
                continue;
            }
            matchedLocal.add(local.id);
            String localHash = fingerprint(localSnapshot(local));
            String remoteHash = fingerprint(remoteSnapshot(remote, remoteState(memberships.get(documentId), stateFolders)));
            if (link == null) {
                actions.add(action("CONFLICT", local.id, documentId, "Matching DOI has different ownership", remote));
            } else {
                boolean localChanged = !Objects.equals(localHash, link.localFingerprint);
                boolean remoteChanged = !Objects.equals(remoteHash, link.remoteFingerprint);
                if (localChanged && remoteChanged) actions.add(action("CONFLICT", local.id, documentId, "Both copies changed", remote));
                else if (localChanged) actions.add(action("PUSH", local.id, documentId, "Paper Monitor changed", remote));
                else if (remoteChanged) actions.add(action("PULL", local.id, documentId, "Mendeley changed", remote));
            }
        }
        for (Paper paper : localPapers) {
            if (!matchedLocal.contains(paper.id) && !linkByPaper.containsKey(paper.id)) {
                actions.add(action("EXPORT", paper.id, null, "New Paper Monitor paper", null));
            }
        }
        for (MendeleyPaperSync link : existingLinks) {
            if (link.paper != null && !remoteById.containsKey(link.mendeleyDocumentId)
                    && !MendeleyPaperSync.IGNORED.equals(link.status)) {
                actions.add(action("REMOTE_DELETED", link.paper.id, link.mendeleyDocumentId,
                        "Document is no longer in the mapped Mendeley folders", null));
            }
        }
        Map<String, Object> preview = previewPayload(config, actions);
        config.pendingPreviewJson = JsonCodec.stringify(preview);
        config.previewedAt = Instant.now();
        config.lastError = null;
        return preview;
    }

    @Transactional
    public Map<String, Object> apply(AppUser user, LogicalFeed logicalFeed) throws IOException {
        MendeleyFeedSync config = requireConfig(user, logicalFeed);
        if (config.pendingPreviewJson == null || config.previewedAt == null
                || config.previewedAt.isBefore(Instant.now().minus(30, ChronoUnit.MINUTES))) {
            throw new BadRequestException("Create a fresh Mendeley sync preview first");
        }
        Map<String, Object> preview = object(JsonCodec.parse(config.pendingPreviewJson));
        List<Map<String, Object>> actions = objects(preview.get("actions"));
        UserSettings settings = settings(user);
        Map<String, String> stateFolders = mappings(config);
        int applied = 0, conflicts = 0;
        for (Map<String, Object> action : actions) {
            String type = value(action.get("type"));
            Long paperId = longValue(action.get("paperId"));
            String documentId = value(action.get("documentId"));
            if ("CONFLICT".equals(type) || "REMOTE_DELETED".equals(type)) {
                recordConflict(config, paperId, documentId, type, action);
                conflicts++;
                continue;
            }
            if ("IMPORT".equals(type) || "IMPORT_CONFLICT".equals(type)) {
                Map<String, Object> remote = remoteDocument(settings, documentId);
                Paper paper = importPaper(settings, config, remote, stateFolders);
                syncLink(config, paper, remote, documentId);
                if ("IMPORT_CONFLICT".equals(type)) {
                    recordConflict(config, paper.id, documentId, MendeleyPaperSync.CONFLICT, action);
                    conflicts++;
                }
            } else if ("EXPORT".equals(type)) {
                Paper paper = requirePaper(logicalFeed, paperId);
                Map<String, Object> remote = api.createDocument(settings, documentPayload(paper));
                documentId = value(remote.get("id"));
                api.addToFolder(settings, config.rootFolderId, documentId);
                setRemoteState(settings, documentId, paper.status, stateFolders);
                syncPdfToMendeley(settings, paper, documentId);
                api.updateDocumentNote(settings, documentId, settings.mendeleyProfileId, paper.notes);
                remote = remoteDocument(settings, documentId);
                syncLink(config, paper, remote, documentId);
            } else if ("PULL".equals(type)) {
                Paper paper = requirePaper(logicalFeed, paperId);
                Map<String, Object> remote = remoteDocument(settings, documentId);
                applyRemoteToPaper(settings, paper, remote, stateFolders, config);
                syncLink(config, paper, remote, documentId);
            } else if ("PUSH".equals(type)) {
                Paper paper = requirePaper(logicalFeed, paperId);
                Map<String, Object> remote = api.updateDocument(settings, documentId, documentPayload(paper), null);
                setRemoteState(settings, documentId, paper.status, stateFolders);
                syncPdfToMendeley(settings, paper, documentId);
                api.updateDocumentNote(settings, documentId, settings.mendeleyProfileId, paper.notes);
                remote = remoteDocument(settings, documentId);
                syncLink(config, paper, remote, documentId);
            }
            applied++;
        }
        config.lastSyncedAt = Instant.now();
        config.pendingPreviewJson = null;
        config.lastError = null;
        return Map.of("applied", applied, "conflicts", conflicts, "lastSyncedAt", config.lastSyncedAt.toString());
    }

    @Transactional
    public Map<String, Object> resolve(AppUser user, LogicalFeed logicalFeed, Long linkId, String resolution) throws IOException {
        MendeleyFeedSync config = requireConfig(user, logicalFeed);
        MendeleyPaperSync link = links.findById(linkId);
        if (link == null || !Objects.equals(link.feedSync.id, config.id)) throw new NotFoundException();
        if (link.paper == null) throw new BadRequestException("The local paper no longer exists");
        UserSettings settings = settings(user);
        Map<String, String> stateFolders = mappings(config);
        if ("KEEP_LOCAL".equals(resolution)) {
            Map<String, Object> remote;
            try {
                remote = api.updateDocument(settings, link.mendeleyDocumentId, documentPayload(link.paper), null);
            } catch (MendeleyApiClient.MendeleyApiException error) {
                if (error.status != 404) throw error;
                remote = api.createDocument(settings, documentPayload(link.paper));
                link.mendeleyDocumentId = value(remote.get("id"));
            }
            api.addToFolder(settings, config.rootFolderId, link.mendeleyDocumentId);
            setRemoteState(settings, link.mendeleyDocumentId, link.paper.status, stateFolders);
            api.updateDocumentNote(settings, link.mendeleyDocumentId, settings.mendeleyProfileId, link.paper.notes);
            remote = remoteDocument(settings, link.mendeleyDocumentId);
            syncLink(config, link.paper, remote, link.mendeleyDocumentId);
        } else if ("KEEP_MENDELEY".equals(resolution)) {
            Map<String, Object> remote = remoteDocument(settings, link.mendeleyDocumentId);
            applyRemoteToPaper(settings, link.paper, remote, stateFolders, config);
            setRemoteState(settings, link.mendeleyDocumentId, link.paper.status, stateFolders);
            syncLink(config, link.paper, remote, link.mendeleyDocumentId);
        } else if ("MERGE".equals(resolution)) {
            Map<String, Object> remote = remoteDocument(settings, link.mendeleyDocumentId);
            link.paper.tags = mergeTags(link.paper.tags, strings(remote.get("tags")));
            String remoteNotes = value(remote.get("paper_monitor_notes"));
            if (remoteNotes != null && (link.paper.notes == null || !link.paper.notes.contains(remoteNotes))) {
                link.paper.notes = first(link.paper.notes, "") + (link.paper.notes == null || link.paper.notes.isBlank() ? "" : "\n\n") + remoteNotes;
            }
            Map<String, Object> updated = api.updateDocument(settings, link.mendeleyDocumentId, documentPayload(link.paper), null);
            api.updateDocumentNote(settings, link.mendeleyDocumentId, settings.mendeleyProfileId, link.paper.notes);
            setRemoteState(settings, link.mendeleyDocumentId, link.paper.status, stateFolders);
            updated = remoteDocument(settings, link.mendeleyDocumentId);
            syncLink(config, link.paper, updated, link.mendeleyDocumentId);
        } else if ("KEEP_BOTH".equals(resolution) && MendeleyPaperSync.REMOTE_DELETED.equals(link.status)) {
            link.status = MendeleyPaperSync.IGNORED;
            link.conflictJson = null;
        } else throw new BadRequestException("Unknown conflict resolution");
        return linkView(link);
    }

    public Map<String, Object> status(AppUser user, List<LogicalFeed> logicalFeeds) {
        UserSettings settings = auth.ensureSettings(user);
        List<Map<String, Object>> configs = new ArrayList<>();
        for (LogicalFeed feed : logicalFeeds) {
            MendeleyFeedSync config = feeds.findByUserAndFeed(user, feed).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("logicalFeedId", feed.id); row.put("logicalFeedName", feed.name);
            row.put("workflowStates", feed.workflowStateList());
            if (config != null) row.putAll(configView(config));
            configs.add(row);
        }
        return Map.of("serverEnabled", true, "connected", settings.hasMendeleyConnection(),
                "displayName", nonNull(settings.mendeleyDisplayName), "feeds", configs);
    }

    private Map<String, String> ensureStateFolders(UserSettings settings, LogicalFeed feed, String root) throws IOException {
        List<Map<String, Object>> all = api.folders(settings);
        Map<String, String> result = new LinkedHashMap<>();
        for (String state : feed.workflowStateList()) {
            String label = feed.workflowConfig().state(state).label();
            Map<String, Object> found = all.stream().filter(row -> label.equals(value(row.get("name")))
                    && root.equals(value(row.get("parent_id")))).findFirst().orElse(null);
            if (found == null) found = api.createFolder(settings, label, root);
            result.put(state, value(found.get("id")));
        }
        return result;
    }

    private Map<String, Set<String>> folderMemberships(UserSettings settings, MendeleyFeedSync config,
            Map<String, String> stateFolders) throws IOException {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String id : api.folderDocumentIds(settings, config.rootFolderId)) result.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(config.rootFolderId);
        for (String folder : stateFolders.values()) for (String id : api.folderDocumentIds(settings, folder)) result.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(folder);
        return result;
    }

    private Paper importPaper(UserSettings settings, MendeleyFeedSync config, Map<String, Object> remote,
            Map<String, String> stateFolders) throws IOException {
        Paper paper = new Paper();
        paper.logicalFeed = config.logicalFeed;
        paper.feed = mendeleySource(config);
        paper.discoveredAt = Instant.now();
        paper.status = remoteStateForDocument(settings, value(remote.get("id")), stateFolders, config.logicalFeed.initialPaperStatus());
        applyMetadata(paper, remote);
        papers.persist(paper);
        setRemoteState(settings, value(remote.get("id")), paper.status, stateFolders);
        syncPdfFromMendeley(settings, paper, value(remote.get("id")));
        events.log(paper, "MENDELEY_IMPORT", "Imported from Mendeley");
        return paper;
    }

    private void applyRemoteToPaper(UserSettings settings, Paper paper, Map<String, Object> remote,
            Map<String, String> stateFolders, MendeleyFeedSync config) throws IOException {
        applyMetadata(paper, remote);
        paper.status = remoteStateForDocument(settings, value(remote.get("id")), stateFolders, paper.status);
        syncPdfFromMendeley(settings, paper, value(remote.get("id")));
        events.log(paper, "MENDELEY_PULL", "Updated from Mendeley");
    }

    private void applyMetadata(Paper paper, Map<String, Object> remote) {
        paper.title = first(value(remote.get("title")), "Untitled Mendeley document");
        paper.sourceLink = first(remoteDoi(remote) == null ? null : "https://doi.org/" + remoteDoi(remote),
                firstString(remote.get("websites")), "mendeley:" + value(remote.get("id")));
        paper.summary = value(remote.get("abstract"));
        paper.publisher = first(value(remote.get("source")), value(remote.get("publisher")));
        Integer year = intValue(remote.get("year"));
        paper.publishedOn = year == null ? null : LocalDate.of(year, 1, 1);
        paper.authors = authorText(remote.get("authors"));
        paper.tags = mergeTags(null, strings(remote.get("tags")));
        String notes = value(remote.get("paper_monitor_notes"));
        if (notes != null) paper.notes = notes;
    }

    private Map<String, Object> documentPayload(Paper paper) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "journal"); result.put("title", paper.title);
        if (paper.summary != null) result.put("abstract", paper.summary);
        if (paper.publisher != null) result.put("source", paper.publisher);
        if (paper.publishedOn != null) result.put("year", paper.publishedOn.getYear());
        String doi = localDoi(paper); if (doi != null) result.put("identifiers", Map.of("doi", doi));
        if (paper.tags != null) result.put("tags", List.of(paper.tags.split("\\s*,\\s*|\\R")));
        List<Map<String, String>> authors = new ArrayList<>();
        if (paper.authors != null) for (String name : paper.authors.split("\\s*;\\s*")) {
            String[] parts = name.trim().split("\\s+");
            if (parts.length > 0) authors.add(Map.of("first_name", parts.length == 1 ? "" : String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1)), "last_name", parts[parts.length - 1]));
        }
        if (!authors.isEmpty()) result.put("authors", authors);
        return result;
    }

    private void setRemoteState(UserSettings settings, String documentId, String state, Map<String, String> stateFolders) throws IOException {
        for (Map.Entry<String, String> entry : stateFolders.entrySet()) {
            List<String> ids = api.folderDocumentIds(settings, entry.getValue());
            if (entry.getKey().equals(state) && !ids.contains(documentId)) api.addToFolder(settings, entry.getValue(), documentId);
            else if (!entry.getKey().equals(state) && ids.contains(documentId)) api.removeFromFolder(settings, entry.getValue(), documentId);
        }
    }

    private String remoteStateForDocument(UserSettings settings, String documentId, Map<String, String> stateFolders,
            String fallback) throws IOException {
        List<String> states = new ArrayList<>();
        for (Map.Entry<String, String> entry : stateFolders.entrySet()) if (api.folderDocumentIds(settings, entry.getValue()).contains(documentId)) states.add(entry.getKey());
        return states.size() == 1 ? states.get(0) : fallback;
    }

    private void syncPdfToMendeley(UserSettings settings, Paper paper, String documentId) throws IOException {
        if (paper.uploadedPdfPath == null || !api.files(settings, documentId).isEmpty()) return;
        api.uploadPdf(settings, documentId, storage.resolve(paper.uploadedPdfPath), first(paper.uploadedPdfFileName, "paper.pdf"));
    }

    private void syncPdfFromMendeley(UserSettings settings, Paper paper, String documentId) throws IOException {
        if (paper.uploadedPdfPath != null) return;
        Map<String, Object> file = api.files(settings, documentId).stream()
                .filter(row -> value(row.get("mime_type")) == null || "application/pdf".equals(value(row.get("mime_type"))))
                .findFirst().orElse(null);
        if (file == null) return;
        byte[] bytes = api.downloadFile(settings, value(file.get("id")));
        Path temp = Files.createTempFile("mendeley-", ".pdf");
        try {
            Files.write(temp, bytes);
            String name = first(value(file.get("file_name")), "mendeley-paper.pdf");
            PaperStorageService.StoredPdf stored = storage.storePdf(temp, name.endsWith(".pdf") ? name : name + ".pdf");
            paper.uploadedPdfPath = stored.storedPath(); paper.uploadedPdfFileName = stored.originalFileName();
        } finally { Files.deleteIfExists(temp); }
    }

    private Map<String, Object> remoteDocument(UserSettings settings, String documentId) throws IOException {
        Map<String, Object> document = new LinkedHashMap<>(api.document(settings, documentId));
        document.put("paper_monitor_notes", api.documentNote(settings, documentId));
        return document;
    }

    private void syncLink(MendeleyFeedSync config, Paper paper, Map<String, Object> remote, String documentId) {
        MendeleyPaperSync link = links.findByFeedAndDocument(config, documentId).orElseGet(MendeleyPaperSync::new);
        link.feedSync = config; link.paper = paper; link.mendeleyDocumentId = documentId;
        link.localFingerprint = fingerprint(localSnapshot(paper));
        link.remoteFingerprint = fingerprint(remoteSnapshot(remote, paper.status));
        link.remoteModifiedAt = instant(remote.get("last_modified"));
        link.status = MendeleyPaperSync.SYNCED; link.conflictJson = null; link.syncedAt = Instant.now();
        if (link.id == null) links.persist(link);
    }

    private void recordConflict(MendeleyFeedSync config, Long paperId, String documentId, String status, Map<String, Object> action) {
        MendeleyPaperSync link = links.findByFeedAndDocument(config, documentId).orElseGet(MendeleyPaperSync::new);
        link.feedSync = config; link.paper = paperId == null ? null : papers.findById(paperId);
        link.mendeleyDocumentId = documentId; link.status = status; link.conflictJson = JsonCodec.stringify(action);
        if (link.id == null) links.persist(link);
    }

    private Feed mendeleySource(MendeleyFeedSync config) {
        String url = "mendeley://" + config.user.id + "/" + config.rootFolderId;
        Feed feed = rssFeeds.find("logicalFeed = ?1 and url = ?2", config.logicalFeed, url).firstResult();
        if (feed == null) { feed = new Feed(); feed.logicalFeed = config.logicalFeed; feed.name = "Mendeley"; feed.url = url; feed.pollIntervalMinutes = 525600; rssFeeds.persist(feed); }
        return feed;
    }

    private Paper requirePaper(LogicalFeed feed, Long id) { Paper paper = papers.findById(id); if (paper == null || !Objects.equals(paper.logicalFeed.id, feed.id)) throw new NotFoundException(); return paper; }
    private MendeleyFeedSync requireConfig(AppUser user, LogicalFeed feed) { MendeleyFeedSync config = feeds.findByUserAndFeed(user, feed).orElseThrow(() -> new BadRequestException("Configure this feed's Mendeley folder first")); if (!config.enabled) throw new BadRequestException("Mendeley sync is disabled for this feed"); return config; }
    private UserSettings settings(AppUser user) { UserSettings settings = auth.ensureSettings(user); if (!settings.hasMendeleyConnection()) throw new BadRequestException("Connect Mendeley first"); return settings; }

    private Map<String, Object> previewPayload(MendeleyFeedSync config, List<Map<String, Object>> actions) { Map<String, Long> counts = new LinkedHashMap<>(); actions.forEach(a -> counts.merge(value(a.get("type")), 1L, Long::sum)); return Map.of("feedId", config.logicalFeed.id, "generatedAt", Instant.now().toString(), "counts", counts, "actions", actions); }
    private Map<String, Object> action(String type, Long paperId, String documentId, String reason, Map<String, Object> remote) {
        Map<String, Object> row = new LinkedHashMap<>();
        Paper local = paperId == null ? null : papers.findById(paperId);
        row.put("type", type); row.put("paperId", paperId); row.put("documentId", documentId);
        row.put("reason", reason);
        row.put("title", remote == null ? (local == null ? "" : local.title) : first(value(remote.get("title")), "Untitled"));
        if (local != null) row.put("local", localSnapshot(local));
        if (remote != null) row.put("mendeley", remoteSnapshot(remote, null));
        return row;
    }
    private String requirePaperTitle(Long id) { Paper p = papers.findById(id); return p == null ? "Deleted paper" : p.title; }
    private Map<String, Object> configView(MendeleyFeedSync config) { Map<String, Object> row = new LinkedHashMap<>(); row.put("configured", true); row.put("configId", config.id); row.put("rootFolderId", config.rootFolderId); row.put("rootFolderName", config.rootFolderName); row.put("enabled", config.enabled); row.put("stateFolders", mappings(config)); row.put("lastSyncedAt", config.lastSyncedAt); row.put("lastError", config.lastError); row.put("conflicts", links.findByFeed(config).stream().filter(l -> MendeleyPaperSync.CONFLICT.equals(l.status) || MendeleyPaperSync.REMOTE_DELETED.equals(l.status) || MendeleyPaperSync.LOCAL_DELETED.equals(l.status)).map(this::linkView).toList()); return row; }
    private Map<String, Object> linkView(MendeleyPaperSync link) { Map<String, Object> row = new LinkedHashMap<>(); row.put("id", link.id); row.put("paperId", link.paper == null ? null : link.paper.id); row.put("title", link.paper == null ? "Deleted paper" : link.paper.title); row.put("documentId", link.mendeleyDocumentId); row.put("status", link.status); row.put("details", link.conflictJson == null ? null : JsonCodec.parse(link.conflictJson)); return row; }

    private Map<String, Object> localSnapshot(Paper p) { Map<String, Object> m = new LinkedHashMap<>(); m.put("title", p.title); m.put("doi", localDoi(p)); m.put("authors", p.authors); m.put("abstract", p.summary); m.put("year", p.publishedOn == null ? null : p.publishedOn.getYear()); m.put("source", p.publisher); m.put("tags", p.tags); m.put("notes", p.notes); m.put("state", p.status); m.put("pdf", p.uploadedPdfPath); return m; }
    private Map<String, Object> remoteSnapshot(Map<String, Object> r, String state) { Map<String, Object> m = new LinkedHashMap<>(); m.put("title", r.get("title")); m.put("doi", remoteDoi(r)); m.put("authors", authorText(r.get("authors"))); m.put("abstract", r.get("abstract")); m.put("year", r.get("year")); m.put("source", first(value(r.get("source")), value(r.get("publisher")))); m.put("tags", strings(r.get("tags"))); m.put("notes", r.get("paper_monitor_notes")); m.put("state", state); m.put("pdf", r.get("file_attached")); return m; }
    static String fingerprint(Map<String, Object> map) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JsonCodec.stringify(map).getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String localDoi(Paper p) { for (String s : List.of(first(p.sourceLink, ""), first(p.openAccessLink, ""))) { int i = s.toLowerCase(Locale.ROOT).indexOf("doi.org/"); if (i >= 0) return s.substring(i + 8).trim().toLowerCase(Locale.ROOT); } return null; }
    private static String remoteDoi(Map<String, Object> r) { Object ids = r.get("identifiers"); if (ids instanceof Map<?, ?> map) { String doi = value(map.get("doi")); return doi == null ? null : doi.toLowerCase(Locale.ROOT); } return null; }
    private static String authorText(Object raw) { List<String> names = new ArrayList<>(); for (Map<String, Object> p : objects(raw)) names.add((first(value(p.get("first_name")), "") + " " + first(value(p.get("last_name")), "")).trim()); return names.isEmpty() ? null : String.join("; ", names); }
    static String mergeTags(String local, List<String> remote) { LinkedHashSet<String> tags = new LinkedHashSet<>(); if (local != null) for (String tag : local.split("\\s*,\\s*|\\R")) if (!tag.isBlank()) tags.add(tag.trim()); tags.addAll(remote); return tags.isEmpty() ? null : String.join(", ", tags); }
    private static String remoteState(Set<String> memberships, Map<String, String> mappings) { if (memberships == null) return null; List<String> states = mappings.entrySet().stream().filter(e -> memberships.contains(e.getValue())).map(Map.Entry::getKey).toList(); return states.size() == 1 ? states.get(0) : null; }
    static List<String> remoteStates(Set<String> memberships, Map<String, String> mappings) { if (memberships == null) return List.of(); return mappings.entrySet().stream().filter(e -> memberships.contains(e.getValue())).map(Map.Entry::getKey).toList(); }
    @SuppressWarnings("unchecked") private static Map<String, String> mappings(MendeleyFeedSync c) { if (c.stateFolderMappingsJson == null) return Map.of(); Object p = JsonCodec.parse(c.stateFolderMappingsJson); if (!(p instanceof Map<?, ?> m)) return Map.of(); Map<String, String> out = new LinkedHashMap<>(); m.forEach((k,v) -> out.put(String.valueOf(k), String.valueOf(v))); return out; }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object raw) { return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    private static List<Map<String, Object>> objects(Object raw) { List<Map<String, Object>> out = new ArrayList<>(); if (raw instanceof List<?> list) for (Object o : list) if (o instanceof Map<?, ?> m) out.add(object(m)); return out; }
    private static List<String> strings(Object raw) { List<String> out = new ArrayList<>(); if (raw instanceof List<?> list) for (Object o : list) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o)); return out; }
    private static String firstString(Object raw) { List<String> values = strings(raw); return values.isEmpty() ? null : values.get(0); }
    private static String value(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
    static String nonNull(String value) { return value == null ? "" : value; }
    private static String first(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v; return null; }
    private static Long longValue(Object v) { return v == null ? null : Long.valueOf(String.valueOf(v)); }
    private static Integer intValue(Object v) { return v == null ? null : Integer.valueOf(String.valueOf(v)); }
    private static Instant instant(Object v) { try { return v == null ? null : Instant.parse(String.valueOf(v)); } catch (Exception e) { return null; } }
}
