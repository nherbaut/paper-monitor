package top.nextnet.paper.monitor.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

public final class BuildInfo {

    private static final String UNKNOWN = "unknown";
    private static final String COMMIT = loadCommit();

    private BuildInfo() {
    }

    public static String commit() {
        return COMMIT;
    }

    public static String shortCommit() {
        return COMMIT.length() > 12 ? COMMIT.substring(0, 12) : COMMIT;
    }

    static String normalizeCommit(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[0-9a-f]{7,40}") ? normalized : UNKNOWN;
    }

    private static String loadCommit() {
        Properties properties = new Properties();
        try (InputStream input = BuildInfo.class.getResourceAsStream("/build.properties")) {
            if (input == null) {
                return UNKNOWN;
            }
            properties.load(input);
            return normalizeCommit(properties.getProperty("build.commit"));
        } catch (IOException e) {
            return UNKNOWN;
        }
    }
}
