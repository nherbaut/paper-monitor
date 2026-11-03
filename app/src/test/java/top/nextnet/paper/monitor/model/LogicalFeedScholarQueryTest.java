package top.nextnet.paper.monitor.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LogicalFeedScholarQueryTest {

    @Test
    void savedSourceSurvivesDescriptionChanges() {
        LogicalFeed feed = new LogicalFeed();
        feed.scholarQueryId = 8310L;
        feed.description = "A description edited by the owner";
        assertEquals(8310L, feed.sourceScholarQueryId());
    }

    @Test
    void readsSourceFromLegacySetupDescription() {
        LogicalFeed feed = new LogicalFeed();
        feed.description = "Created from MIAGE Scholar query 8310";
        assertEquals(8310L, feed.sourceScholarQueryId());
    }

    @Test
    void ignoresMissingOrMalformedLegacySources() {
        LogicalFeed feed = new LogicalFeed();
        for (String description : new String[] {null, "", "Created without an RSS source",
                "Created from MIAGE Scholar query null", "Created from MIAGE Scholar query 999999999999999999999"}) {
            feed.description = description;
            assertNull(feed.sourceScholarQueryId());
        }
    }
}
