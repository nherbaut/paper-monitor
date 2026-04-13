package top.nextnet.paper.monitor.service;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.repo.FeedRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;
import top.nextnet.paper.monitor.rss.RssParser;

@ApplicationScoped
public class FeedPollingService {

    private static final int TITLE_MAX = 1000;
    private static final int LINK_MAX = 1000;
    private static final int SUMMARY_MAX = 4000;
    private static final int AUTHORS_MAX = 1000;
    private static final int PUBLISHER_MAX = 255;
    private static final int STATUS_MAX = 64;

    private final FeedRepository feedRepository;
    private final PaperRepository paperRepository;
    private final FeedFetcher feedFetcher;
    private final RssParser rssParser;
    private final PaperEventService paperEventService;

    public FeedPollingService(
            FeedRepository feedRepository,
            PaperRepository paperRepository,
            FeedFetcher feedFetcher,
            RssParser rssParser,
            PaperEventService paperEventService
    ) {
        this.feedRepository = feedRepository;
        this.paperRepository = paperRepository;
        this.feedFetcher = feedFetcher;
        this.rssParser = rssParser;
        this.paperEventService = paperEventService;
    }

    @Scheduled(every = "{paper-monitor.poller.every:60s}")
    @Transactional
    void pollDueFeeds() {
        Instant now = Instant.now();
        List<Feed> dueFeeds = feedRepository.findAll().list();
        for (Feed feed : dueFeeds) {
            if (!isDue(feed, now)) {
                continue;
            }
            pollFeed(feed, now);
        }
    }

    @Transactional
    public void pollFeedById(Long feedId) {
        Feed feed = feedRepository.findById(feedId);
        if (feed == null) {
            Log.warnf("Manual feed poll requested for unknown feed id=%d", feedId);
            return;
        }
        if (!isPollable(feed)) {
            Log.warnf("Manual feed poll skipped for non-pollable feed id=%d url=%s", feed.id, feed.url);
            return;
        }
        pollFeed(feed, Instant.now());
    }

    private boolean isDue(Feed feed, Instant now) {
        if (!isPollable(feed)) {
            return false;
        }
        if (feed.lastPolledAt == null) {
            return true;
        }
        return feed.lastPolledAt.plusSeconds(feed.pollIntervalMinutes.longValue() * 60).isBefore(now)
                || feed.lastPolledAt.plusSeconds(feed.pollIntervalMinutes.longValue() * 60).equals(now);
    }

    private boolean isPollable(Feed feed) {
        if (feed == null || feed.url == null) {
            return false;
        }
        return feed.url.startsWith("http://") || feed.url.startsWith("https://");
    }

    private void pollFeed(Feed feed, Instant now) {
        try {
            Log.infof("Polling feed id=%d name=%s url=%s", feed.id, feed.name, feed.url);
            var body = feedFetcher.fetch(feed.url);
            var items = rssParser.parse(body);
            Log.infof("Parsed %d RSS items for feed id=%d url=%s", items.size(), feed.id, feed.url);
            int createdCount = 0;
            int skippedDuplicateCount = 0;
            int skippedMissingLinkCount = 0;
            for (var item : items) {
                if (item.link() == null) {
                    skippedMissingLinkCount++;
                    Log.warnf("Skipping RSS item without link for feed id=%d title=%s", feed.id, item.title());
                    continue;
                }
                if (paperRepository.findBySourceLink(item.link()).isPresent()) {
                    skippedDuplicateCount++;
                    continue;
                }
                Paper paper = new Paper();
                paper.title = truncate(item.title(), TITLE_MAX);
                paper.sourceLink = truncate(item.link(), LINK_MAX);
                paper.openAccessLink = truncate(item.openAccessLink(), LINK_MAX);
                paper.summary = truncate(item.summary(), SUMMARY_MAX);
                paper.authors = truncate(item.authors(), AUTHORS_MAX);
                paper.publisher = truncate(item.publisher(), PUBLISHER_MAX);
                paper.publishedOn = item.publishedOn();
                paper.status = truncate(feed.initialPaperStatus(), STATUS_MAX);
                paper.discoveredAt = now;
                paper.feed = feed;
                paper.logicalFeed = feed.logicalFeed;
                paperRepository.persist(paper);
                paperEventService.log(paper, "FETCH", "Fetched from " + feed.name);
                createdCount++;
            }
            feed.lastPolledAt = now;
            feed.lastError = null;
            Log.infof(
                    "Feed poll completed id=%d created=%d duplicates=%d missingLink=%d",
                    feed.id, createdCount, skippedDuplicateCount, skippedMissingLinkCount);
        } catch (Exception e) {
            feed.lastPolledAt = now;
            feed.lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.errorf(e, "Failed to poll feed id=%d name=%s url=%s", feed.id, feed.name, feed.url);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
