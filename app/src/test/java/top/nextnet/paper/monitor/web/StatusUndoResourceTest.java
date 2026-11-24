package top.nextnet.paper.monitor.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.*;
import top.nextnet.paper.monitor.repo.PaperEventRepository;
import top.nextnet.paper.monitor.service.*;

@QuarkusTest
class StatusUndoResourceTest {
    @Inject AuthService auth;
    @Inject LogicalFeedAccessService access;
    @Inject PaperEventService eventService;
    @Inject PaperEventRepository events;
    @Inject BackupService backups;
    private Long ownerId, readerId, feedId, paperId;
    private String ownerToken, readerToken;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser owner = user("undo-owner-");
            AppUser reader = user("undo-reader-");
            ownerId = owner.id;
            readerId = reader.id;
            ownerToken = auth.createSession(owner).token;
            readerToken = auth.createSession(reader).token;
            LogicalFeed feed = new LogicalFeed();
            feed.owner = owner;
            feed.name = "Status undo review " + UUID.randomUUID();
            feed.workflowStates = QuickSetupWorkflows.KANBAN;
            feed.persist();
            feedId = feed.id;
            access.grant(feed, reader, "READ");
            Feed rss = new Feed();
            rss.logicalFeed = feed;
            rss.name = "Undo source";
            rss.url = "upload://undo-" + UUID.randomUUID();
            rss.persist();
            Paper paper = new Paper();
            paper.logicalFeed = feed;
            paper.feed = rss;
            paper.title = "A paper with an accidental status change";
            paper.sourceLink = "https://example.org/undo-" + UUID.randomUUID();
            paper.discoveredAt = Instant.now();
            paper.status = "NEW";
            paper.notes = "Keep these research notes.";
            paper.tags = "evidence";
            paper.persist();
            paperId = paper.id;
        });
    }

    private AppUser user(String prefix) {
        AppUser user = new AppUser();
        user.username = prefix + UUID.randomUUID();
        user.authProvider = "LOCAL";
        user.approved = true;
        user.emailVerified = true;
        user.persist();
        return user;
    }

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            PaperEvent.delete("paper.logicalFeed.id", feedId);
            LogicalFeedAccessGrant.delete("logicalFeed.id", feedId);
            LogicalFeed.deleteById(feedId);
            for (Long id : List.of(ownerId, readerId)) {
                UserSession.delete("user.id", id);
                AppUser.deleteById(id);
            }
        });
    }

    private Long change(String target) {
        given().cookie(auth.sessionCookieName(), ownerToken).formParam("status", target)
                .post("/papers/" + paperId + "/status").then().statusCode(204);
        return QuarkusTransaction.requiringNew().call(() -> events.find("paper.id = ?1 and type = 'STATE_CHANGED' order by id desc", paperId)
                .firstResult().id);
    }

    private io.restassured.response.Response undo(Long eventId, String token) {
        var request = given().redirects().follow(false);
        if (token != null) request.cookie(auth.sessionCookieName(), token);
        return request.post("/logs/events/" + eventId + "/undo");
    }

    @Test
    void restoresStatusKeepsOtherEditsAndWritesAnAuditEventOnlyOnce() {
        Long eventId = change("TODO");
        QuarkusTransaction.requiringNew().run(() -> {
            Paper paper = Paper.findById(paperId);
            paper.notes = "Newer research notes.";
            paper.tags = "new-evidence";
            eventService.log(paper, "NOTES_CHANGED", "Updated notes");
        });
        assertTrue(given().cookie(auth.sessionCookieName(), ownerToken).get("/logs").then().statusCode(200)
                .extract().asString().contains("action=\"/logs/events/" + eventId + "/undo\""));
        var response = undo(eventId, ownerToken).then().statusCode(303).extract().response();
        assertTrue(response.header("Location").contains("info="));
        QuarkusTransaction.requiringNew().run(() -> {
            Paper paper = Paper.findById(paperId);
            assertEquals("NEW", paper.status);
            assertEquals("Newer research notes.", paper.notes);
            assertEquals("new-evidence", paper.tags);
            assertNotNull(events.findById(eventId).revertedAt);
            assertEquals(1L, events.count("paper.id = ?1 and type = 'STATE_REVERTED'", paperId));
            assertNotNull(paper.updatedAt);
        });
        assertTrue(undo(eventId, ownerToken).then().statusCode(303).extract().header("Location").contains("error="));
        QuarkusTransaction.requiringNew().run(() -> assertEquals(1L,
                events.count("paper.id = ?1 and type = 'STATE_REVERTED'", paperId)));
    }

    @Test
    void preventsUndoOfAnOlderChangeEvenWhenTheStatusLaterMatchesAgain() {
        Long old = change("TODO");
        change("NEW");
        change("TODO");
        assertTrue(undo(old, ownerToken).then().statusCode(303).extract().header("Location").contains("error="));
        QuarkusTransaction.requiringNew().run(() -> assertEquals("TODO", ((Paper) Paper.findById(paperId)).status));
    }

    @Test
    void enforcesFeedAdminAccessAndRejectsOtherEventTypes() {
        Long eventId = change("TODO");
        undo(eventId, readerToken).then().statusCode(403);
        undo(eventId, null).then().statusCode(401);
        String html = given().cookie(auth.sessionCookieName(), readerToken).get("/logs")
                .then().statusCode(200).extract().asString();
        assertFalse(html.contains("action=\"/logs/events/" + eventId + "/undo\""));
        Long note = QuarkusTransaction.requiringNew().call(() -> eventService.log(Paper.findById(paperId), "NOTE_VIEWED", "Viewed").id);
        assertTrue(undo(note, ownerToken).then().statusCode(303).extract().header("Location").contains("error="));
        undo(Long.MAX_VALUE, ownerToken).then().statusCode(404);
    }

    @Test
    void restoresClassificationReasonsEvenWhenTheWorkflowHasNoReverseEdge() {
        QuarkusTransaction.requiringNew().run(() -> {
            LogicalFeed feed = LogicalFeed.findById(feedId);
            feed.workflowStates = criteriaWorkflow();
            Paper paper = Paper.findById(paperId);
            paper.status = "EXCLUDED";
            paper.setEligibilityExclusionCriteriaIds(List.of("OUT_OF_SCOPE"));
            paper.eligibilityExclusionNotes = "Prior classification reason";
        });
        Long eventId = change("NEW");
        undo(eventId, ownerToken).then().statusCode(303);
        QuarkusTransaction.requiringNew().run(() -> {
            Paper paper = Paper.findById(paperId);
            assertEquals("EXCLUDED", paper.status);
            assertEquals(List.of("OUT_OF_SCOPE"), paper.eligibilityExclusionCriteriaIds());
            assertEquals("Prior classification reason", paper.eligibilityExclusionNotes);
        });
    }

    @Test
    void rejectsRemovedStatesChangedReasonsAndRemoteSynchronization() {
        Long eventId = change("TODO");
        QuarkusTransaction.requiringNew().run(() -> {
            Paper paper = Paper.findById(paperId);
            paper.setEligibilityExclusionCriteriaIds(List.of("later-edit"));
        });
        assertTrue(undo(eventId, ownerToken).then().statusCode(303).extract().header("Location").contains("error="));
        QuarkusTransaction.requiringNew().run(() -> {
            Paper paper = Paper.findById(paperId);
            paper.setEligibilityExclusionCriteriaIds(List.of());
            LogicalFeed feed = LogicalFeed.findById(feedId);
            feed.workflowStates = "TODO|DONE";
        });
        assertTrue(undo(eventId, ownerToken).then().statusCode(303).extract().header("Location").contains("error="));
        QuarkusTransaction.requiringNew().run(() -> {
            LogicalFeed feed = LogicalFeed.findById(feedId);
            feed.workflowStates = QuickSetupWorkflows.KANBAN;
            eventService.log(Paper.findById(paperId), "MENDELEY_PULL", "Remote update");
        });
        assertTrue(undo(eventId, ownerToken).then().statusCode(303).extract().header("Location").contains("error="));
    }

    @Test
    void supportsSimpleLegacyEventsButRejectsLegacyEventsMissingRequiredReasons() {
        Long legacy = QuarkusTransaction.requiringNew().call(() -> {
            Paper paper = Paper.findById(paperId);
            paper.status = "TODO";
            return eventService.log(paper, "STATE_CHANGED", "NEW -> TODO").id;
        });
        assertTrue(undo(legacy, ownerToken).then().statusCode(303).extract().header("Location").contains("info="));
        Long unsupported = QuarkusTransaction.requiringNew().call(() -> {
            LogicalFeed feed = LogicalFeed.findById(feedId);
            feed.workflowStates = criteriaWorkflow();
            return eventService.log(Paper.findById(paperId), "STATE_CHANGED", "EXCLUDED -> NEW").id;
        });
        assertTrue(undo(unsupported, ownerToken).then().statusCode(303).extract().header("Location").contains("error="));
    }

    @Test
    void rejectsSavedReasonsThatWereRemovedFromTheWorkflow() {
        QuarkusTransaction.requiringNew().run(() -> {
            LogicalFeed feed = LogicalFeed.findById(feedId);
            feed.workflowStates = criteriaWorkflow();
            Paper paper = Paper.findById(paperId);
            paper.status = "EXCLUDED";
            paper.setEligibilityExclusionCriteriaIds(List.of("OUT_OF_SCOPE"));
        });
        Long eventId = change("NEW");
        QuarkusTransaction.requiringNew().run(() -> {
            LogicalFeed feed = LogicalFeed.findById(feedId);
            feed.workflowStates = criteriaWorkflow().replace("OUT_OF_SCOPE", "OTHER_REASON");
        });
        assertTrue(undo(eventId, ownerToken).then().statusCode(303).extract().header("Location").contains("error="));
        QuarkusTransaction.requiringNew().run(() -> {
            Paper paper = Paper.findById(paperId);
            assertEquals("NEW", paper.status);
            assertTrue(paper.eligibilityExclusionCriteriaIds().isEmpty());
            assertNull(events.findById(eventId).revertedAt);
        });
    }

    @Test
    void includesStatusSnapshotsAndUndoMarkersInBackups() {
        Long eventId = change("TODO");
        undo(eventId, ownerToken).then().statusCode(303);
        QuarkusTransaction.requiringNew().run(() -> {
            try {
                byte[] archive = backups.exportZip(List.of(LogicalFeed.findById(feedId)), AppUser.findById(ownerId));
                try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
                    assertEquals("data.json", zip.getNextEntry().getName());
                    var root = (java.util.Map<?, ?>) JsonCodec.parse(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    var rows = (List<?>) root.get("paperEvents");
                    var change = rows.stream().map(row -> (java.util.Map<?, ?>) row)
                            .filter(row -> "STATE_CHANGED".equals(row.get("type"))).findFirst().orElseThrow();
                    assertEquals("NEW", StatusChangeUndoService.StatusSnapshot.fromJson((String) change.get("statusBeforeJson")).status());
                    assertEquals("TODO", StatusChangeUndoService.StatusSnapshot.fromJson((String) change.get("statusAfterJson")).status());
                    assertNotNull(change.get("revertedAt"));
                    assertTrue(rows.stream().anyMatch(row -> "STATE_REVERTED".equals(((java.util.Map<?, ?>) row).get("type"))));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void concurrentUndoRequestsCreateOnlyOneReversal() throws Exception {
        Long eventId = change("TODO");
        var first = CompletableFuture.supplyAsync(() -> undo(eventId, ownerToken).statusCode());
        var second = CompletableFuture.supplyAsync(() -> undo(eventId, ownerToken).statusCode());
        assertEquals(303, first.get());
        assertEquals(303, second.get());
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals("NEW", ((Paper) Paper.findById(paperId)).status);
            assertEquals(1L, events.count("paper.id = ?1 and type = 'STATE_REVERTED'", paperId));
        });
    }

    @Test
    void browserChecks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("paper-monitor.undo-browser-check"));
        Long eventId = change("TODO");
        ProcessBuilder builder = new ProcessBuilder("node", "src/test/browser/status-undo.cjs", eventId.toString());
        builder.environment().put("PAPER_MONITOR_TEST_COOKIE", ownerToken);
        builder.inheritIO();
        assertEquals(0, builder.start().waitFor());
    }

    private String criteriaWorkflow() {
        return """
                version: 2
                initial_state: EXCLUDED
                states:
                  - id: EXCLUDED
                    requires:
                      exclusion_criteria:
                        taxonomy: EXCLUSION
                        min: 1
                  - id: NEW
                transitions:
                  - from: EXCLUDED
                    to: [NEW]
                taxonomies:
                  EXCLUSION:
                    label: Exclusion criteria
                    values:
                      - id: OUT_OF_SCOPE
                        label: Out of scope
                """;
    }
}
