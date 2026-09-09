package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void exportsCommaSeparatedAuthorsAsIndividualMendeleyAuthors() {
        List<Map<String, String>> authors = MendeleySyncService.mendeleyAuthors(
                "Elnaz Azmi, Khadijeh Alibabaei, В. Козлов, Tjerk Krijger");

        assertEquals(List.of(
                Map.of("first_name", "Elnaz", "last_name", "Azmi"),
                Map.of("first_name", "Khadijeh", "last_name", "Alibabaei"),
                Map.of("first_name", "В.", "last_name", "Козлов"),
                Map.of("first_name", "Tjerk", "last_name", "Krijger")), authors);
    }

    @Test
    void removesSourceAffiliationsAndBoundsMendeleyAuthorComponents() {
        String oversizedFirstName = "x".repeat(300);

        List<Map<String, String>> authors = MendeleySyncService.mendeleyAuthors(
                "Author links open overlay panel " + oversizedFirstName + " Family a, Jane Doe b…Gergely Sipos s");

        assertEquals(3, authors.size());
        assertEquals("Family", authors.get(0).get("last_name"));
        assertEquals("Jane", authors.get(1).get("first_name"));
        assertEquals("Doe", authors.get(1).get("last_name"));
        assertEquals("Gergely", authors.get(2).get("first_name"));
        assertEquals("Sipos", authors.get(2).get("last_name"));
        assertTrue(authors.get(0).get("first_name").codePointCount(0,
                authors.get(0).get("first_name").length()) <= 255);
    }

    @Test
    void treatsEmptyMendeleyValuesAndAnUnassignedRemoteStateAsEquivalent() {
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("title", "Detection of Gender and Age");
        local.put("doi", "10.1109/iitcee67948.2026.11394555");
        local.put("authors", "Mr. Shrikanth N G, Priyanka R B");
        local.put("year", 2026);
        local.put("tags", null);
        local.put("notes", null);
        local.put("state", "INCLUDED/DATABASE_INCLUDED_IN_REVIEW");
        local.put("pdf", null);
        Map<String, Object> remote = new LinkedHashMap<>(local);
        remote.put("tags", List.of());
        remote.put("state", null);
        remote.put("pdf", false);

        assertTrue(MendeleySyncService.equivalentDocumentValues(local, remote));

        remote.put("title", "A genuinely different title");
        assertFalse(MendeleySyncService.equivalentDocumentValues(local, remote));
    }
}
