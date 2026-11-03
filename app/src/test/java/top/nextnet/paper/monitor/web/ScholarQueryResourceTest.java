package top.nextnet.paper.monitor.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.PaperFeedSetupDraft;
import top.nextnet.paper.monitor.model.UserSession;
import top.nextnet.paper.monitor.service.AuthService;
import top.nextnet.paper.monitor.service.QuickSetupWorkflows;
import top.nextnet.paper.monitor.service.ScholarService;
import top.nextnet.paper.monitor.service.SetupWizardService;

@QuarkusTest
class ScholarQueryResourceTest {

    private static final long QUERY_ID = 8310L;
    private static final String QUERY = "TITLE-ABS-KEY(\n  governance AND \"<script>alert('example')</script>\"\n)";
    private static final String PERMALINK = "https://scholar.example.test/permalink/8310";

    @Inject
    AuthService authService;

    @Inject
    SetupWizardService setupWizardService;

    private Long userId;
    private String sessionToken;
    private List<Map<String, Object>> history;
    private final AtomicInteger historyRequests = new AtomicInteger();

    @BeforeEach
    void setUp() {
        history = List.of(Map.of("queryId", QUERY_ID, "query", QUERY));
        historyRequests.set(0);
        ScholarService scholar = new ScholarService("https://scholar.example.test/", "unused", "unused") {
            @Override
            public List<Map<String, Object>> history() {
                historyRequests.incrementAndGet();
                return history;
            }
        };
        QuarkusMock.installMockForType(scholar, ScholarService.class);
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = new AppUser();
            user.username = "scholar-query-" + UUID.randomUUID();
            user.authProvider = "LOCAL";
            user.approved = true;
            user.persist();
            userId = user.id;
            sessionToken = authService.createSession(user).token;
        });
    }

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            PaperFeedSetupDraft.delete("user.id", userId);
            List<LogicalFeed> feeds = LogicalFeed.find("owner.id", userId).list();
            feeds.forEach(LogicalFeed::delete);
            UserSession.delete("user.id", userId);
            AppUser.deleteById(userId);
        });
    }

    @Test
    void setupRetainsFullQueryAfterDeletingAuthenticatedDraft() {
        String query = QUERY + "\n" + "long query clause ".repeat(100);
        String draftId = UUID.randomUUID().toString();
        Long feedId = QuarkusTransaction.requiringNew().call(() -> {
            AppUser user = AppUser.findById(userId);
            PaperFeedSetupDraft draft = new PaperFeedSetupDraft();
            draft.id = draftId;
            draft.user = user;
            draft.title = "Scholar setup " + UUID.randomUUID();
            draft.scholarQueryId = QUERY_ID;
            draft.scholarQuery = query;
            draft.rssUrl = "https://scholar.example.test/feed/42.rss";
            draft.previewConfirmed = true;
            draft.workflowType = "MIAGE";
            draft.expiresAt = Instant.now().plus(1, ChronoUnit.DAYS);
            draft.persist();
            return setupWizardService.complete(user, draftId, null).logicalFeedId();
        });
        QuarkusTransaction.requiringNew().run(() -> {
            LogicalFeed feed = LogicalFeed.findById(feedId);
            assertEquals(QUERY_ID, feed.scholarQueryId);
            assertEquals(query, feed.scholarQuery);
            assertNull(PaperFeedSetupDraft.findById(draftId));
        });
        var section = Jsoup.parse(adminPage()).selectFirst("[aria-label='Source MIAGE Scholar query']");
        assertEquals(query, section.selectFirst("pre").wholeText());
        assertEquals(PERMALINK, section.selectFirst("a").attr("href"));
        assertTrue(section.select("script").isEmpty(), "Query text must be HTML escaped");
        assertEquals(0, historyRequests.get(), "Rendering saved queries must not call Scholar");
    }

    @Test
    void legacyFeedShowsPermalinkAndCanRetrieveAndPersistQueryText() {
        Long feedId = createFeed(true);
        var before = Jsoup.parse(adminPage()).selectFirst("[aria-label='Source MIAGE Scholar query']");
        assertEquals(PERMALINK, before.selectFirst("a").attr("href"));
        assertEquals("/logical-feeds/" + feedId + "/scholar-query/retrieve", before.selectFirst("form").attr("action"));
        assertEquals(0, historyRequests.get());

        given().cookie(authService.sessionCookieName(), sessionToken).redirects().follow(false)
                .when().post("/logical-feeds/" + feedId + "/scholar-query/retrieve")
                .then().statusCode(303);

        QuarkusTransaction.requiringNew().run(() -> {
            LogicalFeed feed = LogicalFeed.findById(feedId);
            assertEquals(QUERY_ID, feed.scholarQueryId);
            assertEquals(QUERY, feed.scholarQuery);
        });
        var after = Jsoup.parse(adminPage()).selectFirst("[aria-label='Source MIAGE Scholar query']");
        assertEquals(QUERY, after.selectFirst("pre").wholeText());
        assertTrue(after.select("form").isEmpty());
        assertEquals(1, historyRequests.get());
    }

    @Test
    void unavailableHistoryLeavesLegacyMetadataIntactAndExplainsHowToOpenQuery() {
        Long feedId = createFeed(true);
        history = List.of(Map.of("queryId", 42L, "query", "A different query"));
        String location = given().cookie(authService.sessionCookieName(), sessionToken).redirects().follow(false)
                .when().post("/logical-feeds/" + feedId + "/scholar-query/retrieve")
                .then().statusCode(303).extract().header("Location");
        assertTrue(location.contains("error="));
        assertTrue(location.endsWith("#feeds/" + feedId + "/configuration"));
        QuarkusTransaction.requiringNew().run(() -> {
            LogicalFeed feed = LogicalFeed.findById(feedId);
            assertNull(feed.scholarQuery);
            assertNull(feed.scholarQueryId);
            assertEquals(QUERY_ID, feed.sourceScholarQueryId());
        });
        assertTrue(adminPage().contains(PERMALINK));
    }

    @Test
    void anotherUserCannotRetrieveQueryText() {
        Long feedId = createFeed(true);
        String otherToken = QuarkusTransaction.requiringNew().call(() -> {
            AppUser other = new AppUser();
            other.username = "other-scholar-user-" + UUID.randomUUID();
            other.authProvider = "LOCAL";
            other.approved = true;
            other.persist();
            return authService.createSession(other).token;
        });
        try {
            given().cookie(authService.sessionCookieName(), otherToken).redirects().follow(false)
                    .when().post("/logical-feeds/" + feedId + "/scholar-query/retrieve")
                    .then().statusCode(403);
            assertEquals(0, historyRequests.get());
            String html = given().cookie(authService.sessionCookieName(), otherToken)
                    .when().get("/admin").then().statusCode(200).extract().asString();
            assertFalse(html.contains(PERMALINK));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                UserSession session = UserSession.find("token", otherToken).firstResult();
                Long otherId = session.user.id;
                session.delete();
                AppUser.deleteById(otherId);
            });
        }
    }

    @Test
    void feedsWithoutScholarSourceDoNotShowQuerySection() {
        createFeed(false);
        assertTrue(Jsoup.parse(adminPage()).select("[aria-label='Source MIAGE Scholar query']").isEmpty());
    }

    private Long createFeed(boolean legacy) {
        return QuarkusTransaction.requiringNew().call(() -> {
            LogicalFeed feed = new LogicalFeed();
            feed.name = "Scholar query test " + UUID.randomUUID();
            feed.description = legacy ? "Created from MIAGE Scholar query 8310" : "Created without an RSS source";
            feed.owner = AppUser.findById(userId);
            feed.workflowStates = QuickSetupWorkflows.KANBAN;
            feed.persist();
            return feed.id;
        });
    }

    private String adminPage() {
        return given().cookie(authService.sessionCookieName(), sessionToken)
                .when().get("/admin").then().statusCode(200).extract().asString();
    }
}
