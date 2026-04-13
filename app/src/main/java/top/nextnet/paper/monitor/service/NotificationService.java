package top.nextnet.paper.monitor.service;

import io.quarkus.logging.Log;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
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
