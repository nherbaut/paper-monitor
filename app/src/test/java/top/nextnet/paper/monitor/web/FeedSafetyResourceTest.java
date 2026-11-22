package top.nextnet.paper.monitor.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.*;
import top.nextnet.paper.monitor.service.AuthService;
import top.nextnet.paper.monitor.service.BackupService;
import top.nextnet.paper.monitor.service.EmailDomainPolicyService;
import top.nextnet.paper.monitor.service.FeedSharingService;
import top.nextnet.paper.monitor.service.LogicalFeedAccessService;
import top.nextnet.paper.monitor.service.QuickSetupWorkflows;

@QuarkusTest
class FeedSafetyResourceTest {

    @Inject AuthService auth;
    @Inject FeedSharingService sharing;
    @Inject LogicalFeedAccessService access;
    @Inject EmailDomainPolicyService policies;
    @Inject BackupService backups;
    @Inject MockMailbox mailbox;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> policyIds = new ArrayList<>();
    private Long ownerId;
    private Long recipientId;
    private Long adminId;
    private Long feedId;
    private Long rssId;
    private String ownerToken;
    private String adminToken;
    private String recipientEmail;
    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString();
        mailbox.clear();
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser owner = user("owner", false, true, true);
            AppUser recipient = user("recipient", false, true, true);
            // Email-like display labels must not leak via the Access or Ownership panels.
            recipient.username = recipient.email;
            recipient.displayName = recipient.email;
            recipient.passwordHash = "private-hash-" + suffix;
            recipient.passwordSalt = "private-salt-" + suffix;
            AppUser admin = user("admin", true, true, true);
            ownerId = owner.id;
            recipientId = recipient.id;
            adminId = admin.id;
            recipientEmail = recipient.email;
            ownerToken = auth.createSession(owner).token;
            adminToken = auth.createSession(admin).token;
            LogicalFeed feed = new LogicalFeed();
            feed.name = "Safety review " + suffix;
            feed.owner = owner;
            feed.workflowStates = QuickSetupWorkflows.KANBAN;
            feed.persist();
            feedId = feed.id;
            Feed rss = new Feed();
            rss.name = "Manual test source";
            rss.url = "upload://" + suffix;
            rss.logicalFeed = feed;
            rss.persist();
            rssId = rss.id;
        });
    }

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            PaperEvent.delete("paper.logicalFeed.id", feedId);
            Paper.delete("logicalFeed.id", feedId);
            LogicalFeedAccessGrant.delete("logicalFeed.id", feedId);
            LogicalFeedInvitation.delete("logicalFeed.id", feedId);
            LogicalFeed feed = LogicalFeed.findById(feedId);
            if (feed != null) feed.delete();
            for (Long id : userIds) {
                UserSession.delete("user.id", id);
                AppUser.deleteById(id);
            }
            for (Long id : policyIds) EmailDomainPolicy.deleteById(id);
        });
        userIds.clear();
        policyIds.clear();
        mailbox.clear();
    }

    @Test
    void ordinaryFeedAdminsCannotBrowseMemberEmailsButGlobalAdminsCan() {
        QuarkusTransaction.requiringNew().run(() -> access.grant(LogicalFeed.findById(feedId), AppUser.findById(recipientId), "READ"));
        String html = given().cookie(auth.sessionCookieName(), ownerToken)
                .get("/admin").then().statusCode(200).extract().asString();
        assertFalse(html.contains(recipientEmail));
        assertTrue(html.contains("name=\"email\""));
        assertFalse(html.contains("<select name=\"userId\""));
        assertTrue(given().cookie(auth.sessionCookieName(), adminToken).get("/admin")
                .then().statusCode(200).extract().asString().contains(recipientEmail));
    }

    @Test
    void sharesWithAnExistingActiveUserByNormalizedEmail() {
        share(ownerToken, " " + recipientEmail.toUpperCase() + " ", "READ").then().statusCode(303);
        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(access.canRead(LogicalFeed.findById(feedId), AppUser.findById(recipientId)));
            assertEquals(0L, LogicalFeedInvitation.count());
        });
        assertEquals(1, mailbox.getMessagesSentTo(recipientEmail).size());
    }

    @Test
    void oldUserIdParameterCannotBeUsedToShareByEnumeration() {
        given().cookie(auth.sessionCookieName(), ownerToken).redirects().follow(false)
                .formParam("userId", recipientId).formParam("role", "ADMIN")
                .post("/logical-feeds/" + feedId + "/share").then().statusCode(303);
        QuarkusTransaction.requiringNew().run(() ->
                assertFalse(access.canRead(LogicalFeed.findById(feedId), AppUser.findById(recipientId))));
    }

    @Test
    void nonAdminCannotGrantAccessOrIssueInvitations() {
        String token = QuarkusTransaction.requiringNew().call(() -> auth.createSession(AppUser.findById(recipientId)).token);
        share(token, "unknown@example.org", "ADMIN").then().statusCode(403);
        assertTrue(mailbox.getMessagesSentTo("unknown@example.org").isEmpty());
        QuarkusTransaction.requiringNew().run(() -> assertEquals(0L, LogicalFeedInvitation.count()));
    }

    @Test
    void invitationDoesNotCreateAnAccountOrBypassVerificationAndApproval() {
        String email = "invite-" + suffix + "@example.org";
        share(ownerToken, email, "READ").then().statusCode(303);
        assertEquals(1, mailbox.getMessagesSentTo(email).size());
        Long invitee = QuarkusTransaction.requiringNew().call(() -> {
            assertEquals(0L, AppUser.count("email", email));
            AppUser user = user("invitee", false, false, false);
            user.email = email;
            sharing.acceptPending(user);
            assertFalse(access.canRead(LogicalFeed.findById(feedId), user));
            user.emailVerified = true;
            sharing.acceptPending(user);
            assertFalse(access.canRead(LogicalFeed.findById(feedId), user));
            user.emailVerified = false;
            user.approved = true;
            sharing.acceptPending(user);
            assertFalse(access.canRead(LogicalFeed.findById(feedId), user));
            user.emailVerified = true;
            return user.id;
        });
        QuarkusTransaction.requiringNew().run(() -> {
            auth.createSession(AppUser.findById(invitee));
            assertTrue(access.canRead(LogicalFeed.findById(feedId), AppUser.findById(invitee)));
            assertEquals(0L, LogicalFeedInvitation.count("email", email));
        });
    }

    @Test
    void invitationsRespectSignupPolicyAndDoNotRevealUnknownAccountStatus() {
        String domain = suffix + ".example.org";
        QuarkusTransaction.requiringNew().run(() -> policyIds.add(policies.create(domain, false, false).id));
        share(ownerToken, "invite@" + domain, "READ").then().statusCode(303);
        QuarkusTransaction.requiringNew().run(() -> assertEquals(0L, LogicalFeedInvitation.count()));
        assertTrue(mailbox.getMessagesSentTo("invite@" + domain).isEmpty());
    }

    @Test
    void expiredAndRevokedInvitationsCannotGrantAccess() {
        String email = "expired-" + suffix + "@example.org";
        share(ownerToken, email, "READ").then().statusCode(303);
        Long invitationId = QuarkusTransaction.requiringNew().call(() -> {
            LogicalFeedInvitation invitation = LogicalFeedInvitation.find("email", email).firstResult();
            invitation.expiresAt = Instant.now().minusSeconds(1);
            AppUser invitee = AppUser.findById(recipientId);
            invitee.email = email;
            sharing.acceptPending(invitee);
            assertFalse(access.canRead(LogicalFeed.findById(feedId), invitee));
            return invitation.id;
        });
        given().cookie(auth.sessionCookieName(), ownerToken).redirects().follow(false)
                .post("/logical-feeds/" + feedId + "/invitations/" + invitationId + "/delete")
                .then().statusCode(303);
        QuarkusTransaction.requiringNew().run(() -> assertNull(LogicalFeedInvitation.findById(invitationId)));
    }

    @Test
    void scopedBackupRedactsOtherMembersEmailsAndCredentials() {
        QuarkusTransaction.requiringNew().run(() -> {
            access.grant(LogicalFeed.findById(feedId), AppUser.findById(recipientId), "READ");
            try {
                byte[] archive = backups.exportZip(List.of(LogicalFeed.findById(feedId)), AppUser.findById(ownerId));
                try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
                    assertEquals("data.json", zip.getNextEntry().getName());
                    String json = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertFalse(json.contains(recipientEmail));
                    assertFalse(json.contains("private-hash-" + suffix));
                    assertFalse(json.contains("private-salt-" + suffix));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void batchPreviewIncludesAllMatchingPagesAndApplyUsesOnlyReviewedIds() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 0; i < 65; i++) paper("Selected paper " + i, "TODO", "review");
            paper("A different state", "NEW", "review");
            paper("A different tag", "TODO", "other");
        });
        List<Map<String, Object>> preview = given().cookie(auth.sessionCookieName(), ownerToken)
                .formParam("logicalFeedId", feedId).formParam("filterStatus", "TODO")
                .formParam("filterTag", "review").formParam("filterSearch", "Selected")
                .post("/papers/batch/preview").then().statusCode(200).extract().jsonPath().getList("papers");
        assertEquals(65, preview.size());
        QuarkusTransaction.requiringNew().run(() -> paper("Selected paper arriving after preview", "TODO", "review"));
        given().cookie(auth.sessionCookieName(), ownerToken)
                .formParam("logicalFeedId", feedId).formParam("selection", "ids")
                .formParam("paperIds", preview.stream().map(item -> item.get("id")).toList())
                .formParam("operation", "add_tag").formParam("tag", "confirmed")
                .post("/papers/batch").then().statusCode(204);
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(65L, Paper.count("logicalFeed.id = ?1 and tags like ?2", feedId, "%confirmed%"));
            Paper late = Paper.find("logicalFeed.id = ?1 and title = ?2", feedId, "Selected paper arriving after preview").firstResult();
            assertEquals("review", late.tags);
        });
    }

    @Test
    void batchPreviewIsRestrictedToFeedAdmins() {
        String token = QuarkusTransaction.requiringNew().call(() -> {
            AppUser recipient = AppUser.findById(recipientId);
            access.grant(LogicalFeed.findById(feedId), recipient, "READ");
            return auth.createSession(recipient).token;
        });
        given().cookie(auth.sessionCookieName(), token).formParam("logicalFeedId", feedId)
                .post("/papers/batch/preview").then().statusCode(403);
    }

    @Test
    void browserChecks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("paper-monitor.browser-check"));
        QuarkusTransaction.requiringNew().run(() -> {
            paper("A long research title about governance, evidence and screening decisions in systematic literature reviews", "NEW", "review");
            Paper paper = Paper.find("logicalFeed.id", feedId).firstResult();
            paper.summary = "Research abstract with selectable text and detailed findings. ".repeat(45);
        });
        ProcessBuilder builder = new ProcessBuilder("node", "src/test/browser/feed-safety.cjs", Long.toString(feedId));
        builder.environment().put("PAPER_MONITOR_TEST_COOKIE", ownerToken);
        builder.inheritIO();
        assertEquals(0, builder.start().waitFor());
    }

    private io.restassured.response.Response share(String token, String email, String role) {
        return given().cookie(auth.sessionCookieName(), token).redirects().follow(false)
                .formParam("email", email).formParam("role", role)
                .post("/logical-feeds/" + feedId + "/share");
    }

    private AppUser user(String label, boolean admin, boolean verified, boolean approved) {
        AppUser user = new AppUser();
        user.username = label + "-" + suffix;
        user.email = label + "-" + suffix + "@example.org";
        user.authProvider = "LOCAL";
        user.admin = admin;
        user.emailVerified = verified;
        user.approved = approved;
        user.persist();
        userIds.add(user.id);
        return user;
    }

    private void paper(String title, String state, String tags) {
        Paper paper = new Paper();
        paper.title = title;
        paper.sourceLink = "https://example.org/" + UUID.randomUUID();
        paper.status = state;
        paper.discoveredAt = Instant.now();
        paper.tags = tags;
        paper.feed = Feed.findById(rssId);
        paper.logicalFeed = LogicalFeed.findById(feedId);
        paper.persist();
    }
}
