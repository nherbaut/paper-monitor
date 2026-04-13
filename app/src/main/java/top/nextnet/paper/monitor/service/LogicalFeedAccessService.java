package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.LogicalFeedAccessGrant;
import top.nextnet.paper.monitor.repo.FeedRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedAccessGrantRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;

@ApplicationScoped
public class LogicalFeedAccessService {

    private final LogicalFeedRepository logicalFeedRepository;
    private final LogicalFeedAccessGrantRepository logicalFeedAccessGrantRepository;
    private final FeedRepository feedRepository;

    public LogicalFeedAccessService(
            LogicalFeedRepository logicalFeedRepository,
            LogicalFeedAccessGrantRepository logicalFeedAccessGrantRepository,
            FeedRepository feedRepository
    ) {
        this.logicalFeedRepository = logicalFeedRepository;
        this.logicalFeedAccessGrantRepository = logicalFeedAccessGrantRepository;
        this.feedRepository = feedRepository;
    }

    public List<LogicalFeed> readableLogicalFeeds(AppUser user) {
        if (user == null) {
            return logicalFeedRepository.find("publicReadable = true order by name").list();
        }
        if (user.admin) {
            return logicalFeedRepository.findAll().list();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (LogicalFeed feed : logicalFeedRepository.find("owner", user).list()) {
            ids.add(feed.id);
        }
        for (LogicalFeedAccessGrant grant : logicalFeedAccessGrantRepository.findByUser(user)) {
            if (grant.canRead()) {
                ids.add(grant.logicalFeed.id);
            }
        }
        return ids.isEmpty()
                ? List.of()
                : logicalFeedRepository.find("id in ?1 order by name", ids).list();
    }

    public List<Feed> readableFeeds(AppUser user) {
        List<LogicalFeed> logicalFeeds = readableLogicalFeeds(user);
        if (logicalFeeds.isEmpty()) {
            return List.of();
        }
        return feedRepository.find("logicalFeed in ?1 order by name", logicalFeeds).list();
    }

    public LogicalFeed requireReadableLogicalFeed(Long id, AppUser user) {
        LogicalFeed logicalFeed = logicalFeedRepository.findById(id);
        if (logicalFeed == null) {
            throw new NotFoundException();
        }
        if (!canRead(logicalFeed, user)) {
            throw new ForbiddenException();
        }
        return logicalFeed;
    }

    public LogicalFeed requireAdminLogicalFeed(Long id, AppUser user) {
        LogicalFeed logicalFeed = logicalFeedRepository.findById(id);
        if (logicalFeed == null) {
            throw new NotFoundException();
        }
        if (!canAdmin(logicalFeed, user)) {
            throw new ForbiddenException();
        }
        return logicalFeed;
    }

    public Feed requireAdminFeed(Long id, AppUser user) {
        Feed feed = feedRepository.findById(id);
        if (feed == null) {
            throw new NotFoundException();
        }
        if (!canAdmin(feed.logicalFeed, user)) {
            throw new ForbiddenException();
        }
        return feed;
    }

    public boolean canRead(LogicalFeed logicalFeed, AppUser user) {
        if (logicalFeed == null || user == null) {
            return logicalFeed != null && logicalFeed.publicReadable;
        }
        if (user.admin || logicalFeed.isOwnedBy(user)) {
            return true;
        }
        return logicalFeedAccessGrantRepository.findByLogicalFeedAndUser(logicalFeed, user)
                .map(LogicalFeedAccessGrant::canRead)
                .orElse(false);
    }

    public boolean canAdmin(LogicalFeed logicalFeed, AppUser user) {
        if (logicalFeed == null || user == null) {
            return false;
        }
        if (user.admin || logicalFeed.isOwnedBy(user)) {
            return true;
        }
        return logicalFeedAccessGrantRepository.findByLogicalFeedAndUser(logicalFeed, user)
                .map(LogicalFeedAccessGrant::canAdmin)
                .orElse(false);
    }

    @Transactional
    public LogicalFeedAccessGrant grant(LogicalFeed logicalFeed, AppUser target, String role) {
        String normalizedRole = normalizeRole(role);
        LogicalFeedAccessGrant grant = logicalFeedAccessGrantRepository.findByLogicalFeedAndUser(logicalFeed, target)
                .orElseGet(LogicalFeedAccessGrant::new);
        grant.logicalFeed = logicalFeed;
        grant.user = target;
        grant.role = normalizedRole;
        if (grant.id == null) {
            logicalFeedAccessGrantRepository.persist(grant);
        }
        return grant;
    }

    @Transactional
    public void revoke(LogicalFeed logicalFeed, AppUser target) {
        logicalFeedAccessGrantRepository.findByLogicalFeedAndUser(logicalFeed, target)
                .ifPresent(LogicalFeedAccessGrant::delete);
    }

    public String normalizeRole(String role) {
        if (role == null) {
            throw new IllegalArgumentException("Access role is required");
        }
        String normalized = role.trim().toUpperCase();
        if (!"READ".equals(normalized) && !"ADMIN".equals(normalized)) {
            throw new IllegalArgumentException("Access role must be READ or ADMIN");
        }
        return normalized;
    }
}
