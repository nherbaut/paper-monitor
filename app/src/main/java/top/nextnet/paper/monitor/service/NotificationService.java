package top.nextnet.paper.monitor.service;

import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;

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
        sendText(target == null ? null : target.email,
                "Verify your Paper Monitor email",
                "Welcome to Paper Monitor.\n\nVerify your email address by opening:\n" + verificationUrl
                        + "\n\nAfter verification, an administrator still needs to approve your account before sign-in is enabled.");
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
}
