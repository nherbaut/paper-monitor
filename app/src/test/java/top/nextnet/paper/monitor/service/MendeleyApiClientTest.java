package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MendeleyApiClientTest {
    @Test
    void addsAConservativePageSizeToCollectionRequests() {
        assertEquals(100, MendeleyApiClient.PAGE_SIZE);
        assertEquals("https://api.mendeley.com/annotations?limit=100",
                MendeleyApiClient.paginated("https://api.mendeley.com/annotations"));
        assertEquals("https://api.mendeley.com/annotations?document_id=paper-id&limit=100",
                MendeleyApiClient.paginated("https://api.mendeley.com/annotations?document_id=paper-id"));
    }
}
