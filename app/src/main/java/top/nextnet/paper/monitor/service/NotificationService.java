package top.nextnet.paper.monitor.service;

import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import top.nextnet.paper.monitor.model.AppUser;
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

    public void sendRssPaperDigest(LogicalFeed logicalFeed, List<Paper> papers, LocalDate digestDate) {
        if (logicalFeed == null || papers == null || papers.isEmpty()) {
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

        RenderedDigest digest = renderRssDigest(logicalFeed, papers, digestDate);
        for (String recipient : recipients) {
            sendMultipart(recipient, digest.subject(), digest.text(), digest.html());
        }
    }

    static RenderedDigest renderRssDigest(LogicalFeed logicalFeed, List<Paper> papers, LocalDate digestDate) {
        int count = papers.size();
        String paperWord = count == 1 ? "paper" : "papers";
        String feedName = safe(logicalFeed == null ? null : logicalFeed.name);
        String subject = count + " new " + paperWord + " in " + feedName;
        LocalDate effectiveDate = digestDate == null ? LocalDate.now() : digestDate;
        String displayDate = effectiveDate.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH));

        Map<String, List<Paper>> papersByFeed = new LinkedHashMap<>();
        for (Paper paper : papers) {
            String sourceFeed = paper.feed == null ? "RSS" : safe(paper.feed.name);
            papersByFeed.computeIfAbsent(sourceFeed, ignored -> new java.util.ArrayList<>()).add(paper);
        }

        StringBuilder text = new StringBuilder();
        text.append(count).append(" new ").append(paperWord).append(" added to ")
                .append(feedName).append("\nDaily update for ").append(displayDate).append("\n");

        StringBuilder sections = new StringBuilder();
        for (Map.Entry<String, List<Paper>> entry : papersByFeed.entrySet()) {
            text.append("\n").append(entry.getKey()).append("\n");
            sections.append("<h2 style=\"margin:28px 0 14px;font-size:14px;letter-spacing:.04em;color:#39705f\">")
                    .append(escapeHtml(entry.getKey())).append("</h2>");
            for (Paper paper : entry.getValue()) {
                appendTextPaper(text, paper);
                appendHtmlPaper(sections, paper);
            }
        }

        String html = "<!doctype html><html><body style=\"margin:0;background:#f4f1e8;color:#1f2925;"
                + "font-family:Arial,Helvetica,sans-serif\"><div style=\"max-width:640px;margin:0 auto;padding:24px 12px\">"
                + "<div style=\"background:#173f35;padding:24px 28px;border-radius:14px 14px 0 0;color:#fff\">"
                + "<div style=\"font-size:12px;letter-spacing:1.5px;text-transform:uppercase;color:#b9d8ca\">Paper Monitor</div>"
                + "<h1 style=\"margin:8px 0 4px;font-size:24px\">" + count + " new " + paperWord + "</h1>"
                + "<p style=\"margin:0;color:#dbe9e2\">Added to <strong>" + escapeHtml(feedName) + "</strong></p></div>"
                + "<div style=\"background:#fff;padding:18px 28px 30px\">"
                + "<p style=\"margin:0;color:#78847f;font-size:13px\">Daily update for " + escapeHtml(displayDate) + "</p>"
                + sections
                + "</div><div style=\"background:#e8eee9;padding:16px 28px;border-radius:0 0 14px 14px;"
                + "font-size:12px;line-height:1.5;color:#64716c\">You received this email because you administer the "
                + "&ldquo;" + escapeHtml(feedName) + "&rdquo; paper feed.</div></div></body></html>";
        return new RenderedDigest(subject, text.toString(), html);
    }

    private static void appendTextPaper(StringBuilder text, Paper paper) {
        text.append("\n- ").append(safe(paper.title))
                .append("\n  Status: ").append(safe(paper.status))
                .append("\n  Authors: ").append(safe(paper.authors))
                .append("\n  Venue: ").append(safe(paper.publisher))
                .append("\n  Published: ").append(safe(paper.publishedOn))
                .append("\n  Source: ").append(safe(paper.sourceLink));
        if (safeHttpUrl(paper.openAccessLink) != null) {
            text.append("\n  Open access: ").append(paper.openAccessLink.trim());
        }
        text.append("\n");
    }

    private static void appendHtmlPaper(StringBuilder html, Paper paper) {
        String sourceUrl = safeHttpUrl(paper.sourceLink);
        String openAccessUrl = safeHttpUrl(paper.openAccessLink);
        String title = escapeHtml(safe(paper.title));
        String titleHtml = sourceUrl == null
                ? title
                : "<a href=\"" + escapeHtml(sourceUrl) + "\" style=\"color:#173f35;text-decoration:none\">" + title + "</a>";
        html.append("<article style=\"border-left:4px solid #d7943d;padding:0 0 2px 16px;margin:0 0 24px\">")
                .append("<div style=\"font-size:11px;font-weight:bold;letter-spacing:.04em;color:#39705f\">")
                .append(escapeHtml(safe(paper.status))).append(" &middot; ")
                .append(escapeHtml(safe(paper.publishedOn))).append("</div>")
                .append("<h3 style=\"font-size:18px;line-height:1.35;margin:6px 0\">").append(titleHtml).append("</h3>")
                .append("<p style=\"margin:8px 0;color:#4e5d57\">").append(escapeHtml(safe(paper.authors))).append("</p>")
                .append("<p style=\"margin:0;color:#78847f;font-size:14px\">")
                .append(escapeHtml(safe(paper.publisher))).append("</p>");
        if (openAccessUrl != null && !openAccessUrl.equals(sourceUrl)) {
            html.append("<p style=\"margin:10px 0 0\"><a href=\"").append(escapeHtml(openAccessUrl))
                    .append("\" style=\"color:#39705f;font-size:13px\">Open-access copy</a></p>");
        }
        html.append("</article>");
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

    private void sendMultipart(String to, String subject, String text, String html) {
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            mailer.send(Mail.withText(to, subject, text).setHtml(html).setFrom(fromAddress));
        } catch (Exception e) {
            Log.errorf(e, "Failed to send notification email to %s", to);
        }
    }

    private static String safe(Object value) {
        if (value == null) {
            return "unknown";
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "unknown" : text;
    }

    private static String safeHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    record RenderedDigest(String subject, String text, String html) {
    }
}
