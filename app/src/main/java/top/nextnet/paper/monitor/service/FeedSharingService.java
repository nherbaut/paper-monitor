package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.LogicalFeedInvitation;
import top.nextnet.paper.monitor.repo.AppUserEmailRepository;
import top.nextnet.paper.monitor.repo.AppUserRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedAccessGrantRepository;

@ApplicationScoped
public class FeedSharingService {

    private final AppUserRepository users;
    private final AppUserEmailRepository emails;
    private final LogicalFeedAccessService access;
    private final LogicalFeedAccessGrantRepository grants;
    private final EmailDomainPolicyService policies;
    private final NotificationService notifications;

    public FeedSharingService(AppUserRepository users, AppUserEmailRepository emails,
            LogicalFeedAccessService access, LogicalFeedAccessGrantRepository grants,
            EmailDomainPolicyService policies, NotificationService notifications) {
        this.users = users;
        this.emails = emails;
        this.access = access;
        this.grants = grants;
        this.policies = policies;
        this.notifications = notifications;
    }

    @Transactional
    public void share(Long feedId, AppUser grantedBy, String email, String role) {
        LogicalFeed feed = access.requireAdminLogicalFeed(feedId, grantedBy);
        String recipient = normalizeEmail(email);
        String normalizedRole = access.normalizeRole(role);
        AppUser target = users.findByEmail(recipient).orElse(null);
        if (target != null && target.isActive()) {
            if (feed.isOwnedBy(target)) {
                throw new IllegalArgumentException("The owner already has access to this paper feed");
            }
            access.grant(feed, target, normalizedRole);
            LogicalFeedInvitation.delete("logicalFeed = ?1 and email = ?2", feed, recipient);
            notifications.sendFeedAccessNotification(target, feed, normalizedRole, grantedBy);
            return;
        }
        if (target == null && !policies.resolveForEmail(recipient).canCreateAccounts()) {
            throw new IllegalArgumentException("Invitations are not available for this email domain under the signup policy");
        }
        LogicalFeedInvitation invitation = LogicalFeedInvitation
                .<LogicalFeedInvitation>find("logicalFeed = ?1 and email = ?2", feed, recipient)
                .firstResultOptional().orElseGet(LogicalFeedInvitation::new);
        invitation.logicalFeed = feed;
        invitation.email = recipient;
        invitation.role = normalizedRole;
        invitation.expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        invitation.persist();
        notifications.sendFeedInvitation(recipient, feed, normalizedRole);
    }

    @Transactional
    public void acceptPending(AppUser user) {
        if (user == null || !user.isActive()) {
            return;
        }
        List<String> verifiedEmails = new ArrayList<>();
        if (user.email != null) {
            verifiedEmails.add(user.email.toLowerCase(Locale.ROOT));
        }
        emails.findByUser(user).stream().filter(email -> email.verifiedAt != null)
                .map(email -> email.email.toLowerCase(Locale.ROOT)).forEach(verifiedEmails::add);
        if (verifiedEmails.isEmpty()) {
            return;
        }
        List<LogicalFeedInvitation> invitations = LogicalFeedInvitation.list(
                "email in ?1 and expiresAt > ?2", verifiedEmails, Instant.now());
        for (LogicalFeedInvitation invitation : invitations) {
            var existing = grants.findByLogicalFeedAndUser(invitation.logicalFeed, user);
            if (!invitation.logicalFeed.isOwnedBy(user)
                    && (existing.isEmpty() || !existing.get().canAdmin())) {
                access.grant(invitation.logicalFeed, user, invitation.role);
            }
            invitation.delete();
        }
    }

    public static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Enter a valid recipient email address");
        }
        return normalized;
    }
}
