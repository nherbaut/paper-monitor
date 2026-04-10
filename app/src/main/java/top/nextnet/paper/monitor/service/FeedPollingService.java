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
        if (feed != null && isPollable(feed)) {
            pollFeed(feed, Instant.now());
        }
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
            var body = feedFetcher.fetch(feed.url);
            var items = rssParser.parse(body);
            for (var item : items) {
                if (item.link() == null || paperRepository.findBySourceLink(item.link()).isPresent()) {
                    continue;
                }
                Paper paper = new Paper();
                paper.title = item.title();
                paper.sourceLink = item.link();
                paper.openAccessLink = item.openAccessLink();
                paper.summary = item.summary();
                paper.authors = item.authors();
                paper.publisher = item.publisher();
                paper.publishedOn = item.publishedOn();
                paper.status = feed.initialPaperStatus();
                paper.discoveredAt = now;
                paper.feed = feed;
                paper.logicalFeed = feed.logicalFeed;
                paperRepository.persist(paper);
                paperEventService.log(paper, "FETCH", "Fetched from " + feed.name);
            }
            feed.lastPolledAt = now;
            feed.lastError = null;
        } catch (Exception e) {
            feed.lastPolledAt = now;
            feed.lastError = e.getMessage();
            Log.errorf(e, "Failed to poll feed %s", feed.url);
        }
    }
}
