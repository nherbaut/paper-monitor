package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GoogleDriveAuthServiceTest {

    @Test
    void normalizesCommaSeparatedAndQuotedScopes() {
        assertEquals(
                "openid email profile https://www.googleapis.com/auth/drive",
                GoogleDriveAuthService.normalizeScopes("\"openid, email, profile, https://www.googleapis.com/auth/drive\""));
    }

    @Test
    void defaultsToFullDriveScope() {
        assertEquals(
                "openid email profile https://www.googleapis.com/auth/drive",
                GoogleDriveAuthService.normalizeScopes(""));
    }
}
