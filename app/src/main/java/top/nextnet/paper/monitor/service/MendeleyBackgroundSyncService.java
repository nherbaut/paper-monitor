package top.nextnet.paper.monitor.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.MendeleyFeedSync;
import top.nextnet.paper.monitor.repo.MendeleyFeedSyncRepository;

@ApplicationScoped
public class MendeleyBackgroundSyncService {
    private static final Logger LOG = Logger.getLogger(MendeleyBackgroundSyncService.class);
    private static final Set<String> AUTOMATIC_EVENT_TYPES = Set.of(
            "FETCH", "PDF_UPLOADED", "STATE_CHANGED", "STATE_MIGRATED", "STATE_REPAIRED",
            "NOTES_CHANGED", "TAGS_CHANGED");
    private final MendeleyFeedSyncRepository feeds;
    private final MendeleySyncService sync;
    private final ManagedExecutor executor;
    private final Set<Long> active = ConcurrentHashMap.newKeySet();
    private final Set<Long> rerun = ConcurrentHashMap.newKeySet();
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> automaticGenerations = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public MendeleyBackgroundSyncService(MendeleyFeedSyncRepository feeds, MendeleySyncService sync,
            ManagedExecutor executor) {
        this.feeds = feeds;
        this.sync = sync;
        this.executor = executor;
    }

    public Map<String, Object> start(AppUser user, LogicalFeed logicalFeed, boolean refreshPreview, String trigger) {
        Long configId = QuarkusTransaction.requiringNew().call(() -> feeds.findByUserAndFeed(user, logicalFeed)
                .filter(config -> config.enabled)
                .map(config -> config.id)
                .orElseThrow(() -> new BadRequestException(
                        "Configure this paper feed's Mendeley folder first")));
        enqueue(configId, refreshPreview, trigger, true);
        return status(configId);
    }

    public Map<String, Object> status(Long configId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MendeleyFeedSync config = feeds.findById(configId);
            if (config == null) throw new NotFoundException("Unknown Mendeley synchronization configuration");
            return jobView(config);
        });
    }

    public Map<String, Object> status(AppUser user, LogicalFeed logicalFeed) {
        return QuarkusTransaction.requiringNew().call(() -> feeds.findByUserAndFeed(user, logicalFeed)
                .map(MendeleyBackgroundSyncService::jobView)
                .orElseThrow(() -> new BadRequestException(
                        "Configure this paper feed's Mendeley folder first")));
    }

    public void cancel(List<Long> configurationIds) {
        cancelled.addAll(configurationIds);
        rerun.removeAll(configurationIds);
    }

    void paperChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) PaperChangedEvent event) {
        if (event.logicalFeedId() == null || !AUTOMATIC_EVENT_TYPES.contains(event.type())) return;
        long token = generation.incrementAndGet();
        automaticGenerations.put(event.logicalFeedId(), token);
        CompletableFuture.runAsync(() -> {
            if (automaticGenerations.remove(event.logicalFeedId(), token)) {
                enqueueLogicalFeed(event.logicalFeedId());
            }
        }, CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS, executor));
    }

    @Scheduled(every = "{paper-monitor.mendeley.sync-every:5m}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pollMendeleyChanges() {
        for (Long configId : enabledConfigIds()) enqueue(configId, true, "scheduled", true);
    }

    void resumeInterruptedJobs(@Observes StartupEvent ignored) {
        executor.execute(() -> {
            try {
                List<Long> interrupted = QuarkusTransaction.requiringNew().call(() -> feeds
                        .find("enabled = true and syncStatus in ?1", List.of("QUEUED", "DISCOVERING", "RUNNING"))
                        .list().stream().map(config -> config.id).toList());
                interrupted.forEach(configId -> enqueue(configId, true, "restart", false));
            } catch (RuntimeException error) {
                LOG.error("Could not resume interrupted Mendeley jobs; application startup will continue", error);
            }
        });
    }

    private void enqueueLogicalFeed(Long logicalFeedId) {
        List<Long> ids = QuarkusTransaction.requiringNew().call(() -> feeds
                .findEnabledByLogicalFeedId(logicalFeedId).stream().map(config -> config.id).toList());
        ids.forEach(configId -> enqueue(configId, true, "interface-change", true));
    }

    private List<Long> enabledConfigIds() {
        return QuarkusTransaction.requiringNew().call(() -> feeds.findEnabled().stream()
                .map(config -> config.id).toList());
    }

    private void enqueue(Long configId, boolean refreshPreview, String trigger, boolean requestRerun) {
        if (cancelled.contains(configId)) return;
        if (!active.add(configId)) {
            if (requestRerun) rerun.add(configId);
            return;
        }
        updateJob(configId, "QUEUED", "Waiting for background worker", trigger, 0, 0, null, false);
        executor.execute(() -> run(configId, refreshPreview, trigger));
    }

    private void run(Long configId, boolean refreshPreview, String trigger) {
        try {
            LOG.infof("Starting background Mendeley synchronization %s (trigger=%s, refreshPreview=%s)",
                    configId, trigger, refreshPreview);
            ensureNotCancelled(configId);
            SyncTarget target = target(configId);
            updateJob(configId, "DISCOVERING", "Comparing Paper Monitor and Mendeley", trigger,
                    0, 0, null, false);
            Map<String, Object> preview = refreshPreview
                    ? sync.preview(target.user(), target.logicalFeed())
                    : null;
            ensureNotCancelled(configId);
            int total = preview == null ? 0 : actionCount(preview);
            updateJob(configId, "RUNNING", "Synchronizing papers", trigger, 0, total, null, false);
            sync.apply(target.user(), target.logicalFeed(), (completed, actionTotal) -> {
                ensureNotCancelled(configId);
                updateJob(configId, "RUNNING", "Synchronizing papers", trigger,
                        completed, actionTotal, null, false);
            });
            MendeleyFeedSync completed = QuarkusTransaction.requiringNew().call(() -> {
                MendeleyFeedSync config = feeds.findById(configId);
                if (config != null) {
                    config.syncStatus = "COMPLETED";
                    config.syncPhase = "Synchronization complete";
                    config.syncCompletedActions = Math.max(count(config.syncCompletedActions),
                            count(config.syncTotalActions));
                    config.syncFinishedAt = Instant.now();
                    config.lastError = null;
                }
                return config;
            });
            if (completed != null) LOG.infof("Background Mendeley synchronization %s completed", configId);
        } catch (CancellationException cancelledJob) {
            LOG.infof("Background Mendeley synchronization %s cancelled after user disconnect", configId);
        } catch (Exception error) {
            LOG.errorf(error, "Background Mendeley synchronization %s failed", configId);
            updateJob(configId, "FAILED", "Synchronization failed", trigger, null, null,
                    rootMessage(error), true);
        } finally {
            active.remove(configId);
            boolean shouldRerun = rerun.remove(configId);
            if (!cancelled.remove(configId) && shouldRerun) {
                enqueue(configId, true, "interface-change", false);
            }
        }
    }

    private void ensureNotCancelled(Long configId) {
        if (cancelled.contains(configId)) throw new CancellationException();
    }

    private SyncTarget target(Long configId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MendeleyFeedSync config = feeds.findById(configId);
            if (config == null || !config.enabled) throw new BadRequestException(
                    "Mendeley synchronization configuration is unavailable");
            return new SyncTarget(config.user, config.logicalFeed);
        });
    }

    private void updateJob(Long configId, String status, String phase, String trigger,
            Integer completed, Integer total, String error, boolean finished) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                MendeleyFeedSync config = feeds.findById(configId);
                if (config == null) return;
                config.syncStatus = status;
                config.syncPhase = phase;
                config.syncTrigger = trigger;
                if (completed != null) config.syncCompletedActions = completed;
                if (total != null) config.syncTotalActions = total;
                if ("QUEUED".equals(status)) {
                    config.syncStartedAt = Instant.now();
                    config.syncFinishedAt = null;
                }
                if (error != null) config.lastError = truncate(error, 2000);
                if (finished) config.syncFinishedAt = Instant.now();
            });
        } catch (RuntimeException persistenceError) {
            LOG.warnf(persistenceError, "Could not update Mendeley synchronization job %s", configId);
        }
    }

    static Map<String, Object> jobView(MendeleyFeedSync config) {
        String status = config.syncStatus == null ? "IDLE" : config.syncStatus;
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("configId", config.id);
        result.put("status", status);
        result.put("phase", config.syncPhase == null ? "Not running" : config.syncPhase);
        result.put("trigger", config.syncTrigger == null ? "" : config.syncTrigger);
        result.put("completed", count(config.syncCompletedActions));
        result.put("total", count(config.syncTotalActions));
        result.put("running", Set.of("QUEUED", "DISCOVERING", "RUNNING").contains(status));
        result.put("startedAt", config.syncStartedAt == null ? "" : config.syncStartedAt.toString());
        result.put("finishedAt", config.syncFinishedAt == null ? "" : config.syncFinishedAt.toString());
        result.put("error", config.lastError == null ? "" : config.lastError);
        return result;
    }

    private static int actionCount(Map<String, Object> preview) {
        Object actions = preview.get("actions");
        return actions instanceof List<?> list ? list.size() : 0;
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }

    private static String rootMessage(Throwable error) {
        Throwable result = error;
        while (result.getCause() != null && result.getCause() != result) result = result.getCause();
        String message = result.getMessage();
        return message == null || message.isBlank() ? result.getClass().getSimpleName() : message;
    }

    private record SyncTarget(AppUser user, LogicalFeed logicalFeed) {}
}
