package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MendeleySyncServiceTest {
    @Test
    void resolvesMappedFolderMembershipsToWorkflowStates() {
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("SCREENING/INCLUDED", "folder-a");
        mappings.put("SCREENING/EXCLUDED", "folder-b");

        assertEquals(List.of("SCREENING/INCLUDED"),
                MendeleySyncService.remoteStates(Set.of("folder-a", "unrelated"), mappings));
        assertEquals(List.of("SCREENING/INCLUDED", "SCREENING/EXCLUDED"),
                MendeleySyncService.remoteStates(Set.of("folder-a", "folder-b"), mappings));
    }

    @Test
    void mergesTagsWithoutDroppingEitherSide() {
        assertEquals("local, shared, remote",
                MendeleySyncService.mergeTags("local, shared", List.of("shared", "remote")));
    }

    @Test
    void fingerprintsAreStableAndSensitiveToChanges() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("title", "Paper"); first.put("state", "SCREENING");
        Map<String, Object> same = new LinkedHashMap<>(first);
        Map<String, Object> changed = new LinkedHashMap<>(first); changed.put("state", "INCLUDED");

        assertEquals(MendeleySyncService.fingerprint(first), MendeleySyncService.fingerprint(same));
        assertNotEquals(MendeleySyncService.fingerprint(first), MendeleySyncService.fingerprint(changed));
    }
}
