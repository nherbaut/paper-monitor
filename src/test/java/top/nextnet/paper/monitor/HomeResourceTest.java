package top.nextnet.paper.monitor;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class HomeResourceTest {

    @Inject
    LogicalFeedRepository logicalFeedRepository;

    @Test
    void homepageLoads() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("Paper Monitor"))
                .body(containsString("Recent papers"))
                .body(containsString("Open admin"));
    }

    @Test
    void adminPageLoads() {
        given()
                .when().get("/admin")
                .then()
                .statusCode(200)
                .body(containsString("Paper Monitor Admin"))
                .body(containsString("Create logical feed"))
                .body(containsString("Create feed"));
    }

    @Test
    void createFeedBindsLogicalFeedFromForm() {
        Long logicalFeedId = createLogicalFeed("Sustainability AI");

        given()
                .formParam("name", "OA Monitor Feed")
                .formParam("url", "https://example.com/feed.xml")
                .formParam("pollIntervalMinutes", "30")
                .formParam("logicalFeedId", logicalFeedId)
                .when().post("/feeds")
                .then()
                .statusCode(303);

        given()
                .when().get("/admin")
                .then()
                .statusCode(200)
                .body(containsString("OA Monitor Feed"))
                .body(containsString("Sustainability AI"));
    }

    @Test
    void createFeedRejectsUnknownLogicalFeed() {
        given()
                .formParam("name", "Broken Feed")
                .formParam("url", "https://example.com/broken.xml")
                .formParam("pollIntervalMinutes", "30")
                .formParam("logicalFeedId", "999999")
                .when().post("/feeds")
                .then()
                .statusCode(400)
                .body(equalTo("logicalFeedId does not reference an existing logical feed"));
    }

    @TestTransaction
    Long createLogicalFeed(String name) {
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = name;
        logicalFeedRepository.persist(logicalFeed);
        return logicalFeed.id;
    }
}
