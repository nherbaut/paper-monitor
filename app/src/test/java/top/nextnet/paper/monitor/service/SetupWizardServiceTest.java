package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetupWizardServiceTest {

    @Test
    void collapsesWhitespaceEquivalentQueriesAndKeepsNewestRow() {
        Map<String, Object> newest = query(12L, "TITLE-ABS-KEY( governance )", 20L);
        Map<String, Object> duplicate = query(11L, " TITLE-ABS-KEY(   governance   ) ", 19L);
        Map<String, Object> other = query(10L, "TITLE-ABS-KEY( trust )", 7L);

        List<Map<String, Object>> result = SetupWizardService.collapseDuplicateQueries(
                List.of(newest, duplicate, other));

        assertEquals(2, result.size());
        assertEquals(12L, result.getFirst().get("queryId"));
        assertEquals(10L, result.get(1).get("queryId"));
    }

    @Test
    void keepsRowsWithoutQueryTextDistinctById() {
        List<Map<String, Object>> result = SetupWizardService.collapseDuplicateQueries(
                List.of(query(2L, null, 0L), query(1L, null, 0L)));

        assertEquals(2, result.size());
    }

    private Map<String, Object> query(long id, String query, long count) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("queryId", id);
        row.put("query", query);
        row.put("count", count);
        return row;
    }
}
