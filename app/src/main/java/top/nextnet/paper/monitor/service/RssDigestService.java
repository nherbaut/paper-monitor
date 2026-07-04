package top.nextnet.paper.monitor.service;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;

@ApplicationScoped
public class RssDigestService {

    static final ZoneId DIGEST_ZONE = ZoneId.of("Europe/Paris");

    private final LogicalFeedRepository logicalFeedRepository;
    private final PaperRepository paperRepository;
    private final NotificationService notificationService;

    @Inject
    RssDigestService self;

    public RssDigestService(
            LogicalFeedRepository logicalFeedRepository,
            PaperRepository paperRepository,
            NotificationService notificationService
    ) {
        this.logicalFeedRepository = logicalFeedRepository;
        this.paperRepository = paperRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    void initializeDigestCheckpoints(@Observes StartupEvent ignored) {
        Instant now = Instant.now();
        logicalFeedRepository.find("lastRssDigestSentAt is null").list()
                .forEach(logicalFeed -> logicalFeed.lastRssDigestSentAt = now);
    }

    @Scheduled(
            cron = "{paper-monitor.rss-digest.cron:0 0 8 * * ?}",
            timeZone = "{paper-monitor.rss-digest.time-zone:Europe/Paris}"
    )
    void sendDailyDigests() {
        Instant cutoff = Instant.now();
        List<Long> logicalFeedIds = logicalFeedRepository.findActiveIds();
        for (Long logicalFeedId : logicalFeedIds) {
            self.sendDigest(logicalFeedId, cutoff);
        }
    }

    @Transactional
    public void sendDigest(Long logicalFeedId, Instant cutoff) {
        LogicalFeed logicalFeed = logicalFeedRepository.findById(logicalFeedId);
        if (logicalFeed == null) {
            return;
        }
        if (logicalFeed.lastRssDigestSentAt == null) {
            logicalFeed.lastRssDigestSentAt = cutoff;
            return;
        }
        if (!logicalFeed.notifyOnNewRssPapers) {
            logicalFeed.lastRssDigestSentAt = cutoff;
            return;
        }

        List<Paper> papers = paperRepository.findRssImportsForDigest(
                logicalFeed, logicalFeed.lastRssDigestSentAt, cutoff);
        if (!papers.isEmpty()) {
            notificationService.sendRssPaperDigest(
                    logicalFeed, papers, ZonedDateTime.ofInstant(cutoff, DIGEST_ZONE).toLocalDate());
        }
        logicalFeed.lastRssDigestSentAt = cutoff;
    }
}
