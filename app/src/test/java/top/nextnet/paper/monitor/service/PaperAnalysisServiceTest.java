package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaperAnalysisServiceTest {

    @Test
    void appendsStructuredAbstractWithoutReplacingExistingNotes() {
        String notes = PaperAnalysisService.appendStructuredAbstract(
                "# Existing notes\n\nKeep this.",
                "INTRODUCTION: Context.\n\nMETHODS: Method.",
                Instant.parse("2026-09-14T10:15:30Z"));

        assertTrue(notes.startsWith("# Existing notes\n\nKeep this."));
        assertTrue(notes.contains("## OpenAI structured abstract"));
        assertTrue(notes.contains("_Generated 2026-09-14T10:15:30Z_"));
        assertTrue(notes.endsWith("METHODS: Method."));
    }

    @Test
    void createsStructuredAbstractAsTheFirstNotesSection() {
        String notes = PaperAnalysisService.appendStructuredAbstract(
                null, "RESULTS: Result.", Instant.parse("2026-09-14T10:15:30Z"));

        assertEquals("## OpenAI structured abstract\n\n"
                + "_Generated 2026-09-14T10:15:30Z_\n\nRESULTS: Result.", notes);
    }
}
