package top.nextnet.paper.monitor.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserSettingsTest {

    @Test
    void defaultsMissingOrUnknownDigestPreferencesToCombinedDelivery() {
        UserSettings settings = new UserSettings();
        settings.paperDigestMode = null;
        assertEquals(UserSettings.PAPER_DIGEST_DAILY, settings.effectivePaperDigestMode());
        assertTrue(settings.dailyPaperDigestSelected());

        settings.paperDigestMode = "UNKNOWN";
        assertEquals(UserSettings.PAPER_DIGEST_DAILY, settings.effectivePaperDigestMode());
    }

    @Test
    void supportsSeparateEmailsPerPaperFeed() {
        UserSettings settings = new UserSettings();
        settings.paperDigestMode = UserSettings.PAPER_DIGEST_PER_FEED;
        assertEquals(UserSettings.PAPER_DIGEST_PER_FEED, settings.effectivePaperDigestMode());
        assertTrue(settings.perFeedPaperDigestSelected());
    }
}
