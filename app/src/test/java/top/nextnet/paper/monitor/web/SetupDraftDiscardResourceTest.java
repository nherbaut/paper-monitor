package top.nextnet.paper.monitor.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.PaperFeedSetupDraft;
import top.nextnet.paper.monitor.model.UserSession;
import top.nextnet.paper.monitor.service.AuthService;
import top.nextnet.paper.monitor.service.QuickSetupWorkflows;

@QuarkusTest
class SetupDraftDiscardResourceTest {
    @Inject AuthService auth;
    private final List<String> draftIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> feedIds = new ArrayList<>();
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            ownerToken = session("draft-owner");
            otherToken = session("draft-other");
        });
    }

    private String session(String label) {
        AppUser user = new AppUser();
        user.username = label + UUID.randomUUID();
        user.authProvider = "LOCAL";
        user.approved = true;
        user.emailVerified = true;
        user.persist();
        userIds.add(user.id);
        return auth.createSession(user).token;
    }

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String id : draftIds) PaperFeedSetupDraft.deleteById(id);
            for (Long id : feedIds) LogicalFeed.deleteById(id);
            for (Long id : userIds) {
                UserSession.delete("user.id", id);
                AppUser.deleteById(id);
            }
        });
    }

    private String createDraft(String token) {
        var request = given();
        if (token != null) request.cookie(auth.sessionCookieName(), token);
        String id = request.post("/api/setup/fresh").then().statusCode(200).extract().path("id");
        draftIds.add(id);
        return id;
    }

    private String url(String id) {
        return "/api/setup/drafts/" + id;
    }

    @Test
    void anonymousUsersCanResumeAndDiscardTheirDraftWithoutRemovingOtherDrafts() {
        String id = createDraft(null);
        String otherId = createDraft(null);
        given().get(url(id)).then().statusCode(200);
        given().post(url(id) + "/discard").then().statusCode(200);
        given().get(url(id)).then().statusCode(404);
        given().get(url(otherId)).then().statusCode(200);
        given().post(url(id) + "/discard").then().statusCode(404);
    }

    @Test
    void accountDraftsCanOnlyBeDiscardedByTheirOwner() {
        String id = createDraft(ownerToken);
        given().post(url(id) + "/discard").then().statusCode(404);
        given().cookie(auth.sessionCookieName(), otherToken).post(url(id) + "/discard").then().statusCode(404);
        given().cookie(auth.sessionCookieName(), ownerToken).get(url(id)).then().statusCode(200);
        given().cookie(auth.sessionCookieName(), ownerToken).post(url(id) + "/discard").then().statusCode(200);
        given().cookie(auth.sessionCookieName(), ownerToken).get(url(id)).then().statusCode(404);
    }

    @Test
    void completedSetupsCannotBeDiscardedOrTheirFeedsDeleted() {
        for (String token : new String[]{null, ownerToken}) {
            String id = createDraft(token);
            Long feedId = QuarkusTransaction.requiringNew().call(() -> {
                PaperFeedSetupDraft draft = PaperFeedSetupDraft.findById(id);
                LogicalFeed feed = new LogicalFeed();
                feed.name = "Completed setup " + UUID.randomUUID();
                feed.workflowStates = QuickSetupWorkflows.KANBAN;
                feed.owner = draft.user;
                feed.persist();
                draft.logicalFeed = feed;
                return feed.id;
            });
            feedIds.add(feedId);
            var request = given();
            if (token != null) request.cookie(auth.sessionCookieName(), token);
            request.post(url(id) + "/discard").then().statusCode(409);
            QuarkusTransaction.requiringNew().run(() -> {
                assertNotNull(LogicalFeed.findById(feedId));
                assertNotNull(PaperFeedSetupDraft.findById(id));
            });
        }
    }

    @Test
    void missingAndExpiredDraftsReturnNotFound() {
        given().post(url(UUID.randomUUID().toString()) + "/discard").then().statusCode(404);
        String id = createDraft(null);
        QuarkusTransaction.requiringNew().run(() -> {
            PaperFeedSetupDraft draft = PaperFeedSetupDraft.findById(id);
            draft.expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES);
        });
        given().post(url(id) + "/discard").then().statusCode(404);
    }

    @Test
    void browserChecks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("paper-monitor.draft-browser-check"));
        String anonymousId = createDraft(null);
        String ownedId = createDraft(ownerToken);
        ProcessBuilder builder = new ProcessBuilder("node", "src/test/browser/discard-setup-draft.cjs", anonymousId, ownedId);
        builder.environment().put("PAPER_MONITOR_TEST_COOKIE", ownerToken);
        builder.inheritIO();
        assertEquals(0, builder.start().waitFor());
    }
}
