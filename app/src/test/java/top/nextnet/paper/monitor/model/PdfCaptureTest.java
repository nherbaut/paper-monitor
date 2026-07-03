package top.nextnet.paper.monitor.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PdfCaptureTest {

    @Test
    void reportsExpiredOnlyWhileCaptureIsUnconsumed() {
        Instant now = Instant.parse("2026-07-03T12:00:00Z");
        PdfCapture capture = new PdfCapture();
        capture.status = "ARMED";
        capture.expiresAt = now.minusSeconds(1);

        assertTrue(capture.expired(now));
        assertEquals("EXPIRED", capture.effectiveStatus(now));

        capture.status = "UPLOADED";
        capture.consumedAt = now.minusSeconds(2);

        assertFalse(capture.expired(now));
        assertEquals("UPLOADED", capture.effectiveStatus(now));
    }
}
