package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void convertsMendeleyIsoTimestampToAnHttpConditionalDate() {
        assertEquals("Wed, 9 Sep 2026 14:58:27 GMT",
                MendeleyApiClient.ifUnmodifiedSince("2026-09-09T14:58:27.748789646Z"));
        assertNull(MendeleyApiClient.ifUnmodifiedSince("not-a-timestamp"));
    }

    @Test
    void recognizesAndSummarizesCloudflareBlockPages() {
        MendeleyApiClient.MendeleyApiException error = new MendeleyApiClient.MendeleyApiException(403,
                "<!DOCTYPE html><html><div id=\"cf-error-details\">Sorry, you have been blocked"
                        + "</div>Cloudflare Ray ID: <strong>a38f904d682813d3</strong></html>");

        assertTrue(MendeleyApiClient.isCloudflareBlock(error));
        assertEquals("Mendeley API HTTP 403: request blocked by Cloudflare (Ray ID a38f904d682813d3)",
                error.getMessage());
        assertFalse(error.getMessage().contains("DOCTYPE"));
    }

    @Test
    void doesNotTreatOrdinaryForbiddenResponsesAsCloudflareBlocks() {
        MendeleyApiClient.MendeleyApiException error = new MendeleyApiClient.MendeleyApiException(403,
                "{\"message\":\"Forbidden\"}");

        assertFalse(MendeleyApiClient.isCloudflareBlock(error));
        assertEquals("Mendeley API HTTP 403: {\"message\":\"Forbidden\"}", error.getMessage());
    }
}
