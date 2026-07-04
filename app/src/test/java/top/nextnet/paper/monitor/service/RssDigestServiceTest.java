package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class RssDigestServiceTest {

    @Test
    void usesEuropeParisForDigestDates() {
        assertEquals(ZoneId.of("Europe/Paris"), RssDigestService.DIGEST_ZONE);
    }
}
