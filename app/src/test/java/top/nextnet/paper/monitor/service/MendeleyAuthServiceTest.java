package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class MendeleyAuthServiceTest {
    @Test
    void reportsWhyTheIntegrationIsUnavailableWithoutExposingCredentials() {
        assertEquals("PAPER_MONITOR_MENDELEY_ENABLED is false", service(false, "client", "secret").configurationIssue());
        assertEquals("PAPER_MONITOR_MENDELEY_CLIENT_ID is missing", service(true, "", "secret").configurationIssue());
        assertEquals("PAPER_MONITOR_MENDELEY_CLIENT_SECRET is missing", service(true, "client", "").configurationIssue());
        assertNull(service(true, "client", "secret").configurationIssue());
    }

    @Test
    void usesTheCollectionProfileMediaTypeRequiredByProfilesMe() {
        assertEquals("application/vnd.mendeley-profiles.1+json", MendeleyAuthService.PROFILE_MEDIA_TYPE);
    }

    @Test
    void createsAbsolutePublicRedirectsWithoutTrustingAnExternalReturnUrl() {
        MendeleyAuthService service = service(true, "client", "secret");

        assertEquals("https://papers.example.test/admin#mendeley", service.publicUrl("/admin#mendeley"));
        assertEquals("https://papers.example.test/admin#mendeley", service.publicUrl("http://attacker.test"));
        assertEquals("https://papers.example.test/admin#mendeley", service.publicUrl("//attacker.test"));
    }

    private MendeleyAuthService service(boolean enabled, String clientId, String clientSecret) {
        return new MendeleyAuthService(HttpClient.newHttpClient(), null, null,
                clientId, clientSecret, "all", enabled, "https://papers.example.test");
    }
}
