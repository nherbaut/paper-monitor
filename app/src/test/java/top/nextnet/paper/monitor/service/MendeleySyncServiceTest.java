package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.Paper;

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

    @Test
    void representsMissingProfileNameAsAnEmptyString() {
        assertEquals("", MendeleySyncService.nonNull(null));
        assertEquals("Ada Lovelace", MendeleySyncService.nonNull("Ada Lovelace"));
    }

    @Test
    void extractsDoiWhenPaperLinksAreOptional() {
        Paper paper = new Paper();
        assertNull(MendeleySyncService.localDoi(paper));

        paper.openAccessLink = "https://doi.org/10.1000/ABC";
        assertEquals("10.1000/abc", MendeleySyncService.localDoi(paper));

        paper.sourceLink = "https://example.test/article";
        assertEquals("10.1000/abc", MendeleySyncService.localDoi(paper));
    }

    @Test
    void keepsOnlyUnfinishedActionsInAResumablePreview() {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("feedId", 12L);
        preview.put("counts", Map.of("EXPORT", 2L, "PULL", 1L));
        Map<String, Object> export = Map.of("type", "EXPORT", "paperId", 1L);
        Map<String, Object> pull = Map.of("type", "PULL", "paperId", 2L);

        Map<String, Object> remaining = MendeleySyncService.withRemainingActions(preview, List.of(export, pull));

        assertEquals(12L, remaining.get("feedId"));
        assertEquals(Map.of("EXPORT", 1L, "PULL", 1L), remaining.get("counts"));
        assertEquals(List.of(export, pull), remaining.get("actions"));
    }
}
