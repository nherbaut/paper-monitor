package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void recognizesAnExistingFolderMembershipAsAnIdempotentSuccess() {
        assertTrue(MendeleyApiClient.isExistingFolderMembership(
                new MendeleyApiClient.MendeleyApiException(409,
                        "{\"message\":\"The document already exists in the internalFolder\"}")));
        assertFalse(MendeleyApiClient.isExistingFolderMembership(
                new MendeleyApiClient.MendeleyApiException(409, "{\"message\":\"Another conflict\"}")));
        assertFalse(MendeleyApiClient.isExistingFolderMembership(
                new MendeleyApiClient.MendeleyApiException(400,
                        "{\"message\":\"The document already exists in the internalFolder\"}")));
    }
}
