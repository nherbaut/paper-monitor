package top.nextnet.paper.monitor.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import io.quarkus.runtime.Startup;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Shared presentation format; persisted and API timestamps remain ISO instants. */
@Named("dateDisplay")
@ApplicationScoped
@Startup
public class DateDisplayService {
    private final ZoneId zone;
    private final DateTimeFormatter formatter;

    public DateDisplayService(@ConfigProperty(name = "paper-monitor.time-zone") String timeZone) {
        zone = ZoneId.of(timeZone);
        formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH-mm", Locale.ROOT).withZone(zone);
    }

    public String getTimeZone() {
        return zone.getId();
    }

    public String format(Instant instant) {
        return instant == null ? "" : formatter.format(instant);
    }
}
