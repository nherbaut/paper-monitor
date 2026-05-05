package top.nextnet.paper.monitor.service;

import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;

@ApplicationScoped
public class NotificationService {

    private final Mailer mailer;
    private final String fromAddress;

    public NotificationService(
            Mailer mailer,
            @ConfigProperty(name = "paper-monitor.mail.from", defaultValue = "no-reply@paper-monitor.local") String fromAddress
    ) {
        this.mailer = mailer;
        this.fromAddress = fromAddress;
    }

    public void sendUserAccountNotification(AppUser target, String subject, String body) {
        sendText(target == null ? null : target.email, subject, body);
    }

    public void sendFeedAccessNotification(AppUser target, LogicalFeed logicalFeed, String role, AppUser grantedBy) {
        String grantor = grantedBy == null ? "an administrator" : grantedBy.displayLabel();
        sendText(target == null ? null : target.email,
                "Paper Monitor access granted for " + logicalFeed.name,
                "You were granted " + role.toLowerCase() + " access to logical feed \"" + logicalFeed.name + "\" by " + grantor + ".");
    }

    public void sendSignupVerificationEmail(AppUser target, String verificationUrl) {
        String approvalLine = target != null && target.approved
                ? "After verification, you can sign in right away."
                : "After verification, an administrator still needs to approve your account before sign-in is enabled.";
        sendText(target == null ? null : target.email,
                "Verify your Paper Monitor email",
                "Welcome to Paper Monitor.\n\nVerify your email address by opening:\n" + verificationUrl
                        + "\n\n" + approvalLine);
    }

    public void sendPendingSignupNotification(Collection<AppUser> admins, AppUser pendingUser, String adminUrl) {
        Set<String> recipients = new LinkedHashSet<>();
        for (AppUser admin : admins) {
            if (admin == null || admin.email == null || admin.email.isBlank()) {
                continue;
            }
            recipients.add(admin.email);
        }
        String subject = "Paper Monitor signup awaiting approval";
        String body = "A new Paper Monitor account was created.\n\n"
                + "Username: " + pendingUser.username + "\n"
                + "Display name: " + pendingUser.displayLabel() + "\n"
                + "Email: " + (pendingUser.email == null ? "not provided" : pendingUser.email) + "\n"
                + "Email verified: " + (pendingUser.emailVerified ? "yes" : "no") + "\n\n"
                + "Review and approve it from the admin menu:\n" + adminUrl;
        for (String recipient : recipients) {
            sendText(recipient, subject, body);
        }
    }

    public void sendAccountApprovedNotification(AppUser target, String loginUrl) {
        sendText(target == null ? null : target.email,
                "Your Paper Monitor account was approved",
                "Your Paper Monitor account is now approved.\n\nYou can sign in here:\n" + loginUrl);
    }

    public void sendRssPaperDigest(LogicalFeed logicalFeed, Feed feed, List<Paper> papers) {
        if (logicalFeed == null || feed == null || papers == null || papers.isEmpty()) {
            return;
        }
        Set<String> recipients = new LinkedHashSet<>();
        if (logicalFeed.owner != null && logicalFeed.owner.email != null && !logicalFeed.owner.email.isBlank()) {
            recipients.add(logicalFeed.owner.email);
        }
        for (var grant : logicalFeed.accessGrants) {
            if (grant == null || !grant.canAdmin() || grant.user == null || grant.user.email == null || grant.user.email.isBlank()) {
                continue;
            }
            recipients.add(grant.user.email);
        }
        if (recipients.isEmpty()) {
            return;
        }

        StringBuilder body = new StringBuilder();
        body.append("Paper Monitor imported ")
                .append(papers.size())
                .append(papers.size() == 1 ? " new paper" : " new papers")
                .append(" from RSS into \"")
                .append(logicalFeed.name)
                .append("\".\n\n")
                .append("Feed: ")
                .append(feed.name)
                .append("\n");

        for (Paper paper : papers) {
            body.append("\n- Title: ").append(safe(paper.title))
                    .append("\n  Status: ").append(safe(paper.status))
                    .append("\n  Authors: ").append(safe(paper.authors))
                    .append("\n  Venue: ").append(safe(paper.publisher))
                    .append("\n  Published: ").append(safe(paper.publishedOn))
                    .append("\n  Source: ").append(safe(paper.sourceLink));
            if (paper.openAccessLink != null && !paper.openAccessLink.isBlank()) {
                body.append("\n  Open access: ").append(paper.openAccessLink);
            }
        }

        String subject = "Paper Monitor RSS update for " + logicalFeed.name;
        for (String recipient : recipients) {
            sendText(recipient, subject, body.toString());
        }
    }

    private void sendText(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            mailer.send(Mail.withText(to, subject, body).setFrom(fromAddress));
        } catch (Exception e) {
            Log.errorf(e, "Failed to send notification email to %s", to);
        }
    }

    private String safe(Object value) {
        if (value == null) {
            return "unknown";
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "unknown" : text;
    }
}
