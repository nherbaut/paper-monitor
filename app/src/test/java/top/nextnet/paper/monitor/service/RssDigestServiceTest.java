package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.UserSettings;

class RssDigestServiceTest {

    @Test
    void usesEuropeParisForDigestDates() {
        assertEquals(ZoneId.of("Europe/Paris"), RssDigestService.DIGEST_ZONE);
    }

    @Test
    void routesCombinedAndPerFeedPreferencesPerRecipient() {
        RecordingNotificationService notifications = new RecordingNotificationService();
        RssDigestService service = new RssDigestService(null, null, null, notifications);
        RssDigestService.DigestRecipient combined = new RssDigestService.DigestRecipient(
                "combined@example.org", "combined@example.org", UserSettings.PAPER_DIGEST_DAILY);
        RssDigestService.DigestRecipient separate = new RssDigestService.DigestRecipient(
                "separate@example.org", "separate@example.org", UserSettings.PAPER_DIGEST_PER_FEED);
        LogicalFeed firstFeed = logicalFeed(1L, "First feed");
        LogicalFeed secondFeed = logicalFeed(2L, "Second feed");

        service.deliverDailyUpdates(List.of(
                new RssDigestService.FeedUpdate(firstFeed, List.of(paper("First paper")), List.of(combined, separate)),
                new RssDigestService.FeedUpdate(secondFeed, List.of(paper("Second paper")), List.of(combined, separate))),
                LocalDate.of(2026, 10, 19));

        assertEquals(List.of("separate@example.org:First feed", "separate@example.org:Second feed"),
                notifications.separateDeliveries);
        assertEquals(List.of("combined@example.org:2"), notifications.combinedDeliveries);
    }

    private LogicalFeed logicalFeed(Long id, String name) {
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.id = id;
        logicalFeed.name = name;
        return logicalFeed;
    }

    private Paper paper(String title) {
        Paper paper = new Paper();
        paper.title = title;
        paper.status = "NEW";
        paper.sourceLink = "https://example.org/" + title.replace(' ', '-');
        paper.feed = new Feed();
        paper.feed.name = "Source";
        return paper;
    }

    private static final class RecordingNotificationService extends NotificationService {
        private final List<String> separateDeliveries = new ArrayList<>();
        private final List<String> combinedDeliveries = new ArrayList<>();

        private RecordingNotificationService() {
            super(null, "no-reply@example.org", "https://papers.example.org");
        }

        @Override
        public void sendPaperFeedDigest(
                String recipient,
                LogicalFeed logicalFeed,
                List<Paper> papers,
                LocalDate digestDate
        ) {
            separateDeliveries.add(recipient + ":" + logicalFeed.name);
        }

        @Override
        public void sendCombinedPaperDigest(
                String recipient,
                List<FeedDigest> feeds,
                LocalDate digestDate
        ) {
            combinedDeliveries.add(recipient + ":" + feeds.size());
        }
    }
}
