package top.nextnet.paper.monitor.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.GoogleDrivePdfSync;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PaperFeedSetupDraft;
import top.nextnet.paper.monitor.model.PdfCapture;
import top.nextnet.paper.monitor.repo.GoogleDrivePdfSyncRepository;
import top.nextnet.paper.monitor.repo.PaperFeedSetupDraftRepository;
import top.nextnet.paper.monitor.repo.PdfCaptureRepository;
import top.nextnet.paper.monitor.service.QuickSetupWorkflows;
import top.nextnet.paper.monitor.service.SetupWizardService;

@QuarkusTest
class AnonymousSetupResourceTest {

    @Inject
    PaperFeedSetupDraftRepository draftRepository;

    @Inject
    SetupWizardService setupWizardService;

    @Inject
    GoogleDrivePdfSyncRepository googleDrivePdfSyncRepository;

    @Inject
    PdfCaptureRepository pdfCaptureRepository;

    @Test
    void anonymousHomeLinksToPublicSetupWizard() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("href=\"/setup\""))
                .body(containsString("Set up a paper feed"));
    }

    @Test
    void anonymousHomeOffersSignupWithBundledAvatars() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("href=\"#create-account\""))
                .body(containsString("name=\"avatarFileName\""))
                .body(containsString("/assets/student-avatar/miage-student-01.png"));
    }

    @Test
    void setupWizardAndDraftApiDoNotRedirectAnonymousUsersToLogin() {
        given()
                .redirects().follow(false)
                .when().get("/setup")
                .then()
                .statusCode(200)
                .body(containsString("data-authenticated=\"false\""))
                .body(containsString("Create and open temporary paper feed"));

        given()
                .redirects().follow(false)
                .when().get("/api/setup/drafts/missing")
                .then()
                .statusCode(404)
                .body(containsString("Setup draft was not found or has expired"));
    }

    @Test
    void anonymousDraftCanBeRestoredByItsCapabilityId() {
        String draftId = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            PaperFeedSetupDraft draft = new PaperFeedSetupDraft();
            draft.id = draftId;
            draft.title = "Temporary anonymous feed";
            draft.expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
            draftRepository.persist(draft);
        });

        given()
                .when().get("/api/setup/drafts/" + draftId)
                .then()
                .statusCode(200)
                .body(containsString("Temporary anonymous feed"));
    }

    @Test
    void anonymousDraftTokenCanOpenAndClassifyTemporaryFeed() {
        String draftId = UUID.randomUUID().toString();
        String suffix = UUID.randomUUID().toString();
        Long[] ids = new Long[2];
        QuarkusTransaction.requiringNew().run(() -> {
            LogicalFeed logicalFeed = new LogicalFeed();
            logicalFeed.name = "Temporary feed " + suffix;
            logicalFeed.workflowStates = QuickSetupWorkflows.KANBAN;
            logicalFeed.publicReadable = false;
            logicalFeed.notifyOnNewRssPapers = false;
            logicalFeed.publicShareToken = UUID.randomUUID().toString();
            logicalFeed.persist();

            Feed feed = new Feed();
            feed.name = "Temporary RSS " + suffix;
            feed.url = "https://example.invalid/" + suffix + ".rss";
            feed.logicalFeed = logicalFeed;
            feed.persist();

            Paper paper = new Paper();
            paper.title = "Anonymous paper " + suffix;
            paper.sourceLink = "https://example.invalid/paper/" + suffix;
            paper.status = "NEW";
            paper.discoveredAt = Instant.now();
            paper.feed = feed;
            paper.logicalFeed = logicalFeed;
            paper.persist();

            PaperFeedSetupDraft draft = new PaperFeedSetupDraft();
            draft.id = draftId;
            draft.title = logicalFeed.name;
            draft.workflowType = "MIAGE";
            draft.logicalFeed = logicalFeed;
            draft.expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
            draftRepository.persist(draft);

            ids[0] = logicalFeed.id;
            ids[1] = paper.id;
        });

        given()
                .when().get("/anonymous/feed/" + draftId)
                .then()
                .statusCode(200)
                .body(containsString("This is a temporary paper feed."))
                .body(containsString("class=\"classification-temporary-notice\""))
                .body(containsString("data-anonymous-setup-token=\"" + draftId + "\""))
                .body(containsString("data-start-classification-mode=\"true\""));

        given()
                .redirects().follow(false)
                .when().get("/api/papers/browser?logicalFeedId=" + ids[0])
                .then()
                .statusCode(303);

        given()
                .header("X-Paper-Monitor-Setup-Token", draftId)
                .when().get("/api/papers/browser?logicalFeedId=" + ids[0] + "&classificationQueue=true")
                .then()
                .statusCode(200)
                .body("[0].paperTitle", equalTo("Anonymous paper " + suffix))
                .body("[0].paperCanEditTags", equalTo(true));

        given()
                .header("X-Paper-Monitor-Setup-Token", draftId)
                .contentType("application/x-www-form-urlencoded")
                .formParam("status", "TODO")
                .when().post("/papers/" + ids[1] + "/status")
                .then()
                .statusCode(204);

        String persistedStatus = QuarkusTransaction.requiringNew().call(() -> {
            Paper paper = Paper.findById(ids[1]);
            return paper.status;
        });
        Assertions.assertEquals("TODO", persistedStatus);
    }

    @Test
    void signingInClaimsTemporaryFeedForTheAccount() {
        String draftId = UUID.randomUUID().toString();
        String suffix = UUID.randomUUID().toString();
        Long[] ids = new Long[2];
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = new AppUser();
            user.username = "claim-" + suffix;
            user.authProvider = "LOCAL";
            user.persist();

            LogicalFeed logicalFeed = temporaryLogicalFeed(suffix);

            PaperFeedSetupDraft draft = new PaperFeedSetupDraft();
            draft.id = draftId;
            draft.title = logicalFeed.name;
            draft.logicalFeed = logicalFeed;
            draft.expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
            draftRepository.persist(draft);

            ids[0] = user.id;
            ids[1] = logicalFeed.id;
        });

        Map<String, Object> claimed = QuarkusTransaction.requiringNew().call(() -> {
            AppUser user = AppUser.findById(ids[0]);
            return setupWizardService.draft(user, draftId);
        });

        Assertions.assertEquals("/?logicalFeedId=" + ids[1], claimed.get("url"));
        QuarkusTransaction.requiringNew().run(() -> {
            PaperFeedSetupDraft draft = draftRepository.findById(draftId);
            LogicalFeed logicalFeed = LogicalFeed.findById(ids[1]);
            Assertions.assertEquals(ids[0], draft.user.id);
            Assertions.assertEquals(ids[0], logicalFeed.owner.id);
            Assertions.assertTrue(logicalFeed.notifyOnNewRssPapers);
        });
    }

    @Test
    void cleanupDeletesExpiredUnclaimedFeedAndItsPapers() {
        String draftId = UUID.randomUUID().toString();
        String suffix = UUID.randomUUID().toString();
        Long[] ids = new Long[3];
        QuarkusTransaction.requiringNew().run(() -> {
            LogicalFeed logicalFeed = temporaryLogicalFeed(suffix);

            Feed feed = new Feed();
            feed.name = "Expired RSS " + suffix;
            feed.url = "https://example.invalid/expired-" + suffix + ".rss";
            feed.logicalFeed = logicalFeed;
            feed.persist();

            Paper paper = new Paper();
            paper.title = "Expired paper " + suffix;
            paper.sourceLink = "https://example.invalid/expired-paper/" + suffix;
            paper.discoveredAt = Instant.now();
            paper.feed = feed;
            paper.logicalFeed = logicalFeed;
            paper.persist();

            PaperFeedSetupDraft draft = new PaperFeedSetupDraft();
            draft.id = draftId;
            draft.logicalFeed = logicalFeed;
            draft.expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES);
            draftRepository.persist(draft);

            ids[0] = logicalFeed.id;
            ids[1] = feed.id;
            ids[2] = paper.id;
        });

        setupWizardService.deleteExpiredDrafts();

        QuarkusTransaction.requiringNew().run(() -> {
            Assertions.assertNull(draftRepository.findById(draftId));
            Assertions.assertNull(LogicalFeed.findById(ids[0]));
            Assertions.assertNull(Feed.findById(ids[1]));
            Assertions.assertNull(Paper.findById(ids[2]));
        });
    }

    @Test
    void deletingFeedDependenciesAllowsItsPapersToBeRemoved() {
        String suffix = UUID.randomUUID().toString();
        Long[] ids = new Long[3];
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = new AppUser();
            user.username = "delete-dependencies-" + suffix;
            user.authProvider = "LOCAL";
            user.persist();

            LogicalFeed logicalFeed = temporaryLogicalFeed(suffix);
            Feed feed = new Feed();
            feed.name = "Dependency RSS " + suffix;
            feed.url = "https://example.invalid/dependencies-" + suffix + ".rss";
            feed.logicalFeed = logicalFeed;
            feed.persist();

            Paper paper = new Paper();
            paper.title = "Dependency paper " + suffix;
            paper.sourceLink = "https://example.invalid/dependency-paper/" + suffix;
            paper.status = "NEW";
            paper.discoveredAt = Instant.now();
            paper.feed = feed;
            paper.logicalFeed = logicalFeed;
            paper.persist();

            GoogleDrivePdfSync sync = new GoogleDrivePdfSync();
            sync.user = user;
            sync.paper = paper;
            googleDrivePdfSyncRepository.persist(sync);

            PdfCapture capture = new PdfCapture();
            capture.tokenHash = UUID.randomUUID().toString().replace("-", "");
            capture.paper = paper;
            capture.createdBy = user;
            capture.createdAt = Instant.now();
            capture.expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
            capture.status = "PENDING";
            pdfCaptureRepository.persist(capture);

            ids[0] = logicalFeed.id;
            ids[1] = paper.id;
            ids[2] = sync.id;
        });

        QuarkusTransaction.requiringNew().run(() -> {
            LogicalFeed logicalFeed = LogicalFeed.findById(ids[0]);
            googleDrivePdfSyncRepository.deleteForLogicalFeed(logicalFeed);
            pdfCaptureRepository.deleteForLogicalFeed(logicalFeed);
            googleDrivePdfSyncRepository.flush();
            pdfCaptureRepository.flush();
            logicalFeed.delete();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            Assertions.assertNull(LogicalFeed.findById(ids[0]));
            Assertions.assertNull(Paper.findById(ids[1]));
            Assertions.assertNull(GoogleDrivePdfSync.findById(ids[2]));
        });
    }

    private static LogicalFeed temporaryLogicalFeed(String suffix) {
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = "Temporary lifecycle feed " + suffix;
        logicalFeed.workflowStates = QuickSetupWorkflows.KANBAN;
        logicalFeed.publicReadable = false;
        logicalFeed.notifyOnNewRssPapers = false;
        logicalFeed.publicShareToken = UUID.randomUUID().toString();
        logicalFeed.persist();
        return logicalFeed;
    }
}
