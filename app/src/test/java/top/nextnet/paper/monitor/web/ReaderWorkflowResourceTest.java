package top.nextnet.paper.monitor.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PaperEvent;
import top.nextnet.paper.monitor.model.UserSession;
import top.nextnet.paper.monitor.service.AuthService;

@QuarkusTest
class ReaderWorkflowResourceTest {
    private static final String ABSTRACT = "Evidence, with \"quoted findings\".\nA second line with café and 漢字.";
    @Inject AuthService auth;
    private Long userId;
    private Long feedId;
    private Long sourceId;
    private String token;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser user = new AppUser();
            user.username = "reader-test-" + UUID.randomUUID();
            user.authProvider = "LOCAL";
            user.emailVerified = true;
            user.approved = true;
            user.persist();
            userId = user.id;
            token = auth.createSession(user).token;
            LogicalFeed feed = new LogicalFeed();
            feed.name = "Reader regression " + UUID.randomUUID();
            feed.owner = user;
            feed.workflowStates = """
                    version: 2
                    initial_state: NEW
                    states:
                      - id: NEW
                      - id: TODO/ELIGIBLE
                        group: TODO
                      - id: TODO/RECHECK
                        group: TODO
                      - id: DISCARDED
                    """;
            feed.persist();
            feedId = feed.id;
            Feed source = new Feed();
            source.name = "Manual test source";
            source.url = "upload://reader-test";
            source.logicalFeed = feed;
            source.persist();
            sourceId = source.id;
            for (int i = 0; i < 35; i++) paper("Needle evidence " + i, "TODO/ELIGIBLE", "review", LocalDate.of(2026, 1, 2), ABSTRACT);
            paper("Needle in another state", "NEW", "review", LocalDate.of(2026, 1, 2), null);
            paper("Needle before the date filter", "NEW", "review", LocalDate.of(2024, 1, 2), null);
            paper("Unrelated paper", "TODO/RECHECK", "other", LocalDate.of(2026, 1, 2), null);
        });
    }

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            PaperEvent.delete("paper.logicalFeed.id", feedId);
            Paper.delete("logicalFeed.id", feedId);
            LogicalFeed feed = LogicalFeed.findById(feedId);
            if (feed != null) feed.delete();
            UserSession.delete("user.id", userId);
            AppUser.deleteById(userId);
        });
    }

    @Test
    void csvIncludesAnEscapedMultilineAbstractAndPreservesExistingColumns() {
        String csv = given().cookie(auth.sessionCookieName(), token).queryParam("logicalFeedId", feedId)
                .queryParam("format", "csv").get("/exports/tab")
                .then().statusCode(200).extract().asString();
        assertTrue(csv.startsWith("title,authors,publication_date,venue,status,discovered_at,source_link,open_access_link,feed,paper_feed,tags,has_pdf,abstract\n"));
        assertTrue(csv.contains("\"" + ABSTRACT.replace("\"", "\"\"") + "\""));
        assertTrue(csv.contains("\"Needle in another state\""));
    }

    @Test
    void notesAndReportMarkdownAlsoIncludeTheAbstract() {
        for (String kind : List.of("notes", "report")) {
            String markdown = given().cookie(auth.sessionCookieName(), token).queryParam("logicalFeedId", feedId)
                    .queryParam("kind", kind).queryParam("format", "md").get("/exports/tab")
                    .then().statusCode(200).extract().asString();
            assertTrue(markdown.contains("quoted findings"));
            assertTrue(markdown.contains("A second line with café and 漢字."));
            assertTrue(markdown.contains("No abstract available."));
        }
    }

    @Test
    void searchCountsCoverEveryPageAndEveryStateWithTheSameDateFilter() {
        Map<String, Object> page = given().cookie(auth.sessionCookieName(), token)
                .queryParam("logicalFeedId", feedId).queryParam("status", "TODO/ELIGIBLE")
                .queryParam("search", "needle date:>2025").get("/api/papers/browser")
                .then().statusCode(200).extract().jsonPath().getMap("");
        assertEquals(35, ((Number) page.get("total")).intValue());
        assertEquals(30, ((List<?>) page.get("items")).size());
        List<Map<String, Object>> facets = given().cookie(auth.sessionCookieName(), token)
                .queryParam("logicalFeedId", feedId).queryParam("status", "TODO/ELIGIBLE")
                .queryParam("search", "needle date:>2025").get("/api/papers/browser/facets")
                .then().statusCode(200).extract().jsonPath().getList("");
        assertEquals(35, facet(facets, "state:TODO/ELIGIBLE"));
        assertEquals(1, facet(facets, "state:NEW"));
        assertEquals(0, facet(facets, "state:TODO/RECHECK"));
        List<Map<String, Object>> empty = given().cookie(auth.sessionCookieName(), token)
                .queryParam("logicalFeedId", feedId).queryParam("search", "does-not-exist")
                .get("/api/papers/browser/facets").then().statusCode(200).extract().jsonPath().getList("");
        assertTrue(empty.stream().allMatch(row -> ((Number) row.get("count")).intValue() == 0));
    }

    @Test
    void authenticatedBrowserChecks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("paper-monitor.browser-check"));
        ProcessBuilder builder = new ProcessBuilder("node", "src/test/browser/reader-workflow.cjs", feedId.toString());
        builder.environment().put("PAPER_MONITOR_TEST_COOKIE", token);
        builder.inheritIO();
        assertEquals(0, builder.start().waitFor());
    }

    private int facet(List<Map<String, Object>> facets, String key) {
        return facets.stream().filter(row -> key.equals(row.get("key")))
                .mapToInt(row -> ((Number) row.get("count")).intValue()).findFirst().orElseThrow();
    }

    private void paper(String title, String state, String tags, LocalDate date, String summary) {
        Paper paper = new Paper();
        paper.title = title;
        paper.sourceLink = "https://example.org/" + UUID.randomUUID();
        paper.discoveredAt = Instant.now();
        paper.publishedOn = date;
        paper.status = state;
        paper.tags = tags;
        paper.summary = summary;
        paper.logicalFeed = LogicalFeed.findById(feedId);
        paper.feed = Feed.findById(sourceId);
        paper.persist();
    }
}
