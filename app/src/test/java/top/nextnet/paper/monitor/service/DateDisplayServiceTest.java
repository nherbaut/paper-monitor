package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DateTimeException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DateDisplayServiceTest {
    @Test
    void convertsDatesWithDaylightSavingAndYearRollover() {
        var display = new DateDisplayService("Europe/Paris");
        assertEquals("2027-01-01 00-30", display.format(Instant.parse("2026-12-31T23:30:59.123Z")));
        assertEquals("2026-07-01 14-49", display.format(Instant.parse("2026-07-01T12:49:21Z")));
        assertEquals("2026-03-29 01-30", display.format(Instant.parse("2026-03-29T00:30:00Z")));
        assertEquals("2026-03-29 03-30", display.format(Instant.parse("2026-03-29T01:30:00Z")));
    }

    @Test
    void supportsConfiguredZonesAndMissingDates() {
        var display = new DateDisplayService("UTC");
        assertEquals("UTC", display.getTimeZone());
        assertEquals("2026-07-01 12-49", display.format(Instant.parse("2026-07-01T12:49:21Z")));
        assertEquals("", display.format(null));
        assertThrows(DateTimeException.class, () -> new DateDisplayService("Invalid/Zone"));
    }
}
