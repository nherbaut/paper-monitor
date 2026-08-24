package top.nextnet.paper.monitor.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScholarServiceTest {

    @Test
    void parsesScholarFeeds() {
        String json = """
                [
                  {
                    "id": 24,
                    "count": 5,
                    "query": "TITLE-ABS-KEY(\\n  \\"governance\\"\\n)",
                    "lastBuildDate": "2026-08-14T17:44:46.440363",
                    "hit": 37,
                    "rss_url": "http://scholar.miage.dev/feed/24.rss"
                  }
                ]
                """;

        List<Map<String, Object>> feeds = ScholarService.parseFeeds(json);

        assertEquals(1, feeds.size());
        assertEquals(24L, feeds.getFirst().get("id"));
        assertEquals(5L, feeds.getFirst().get("count"));
        assertEquals(37L, feeds.getFirst().get("hit"));
        assertEquals("http://scholar.miage.dev/feed/24.rss", feeds.getFirst().get("rssUrl"));
    }

    @Test
    void parsesScholarHistory() {
        String json = """
                [
                  {
                    "id": 6991,
                    "query_id": 6991,
                    "query": "TITLE-ABS-KEY((\\"digital* democra*\\"))\\n",
                    "ip": "0.0.0.0",
                    "count": 104,
                    "timestamp": "2026-08-23T01:19:29.393747",
                    "fetched": false,
                    "permalink_url": "http://scholar.miage.dev/permalink/6991",
                    "rss_url": "http://scholar.miage.dev/history/6991.rss",
                    "feed_create_url": "http://scholar.miage.dev/history/6991/feed"
                  }
                ]
                """;

        List<Map<String, Object>> history = ScholarService.parseHistory(json);

        assertEquals(1, history.size());
        assertEquals(6991L, history.getFirst().get("id"));
        assertEquals(6991L, history.getFirst().get("queryId"));
        assertEquals(104L, history.getFirst().get("count"));
        assertEquals(Boolean.FALSE, history.getFirst().get("fetched"));
        assertEquals("http://scholar.miage.dev/permalink/6991", history.getFirst().get("permalinkUrl"));
        assertEquals("http://scholar.miage.dev/history/6991.rss", history.getFirst().get("rssUrl"));
        assertEquals("http://scholar.miage.dev/history/6991/feed", history.getFirst().get("feedCreateUrl"));
    }

    @Test
    void normalizesCreatedFeedResponse() {
        String json = """
                {
                  "id": 7001,
                  "query": "TITLE-ABS-KEY(\\"governance\\")",
                  "count": 12,
                  "rss_url": "http://scholar.miage.dev/feed/7001.rss"
                }
                """;

        Map<String, Object> created = ScholarService.parseCreatedFeed(json, 6991L, "https://scholar.miage.dev");

        assertEquals(7001L, created.get("id"));
        assertEquals(6991L, created.get("queryId"));
        assertEquals(12L, created.get("count"));
        assertEquals("https://scholar.miage.dev/feed/7001.rss", created.get("rssUrl"));
    }

    @Test
    void onlyUpgradesUrlsOwnedByTheConfiguredScholarHost() {
        assertEquals(
                "https://scholar.miage.dev/feed/30.rss?format=atom",
                ScholarService.normalizeScholarUrl(
                        "http://scholar.miage.dev/feed/30.rss?format=atom",
                        "https://scholar.miage.dev"));
        assertEquals(
                "http://feeds.example.org/feed/30.rss",
                ScholarService.normalizeScholarUrl(
                        "http://feeds.example.org/feed/30.rss",
                        "https://scholar.miage.dev"));
    }
}
