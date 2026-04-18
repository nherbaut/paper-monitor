package top.nextnet.paper.monitor.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import top.nextnet.paper.monitor.repo.AppUserRepository;
import top.nextnet.paper.monitor.repo.FeedRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;

@ApplicationScoped
public class ApplicationMetricsService {

    private final MeterRegistry meterRegistry;
    private final AppUserRepository appUserRepository;
    private final LogicalFeedRepository logicalFeedRepository;
    private final FeedRepository feedRepository;
    private final PaperRepository paperRepository;

    public ApplicationMetricsService(
            MeterRegistry meterRegistry,
            AppUserRepository appUserRepository,
            LogicalFeedRepository logicalFeedRepository,
            FeedRepository feedRepository,
            PaperRepository paperRepository
    ) {
        this.meterRegistry = meterRegistry;
        this.appUserRepository = appUserRepository;
        this.logicalFeedRepository = logicalFeedRepository;
        this.feedRepository = feedRepository;
        this.paperRepository = paperRepository;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("paper_monitor_users_total", this, (service) -> service.userCount())
                .description("Total number of users")
                .register(meterRegistry);

        Gauge.builder("paper_monitor_paper_feeds_total", this, (service) -> service.paperFeedCount())
                .description("Total number of paper feeds")
                .register(meterRegistry);

        Gauge.builder("paper_monitor_rss_feeds_total", this, (service) -> service.rssFeedCount())
                .description("Total number of RSS feeds")
                .register(meterRegistry);

        Gauge.builder("paper_monitor_papers_total", this, (service) -> service.paperCount())
                .description("Total number of papers")
                .register(meterRegistry);

        Gauge.builder("paper_monitor_papers_new_last_7_days", this, (service) -> service.newPapersLast7Days())
                .description("Number of papers discovered in the last 7 days")
                .register(meterRegistry);
    }

    double userCount() {
        return appUserRepository.count();
    }

    double paperFeedCount() {
        return logicalFeedRepository.count();
    }

    double rssFeedCount() {
        return feedRepository.count();
    }

    double paperCount() {
        return paperRepository.count();
    }

    double newPapersLast7Days() {
        return paperRepository.countDiscoveredSince(Instant.now().minus(7, ChronoUnit.DAYS));
    }
}
