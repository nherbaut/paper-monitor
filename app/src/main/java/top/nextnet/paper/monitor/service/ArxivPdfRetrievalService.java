package top.nextnet.paper.monitor.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.ArxivPdfRetrievalItem;
import top.nextnet.paper.monitor.model.ArxivPdfRetrievalJob;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.repo.AppUserRepository;
import top.nextnet.paper.monitor.repo.ArxivPdfRetrievalItemRepository;
import top.nextnet.paper.monitor.repo.ArxivPdfRetrievalJobRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;

@ApplicationScoped
public class ArxivPdfRetrievalService {
    private static final Logger LOG = Logger.getLogger(ArxivPdfRetrievalService.class);

    private final ArxivPdfRetrievalJobRepository jobs;
    private final ArxivPdfRetrievalItemRepository items;
    private final PaperRepository papers;
    private final LogicalFeedRepository feeds;
    private final AppUserRepository users;
    private final PaperPdfImportService pdfs;
    private final PaperStorageService storage;
    private final PaperEventService events;
    private final PaperGitSyncService gitSync;
    private final GoogleDriveSyncService driveSync;
    private final ManagedExecutor executor;
    private final Set<Long> activeJobs = ConcurrentHashMap.newKeySet();

    public ArxivPdfRetrievalService(
            ArxivPdfRetrievalJobRepository jobs,
            ArxivPdfRetrievalItemRepository items,
            PaperRepository papers,
            LogicalFeedRepository feeds,
            AppUserRepository users,
            PaperPdfImportService pdfs,
            PaperStorageService storage,
            PaperEventService events,
            PaperGitSyncService gitSync,
            GoogleDriveSyncService driveSync,
            ManagedExecutor executor
    ) {
        this.jobs = jobs;
        this.items = items;
        this.papers = papers;
        this.feeds = feeds;
        this.users = users;
        this.pdfs = pdfs;
        this.storage = storage;
        this.events = events;
        this.gitSync = gitSync;
        this.driveSync = driveSync;
        this.executor = executor;
    }

    public Map<String, Object> preview(LogicalFeed feed, String state) {
        return QuarkusTransaction.requiringNew().call(() -> {
            StateSelection selection = requireState(feed, state);
            List<Paper> matching = statePapers(feed, selection.id());
            int eligible = 0;
            int unsupported = 0;
            int attached = 0;
            for (Paper paper : matching) {
                if (hasPdf(paper)) {
                    attached++;
                } else if (pdfs.supportedPdfUrl(paper).isPresent()) {
                    eligible++;
                } else {
                    unsupported++;
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("logicalFeedId", feed.id);
            result.put("feedName", feed.name);
            result.put("state", selection.id());
            result.put("stateLabel", selection.label());
            result.put("total", matching.size());
            result.put("eligible", eligible);
            result.put("unsupported", unsupported);
            result.put("attached", attached);
            return result;
        });
    }

    public Map<String, Object> start(AppUser user, LogicalFeed feed, String state) {
        Long jobId = QuarkusTransaction.requiringNew().call(() -> {
            LogicalFeed lockedFeed = lockFeed(feed.id);
            ArxivPdfRetrievalJob active = jobs.findActiveByFeed(lockedFeed.id).orElse(null);
            if (active != null) {
                return active.id;
            }
            StateSelection selection = requireState(lockedFeed, state);
            List<Paper> candidates = statePapers(lockedFeed, selection.id()).stream()
                    .filter(paper -> !hasPdf(paper))
                    .filter(paper -> pdfs.supportedPdfUrl(paper).isPresent())
                    .toList();
            return createJob(user, lockedFeed, selection, candidates, null).id;
        });
        enqueue(jobId);
        return status(feed.id, jobId);
    }

    public Map<String, Object> retry(AppUser user, LogicalFeed feed, Long sourceJobId) {
        Long jobId = QuarkusTransaction.requiringNew().call(() -> {
            LogicalFeed lockedFeed = lockFeed(feed.id);
            ArxivPdfRetrievalJob source = requireJob(lockedFeed.id, sourceJobId);
            if (ArxivPdfRetrievalJobRepository.isActive(source.status)) {
                throw new BadRequestException("The arXiv PDF retrieval is still running");
            }
            ArxivPdfRetrievalJob active = jobs.findActiveByFeed(lockedFeed.id).orElse(null);
            if (active != null) {
                return active.id;
            }
            StateSelection selection = requireState(lockedFeed, source.stateId);
            Set<Long> failedIds = items.findFailedByJob(source.id).stream()
                    .map(item -> item.paperId).collect(java.util.stream.Collectors.toSet());
            List<Paper> candidates = statePapers(lockedFeed, selection.id()).stream()
                    .filter(paper -> failedIds.contains(paper.id))
                    .filter(paper -> !hasPdf(paper))
                    .filter(paper -> pdfs.supportedPdfUrl(paper).isPresent())
                    .toList();
            return createJob(user, lockedFeed, selection, candidates, source.id).id;
        });
        enqueue(jobId);
        return status(feed.id, jobId);
    }

    public Map<String, Object> latest(Long feedId) {
        return QuarkusTransaction.requiringNew().call(() -> jobs.findLatestByFeed(feedId)
                .map(this::jobView)
                .orElseGet(() -> idleView(feedId)));
    }

    public Map<String, Object> status(Long feedId, Long jobId) {
        return QuarkusTransaction.requiringNew().call(() -> jobView(requireJob(feedId, jobId)));
    }

    public Long feedId(Long jobId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ArxivPdfRetrievalJob job = jobs.findById(jobId);
            if (job == null) throw new NotFoundException();
            return job.logicalFeedId;
        });
    }

    void resumeInterruptedJobs(@Observes StartupEvent ignored) {
        List<Long> interrupted = QuarkusTransaction.requiringNew().call(() -> {
            List<ArxivPdfRetrievalJob> found = jobs.findInterrupted();
            for (ArxivPdfRetrievalJob job : found) {
                for (ArxivPdfRetrievalItem item : items.findQueuedByJob(job.id)) {
                    item.status = "QUEUED";
                }
                job.status = "QUEUED";
                job.phase = "Resuming after application restart";
                job.currentPaperTitle = null;
            }
            return found.stream().map(job -> job.id).toList();
        });
        interrupted.forEach(this::enqueue);
    }

    private ArxivPdfRetrievalJob createJob(AppUser user, LogicalFeed feed, StateSelection selection,
            List<Paper> candidates, Long sourceJobId) {
        ArxivPdfRetrievalJob job = new ArxivPdfRetrievalJob();
        job.logicalFeedId = feed.id;
        job.userId = user.id;
        job.sourceJobId = sourceJobId;
        job.feedName = feed.name;
        job.stateId = selection.id();
        job.stateLabel = selection.label();
        job.status = candidates.isEmpty() ? "COMPLETED" : "QUEUED";
        job.phase = candidates.isEmpty() ? "No missing arXiv PDFs in this state" : "Waiting for background worker";
        job.totalItems = candidates.size();
        job.startedAt = Instant.now();
        job.finishedAt = candidates.isEmpty() ? job.startedAt : null;
        jobs.persist(job);
        jobs.flush();
        int sequence = 0;
        for (Paper paper : candidates) {
            ArxivPdfRetrievalItem item = new ArxivPdfRetrievalItem();
            item.jobId = job.id;
            item.paperId = paper.id;
            item.sequenceNumber = sequence++;
            item.paperTitle = paper.title;
            item.sourceUrl = pdfs.supportedPdfUrl(paper).orElseThrow();
            items.persist(item);
        }
        return job;
    }

    private void enqueue(Long jobId) {
        if (jobId == null || !activeJobs.add(jobId)) return;
        boolean runnable = QuarkusTransaction.requiringNew().call(() -> {
            ArxivPdfRetrievalJob job = jobs.findById(jobId);
            return job != null && ArxivPdfRetrievalJobRepository.isActive(job.status);
        });
        if (!runnable) {
            activeJobs.remove(jobId);
            return;
        }
        executor.execute(() -> run(jobId));
    }

    private void run(Long jobId) {
        boolean importedAny = QuarkusTransaction.requiringNew().call(() -> {
            ArxivPdfRetrievalJob job = jobs.findById(jobId);
            return job != null && count(job.importedItems) > 0;
        });
        try {
            setRunning(jobId);
            List<Long> itemIds = QuarkusTransaction.requiringNew().call(() -> items.findQueuedByJob(jobId)
                    .stream().map(item -> item.id).toList());
            for (Long itemId : itemIds) {
                DownloadInput input = prepareItem(jobId, itemId);
                if (input == null) continue;
                PaperPdfImportService.ImportedPdf imported = null;
                try {
                    imported = pdfs.importPdfRespectfully(input.sourceUrl(), input.paperTitle());
                    boolean attached = attach(jobId, itemId, input, imported);
                    importedAny |= attached;
                    if (attached) syncDrive(jobId, input.paperId());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    failItem(jobId, itemId, "PDF retrieval was interrupted");
                    throw error;
                } catch (Exception error) {
                    if (imported != null) deleteQuietly(imported.storedPdf().storedPath());
                    failItem(jobId, itemId, rootMessage(error));
                }
            }
            finalizeJob(jobId, importedAny);
        } catch (Exception error) {
            LOG.errorf(error, "arXiv PDF retrieval job %d failed", jobId);
            failJob(jobId, rootMessage(error));
        } finally {
            activeJobs.remove(jobId);
        }
    }

    private void setRunning(Long jobId) {
        QuarkusTransaction.requiringNew().run(() -> {
            ArxivPdfRetrievalJob job = jobs.findById(jobId);
            if (job == null) throw new NotFoundException();
            job.status = "RUNNING";
            job.phase = "Retrieving arXiv PDFs";
            job.finishedAt = null;
        });
    }

    private DownloadInput prepareItem(Long jobId, Long itemId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ArxivPdfRetrievalJob job = jobs.findById(jobId);
            ArxivPdfRetrievalItem item = items.findById(itemId);
            if (job == null || item == null || !Set.of("QUEUED", "DOWNLOADING").contains(item.status)) return null;
            Paper paper = papers.findForReader(item.paperId).orElse(null);
            if (paper == null || !job.logicalFeedId.equals(paper.logicalFeed.id)
                    || !stateMatches(paper.status, job.stateId) || hasPdf(paper)) {
                completeItem(job, item, "SKIPPED", null);
                return null;
            }
            String currentUrl = pdfs.supportedPdfUrl(paper).orElse(null);
            if (currentUrl == null) {
                completeItem(job, item, "SKIPPED", null);
                return null;
            }
            item.status = "DOWNLOADING";
            item.sourceUrl = currentUrl;
            job.currentPaperTitle = paper.title;
            job.phase = "Retrieving " + paper.title;
            return new DownloadInput(paper.id, paper.title, currentUrl);
        });
    }

    private boolean attach(Long jobId, Long itemId, DownloadInput input,
            PaperPdfImportService.ImportedPdf imported) {
        boolean attached = QuarkusTransaction.requiringNew().call(() -> {
            ArxivPdfRetrievalJob job = jobs.findById(jobId);
            ArxivPdfRetrievalItem item = items.findById(itemId);
            Paper paper = papers.findForReaderForUpdate(input.paperId()).orElse(null);
            if (job == null || item == null || paper == null || !job.logicalFeedId.equals(paper.logicalFeed.id)
                    || !stateMatches(paper.status, job.stateId) || hasPdf(paper)) {
                if (job != null && item != null) completeItem(job, item, "SKIPPED", null);
                return false;
            }
            paper.uploadedPdfPath = imported.storedPdf().storedPath();
            paper.uploadedPdfFileName = imported.storedPdf().originalFileName();
            events.log(paper, "PDF_UPLOADED", "Imported PDF from " + imported.sourceUrl());
            completeItem(job, item, "IMPORTED", null);
            return true;
        });
        if (!attached) deleteQuietly(imported.storedPdf().storedPath());
        return attached;
    }

    private void syncDrive(Long jobId, Long paperId) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                ArxivPdfRetrievalJob job = jobs.findById(jobId);
                AppUser user = job == null ? null : users.findById(job.userId);
                Paper paper = papers.findForReader(paperId).orElse(null);
                if (user != null && paper != null) driveSync.syncPaperForUser(user, paper);
            });
        } catch (RuntimeException error) {
            LOG.warnf(error, "Google Drive sync failed after arXiv PDF retrieval for paper %d", paperId);
        }
    }

    private void failItem(Long jobId, Long itemId, String error) {
        QuarkusTransaction.requiringNew().run(() -> {
            ArxivPdfRetrievalJob job = jobs.findById(jobId);
            ArxivPdfRetrievalItem item = items.findById(itemId);
            if (job != null && item != null) completeItem(job, item, "FAILED", truncate(error, 2000));
        });
    }

    private void completeItem(ArxivPdfRetrievalJob job, ArxivPdfRetrievalItem item, String status, String error) {
        item.status = status;
        item.error = error;
        refreshCounts(job);
    }

    private void refreshCounts(ArxivPdfRetrievalJob job) {
        int imported = countItems(job.id, "IMPORTED");
        int skipped = countItems(job.id, "SKIPPED");
        int failed = countItems(job.id, "FAILED");
        job.importedItems = imported;
        job.skippedItems = skipped;
        job.failedItems = failed;
        job.completedItems = imported + skipped + failed;
    }

    private int countItems(Long jobId, String status) {
        return Math.toIntExact(items.count("jobId = ?1 and status = ?2", jobId, status));
    }

    private void finalizeJob(Long jobId, boolean importedAny) {
        QuarkusTransaction.requiringNew().run(() -> {
            ArxivPdfRetrievalJob job = jobs.findById(jobId);
            if (job != null) {
                job.status = "FINALIZING";
                job.phase = "Refreshing paper feed exports";
                job.currentPaperTitle = null;
            }
        });
        if (importedAny) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    LogicalFeed feed = feeds.findById(jobFeedId(jobId));
                    if (feed != null) gitSync.syncLogicalFeed(feed);
                });
            } catch (RuntimeException error) {
                LOG.warnf(error, "Git sync failed after arXiv PDF retrieval job %d", jobId);
            }
        }
        QuarkusTransaction.requiringNew().run(() -> {
            ArxivPdfRetrievalJob job = jobs.findById(jobId);
            if (job == null) return;
            refreshCounts(job);
            job.status = count(job.failedItems) > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
            job.phase = count(job.failedItems) > 0
                    ? "PDF retrieval completed with errors" : "PDF retrieval complete";
            job.currentPaperTitle = null;
            job.finishedAt = Instant.now();
            job.error = null;
        });
    }

    private Long jobFeedId(Long jobId) {
        ArxivPdfRetrievalJob job = jobs.findById(jobId);
        return job == null ? null : job.logicalFeedId;
    }

    private void failJob(Long jobId, String error) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                ArxivPdfRetrievalJob job = jobs.findById(jobId);
                if (job == null) return;
                refreshCounts(job);
                job.status = "FAILED";
                job.phase = "PDF retrieval failed";
                job.currentPaperTitle = null;
                job.error = truncate(error, 2000);
                job.finishedAt = Instant.now();
            });
        } catch (RuntimeException persistenceError) {
            LOG.warnf(persistenceError, "Could not mark arXiv PDF retrieval job %d failed", jobId);
        }
    }

    private ArxivPdfRetrievalJob requireJob(Long feedId, Long jobId) {
        ArxivPdfRetrievalJob job = jobs.findById(jobId);
        if (job == null || !feedId.equals(job.logicalFeedId)) throw new NotFoundException();
        return job;
    }

    private LogicalFeed lockFeed(Long feedId) {
        LogicalFeed feed = feeds.find("id", feedId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (feed == null) throw new NotFoundException();
        return feed;
    }

    private List<Paper> statePapers(LogicalFeed feed, String state) {
        return state.contains("/")
                ? papers.findByLogicalFeedAndStatus(feed, state)
                : papers.findForTabExport(feed, feed.workflowStateList(), state);
    }

    static boolean stateMatches(String paperState, String selectedState) {
        String paper = WorkflowStateConfig.normalizeStateId(paperState);
        String selected = WorkflowStateConfig.normalizeStateId(selectedState);
        return paper != null && selected != null
                && (paper.equals(selected) || (!selected.contains("/") && paper.startsWith(selected + "/")));
    }

    private StateSelection requireState(LogicalFeed feed, String rawState) {
        String state = WorkflowStateConfig.normalizeStateId(rawState);
        WorkflowStateConfig workflow = feed.workflowConfig();
        WorkflowStateConfig.State leaf = workflow.state(state);
        if (leaf != null) return new StateSelection(leaf.id(), leaf.label());
        boolean parent = workflow.groups().stream().anyMatch(group -> group.name().equals(state));
        if (!parent) throw new BadRequestException("State does not belong to the selected paper feed workflow");
        return new StateSelection(state, humanize(state));
    }

    private Map<String, Object> jobView(ArxivPdfRetrievalJob job) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.id);
        result.put("logicalFeedId", job.logicalFeedId);
        result.put("feedName", job.feedName);
        result.put("state", job.stateId);
        result.put("stateLabel", job.stateLabel);
        result.put("status", job.status);
        result.put("phase", job.phase == null ? "Not running" : job.phase);
        result.put("currentPaperTitle", job.currentPaperTitle == null ? "" : job.currentPaperTitle);
        result.put("completed", count(job.completedItems));
        result.put("total", count(job.totalItems));
        result.put("imported", count(job.importedItems));
        result.put("skipped", count(job.skippedItems));
        result.put("failed", count(job.failedItems));
        result.put("running", ArxivPdfRetrievalJobRepository.isActive(job.status));
        result.put("error", job.error == null ? "" : job.error);
        result.put("startedAt", job.startedAt == null ? "" : job.startedAt.toString());
        result.put("finishedAt", job.finishedAt == null ? "" : job.finishedAt.toString());
        result.put("retryOf", job.sourceJobId);
        result.put("failures", items.findFailedByJob(job.id).stream().map(item -> Map.of(
                "paperId", item.paperId,
                "title", item.paperTitle,
                "error", item.error == null ? "PDF retrieval failed" : item.error)).toList());
        return result;
    }

    private Map<String, Object> idleView(Long feedId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", null);
        result.put("logicalFeedId", feedId);
        result.put("status", "IDLE");
        result.put("phase", "Not running");
        result.put("completed", 0);
        result.put("total", 0);
        result.put("imported", 0);
        result.put("skipped", 0);
        result.put("failed", 0);
        result.put("running", false);
        result.put("error", "");
        result.put("failures", List.of());
        return result;
    }

    private void deleteQuietly(String storedPath) {
        try {
            storage.deleteIfExists(storedPath);
        } catch (IOException error) {
            LOG.warnf(error, "Could not clean up unattached arXiv PDF %s", storedPath);
        }
    }

    private static boolean hasPdf(Paper paper) {
        return paper.uploadedPdfPath != null && !paper.uploadedPdfPath.isBlank();
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String value, int maximum) {
        if (value == null) return null;
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private static String humanize(String value) {
        String normalized = value.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? value : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private record StateSelection(String id, String label) {
    }

    private record DownloadInput(Long paperId, String paperTitle, String sourceUrl) {
    }
}
