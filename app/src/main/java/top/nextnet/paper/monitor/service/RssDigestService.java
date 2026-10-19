package top.nextnet.paper.monitor.service;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;
import top.nextnet.paper.monitor.repo.UserSettingsRepository;

@ApplicationScoped
public class RssDigestService {

    static final ZoneId DIGEST_ZONE = ZoneId.of("Europe/Paris");

    private final LogicalFeedRepository logicalFeedRepository;
    private final PaperRepository paperRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final NotificationService notificationService;

    @Inject
    RssDigestService self;

    public RssDigestService(
            LogicalFeedRepository logicalFeedRepository,
            PaperRepository paperRepository,
            UserSettingsRepository userSettingsRepository,
            NotificationService notificationService
    ) {
        this.logicalFeedRepository = logicalFeedRepository;
        this.paperRepository = paperRepository;
        this.userSettingsRepository = userSettingsRepository;
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
        var digestDate = ZonedDateTime.ofInstant(cutoff, DIGEST_ZONE).toLocalDate();
        List<FeedUpdate> updates = self.collectDailyUpdates(cutoff);
        deliverDailyUpdates(updates, digestDate);
    }

    void deliverDailyUpdates(List<FeedUpdate> updates, LocalDate digestDate) {
        Map<String, RecipientDigest> combinedByRecipient = new LinkedHashMap<>();

        for (FeedUpdate update : updates) {
            NotificationService.FeedDigest feedDigest = new NotificationService.FeedDigest(
                    update.logicalFeed(), update.papers());
            for (DigestRecipient recipient : update.recipients()) {
                if (UserSettings.PAPER_DIGEST_PER_FEED.equals(recipient.mode())) {
                    notificationService.sendPaperFeedDigest(
                            recipient.email(), update.logicalFeed(), update.papers(), digestDate);
                    continue;
                }
                combinedByRecipient.computeIfAbsent(
                                recipient.emailKey(), ignored -> new RecipientDigest(recipient.email(), new ArrayList<>()))
                        .feeds().add(feedDigest);
            }
        }

        combinedByRecipient.values().forEach(digest ->
                notificationService.sendCombinedPaperDigest(digest.email(), digest.feeds(), digestDate));
    }

    @Transactional
    public List<FeedUpdate> collectDailyUpdates(Instant cutoff) {
        List<FeedUpdate> updates = new ArrayList<>();
        Map<String, String> digestModesByUser = new LinkedHashMap<>();
        for (Long logicalFeedId : logicalFeedRepository.findActiveIds()) {
            LogicalFeed logicalFeed = logicalFeedRepository.findById(logicalFeedId);
            if (logicalFeed == null) {
                continue;
            }
            if (logicalFeed.lastRssDigestSentAt == null) {
                logicalFeed.lastRssDigestSentAt = cutoff;
                continue;
            }
            if (!logicalFeed.notifyOnNewRssPapers) {
                logicalFeed.lastRssDigestSentAt = cutoff;
                continue;
            }

            List<Paper> papers = paperRepository.findNewPapersForDigest(
                    logicalFeed, logicalFeed.lastRssDigestSentAt, cutoff);
            if (!papers.isEmpty()) {
                List<DigestRecipient> recipients = digestRecipients(logicalFeed, digestModesByUser);
                if (!recipients.isEmpty()) {
                    updates.add(new FeedUpdate(logicalFeed, papers, recipients));
                }
            }
            logicalFeed.lastRssDigestSentAt = cutoff;
        }
        updates.sort(Comparator.comparing(update -> update.logicalFeed().name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(updates);
    }

    private List<DigestRecipient> digestRecipients(LogicalFeed logicalFeed, Map<String, String> digestModesByUser) {
        Map<String, AppUser> users = new LinkedHashMap<>();
        addRecipient(users, logicalFeed.owner);
        for (var grant : logicalFeed.accessGrants) {
            if (grant != null && grant.canAdmin()) {
                addRecipient(users, grant.user);
            }
        }
        return users.values().stream()
                .map(user -> new DigestRecipient(
                        user.email,
                        user.email.trim().toLowerCase(Locale.ROOT),
                        digestModesByUser.computeIfAbsent(userKey(user), ignored ->
                                userSettingsRepository.findByUser(user)
                                        .map(UserSettings::effectivePaperDigestMode)
                                        .orElse(UserSettings.PAPER_DIGEST_DAILY))))
                .toList();
    }

    private void addRecipient(Map<String, AppUser> users, AppUser user) {
        if (user == null || user.email == null || user.email.isBlank()) {
            return;
        }
        users.putIfAbsent(userKey(user), user);
    }

    private String userKey(AppUser user) {
        return user.id == null
                ? "email:" + user.email.trim().toLowerCase(Locale.ROOT)
                : "id:" + user.id;
    }

    record FeedUpdate(LogicalFeed logicalFeed, List<Paper> papers, List<DigestRecipient> recipients) {
        FeedUpdate {
            papers = List.copyOf(papers);
            recipients = List.copyOf(recipients);
        }
    }

    record DigestRecipient(String email, String emailKey, String mode) {
    }

    private record RecipientDigest(String email, List<NotificationService.FeedDigest> feeds) {
    }
}
