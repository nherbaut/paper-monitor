package top.nextnet.paper.monitor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.*;
import top.nextnet.paper.monitor.service.AuthService;
import top.nextnet.paper.monitor.service.QuickSetupWorkflows;

@QuarkusTest
class ThemeResourceTest {
    @Inject AuthService auth;

    @Test
    void browserChecks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("paper-monitor.theme-browser-check"));
        Long[] ids = new Long[4];
        String token = QuarkusTransaction.requiringNew().call(() -> {
            AppUser user = new AppUser();
            user.username = "Theme reviewer " + UUID.randomUUID();
            user.authProvider = "LOCAL";
            user.admin = true;
            user.approved = true;
            user.emailVerified = true;
            user.persist();
            ids[0] = user.id;
            LogicalFeed feed = new LogicalFeed();
            feed.name = "Shared theme review";
            feed.owner = user;
            feed.workflowStates = QuickSetupWorkflows.KANBAN;
            feed.persist();
            ids[1] = feed.id;
            Feed rss = new Feed();
            rss.name = "Theme source";
            rss.url = "upload://theme-" + UUID.randomUUID();
            rss.logicalFeed = feed;
            rss.persist();
            Paper paper = new Paper();
            paper.title = "A research paper for reviewing the shared theme";
            paper.summary = "Evidence, findings and notes remain readable in both light and dark modes.";
            paper.logicalFeed = feed;
            paper.feed = rss;
            paper.sourceLink = "https://example.org/theme";
            paper.status = "NEW";
            paper.discoveredAt = Instant.now();
            paper.persist();
            ids[2] = paper.id;
            PaperEvent event = new PaperEvent();
            event.paper = paper;
            event.type = "FETCH";
            event.details = "Imported from Theme source";
            event.happenedAt = Instant.now();
            event.persist();
            Review review = new Review();
            review.owner = user;
            review.logicalFeed = feed;
            review.title = "Shared theme review";
            review.templateId = "theme-test";
            review.templateTitle = "Theme test";
            review.selectedStatesJson = "[\"NEW\"]";
            review.reviewDesignJson = "{}";
            review.formSchemaJson = "{\"dimensions\":[]}";
            review.createdAt = Instant.now();
            review.updatedAt = Instant.now();
            review.persist();
            ids[3] = review.id;
            return auth.createSession(user).token;
        });
        try {
            ProcessBuilder builder = new ProcessBuilder("node", "src/test/browser/shared-theme.cjs",
                    ids[1].toString(), ids[2].toString(), ids[3].toString());
            builder.environment().put("PAPER_MONITOR_TEST_COOKIE", token);
            builder.inheritIO();
            assertEquals(0, builder.start().waitFor());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                ReviewSubmission.delete("review.id", ids[3]);
                Review.deleteById(ids[3]);
                PaperEvent.delete("paper.logicalFeed.id", ids[1]);
                LogicalFeed.deleteById(ids[1]);
                UserSession.delete("user.id", ids[0]);
                AppUser.deleteById(ids[0]);
            });
        }
    }
}
