package top.nextnet.paper.monitor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

class HomeResourceGrayLiteratureTest {

    @Test
    void canonicalizesHttpUrlsForDuplicateDetection() {
        assertEquals(
                "https://example.org/report?q=one",
                HomeResource.normalizeGrayLiteratureUrl(" HTTPS://Example.ORG:443/report?q=one#section "));
        assertEquals(
                "http://example.org/",
                HomeResource.normalizeGrayLiteratureUrl("http://Example.org:80"));
    }

    @Test
    void rejectsNonWebAndCredentialedUrls() {
        assertThrows(WebApplicationException.class,
                () -> HomeResource.normalizeGrayLiteratureUrl("file:///tmp/report.pdf"));
        assertThrows(WebApplicationException.class,
                () -> HomeResource.normalizeGrayLiteratureUrl("https://user:secret@example.org/report"));
    }
}
