package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.MendeleyFeedSync;

class MendeleyBackgroundSyncServiceTest {
    @Test
    void exposesPersistentProgressForTheAdminInterface() {
        MendeleyFeedSync config = new MendeleyFeedSync();
        config.id = 7L;
        config.syncStatus = "RUNNING";
        config.syncPhase = "Synchronizing papers";
        config.syncTrigger = "manual";
        config.syncCompletedActions = 3;
        config.syncTotalActions = 8;
        config.syncStartedAt = Instant.parse("2026-09-09T15:00:00Z");

        Map<String, Object> job = MendeleyBackgroundSyncService.jobView(config);

        assertEquals("RUNNING", job.get("status"));
        assertEquals(3, job.get("completed"));
        assertEquals(8, job.get("total"));
        assertTrue((Boolean) job.get("running"));
    }

    @Test
    void treatsExistingRowsWithoutAStatusAsIdle() {
        MendeleyFeedSync config = new MendeleyFeedSync();
        config.syncStatus = null;
        config.syncCompletedActions = null;
        config.syncTotalActions = null;

        Map<String, Object> job = MendeleyBackgroundSyncService.jobView(config);

        assertEquals("IDLE", job.get("status"));
        assertEquals(0, job.get("completed"));
        assertEquals(0, job.get("total"));
        assertFalse((Boolean) job.get("running"));
    }

    @Test
    void recognizesWrappedStaleSynchronizationFailures() {
        assertTrue(MendeleyBackgroundSyncService.isStaleFailure(
                new IOException("apply failed", new MendeleySyncService.StaleSyncException("changed"))));
        assertTrue(MendeleyBackgroundSyncService.isStaleFailure(
                new MendeleyApiClient.MendeleyApiException(412, "precondition failed")));
        assertFalse(MendeleyBackgroundSyncService.isStaleFailure(new IOException("network failed")));
    }
}
