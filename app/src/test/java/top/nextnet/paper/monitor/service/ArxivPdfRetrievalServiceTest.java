package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArxivPdfRetrievalServiceTest {

    @Test
    void parentStateIncludesItsChildren() {
        assertTrue(ArxivPdfRetrievalService.stateMatches("SCREENING", "SCREENING"));
        assertTrue(ArxivPdfRetrievalService.stateMatches("SCREENING/TITLE", "SCREENING"));
        assertTrue(ArxivPdfRetrievalService.stateMatches("SCREENING/ABSTRACT", "SCREENING"));
        assertFalse(ArxivPdfRetrievalService.stateMatches("INCLUDED", "SCREENING"));
    }

    @Test
    void leafStateMatchesExactly() {
        assertTrue(ArxivPdfRetrievalService.stateMatches("SCREENING/TITLE", "SCREENING/TITLE"));
        assertFalse(ArxivPdfRetrievalService.stateMatches("SCREENING/ABSTRACT", "SCREENING/TITLE"));
        assertFalse(ArxivPdfRetrievalService.stateMatches("SCREENING/TITLE/EXTRA", "SCREENING/TITLE"));
    }

    @Test
    void stateMatchingNormalizesCaseAndWhitespace() {
        assertTrue(ArxivPdfRetrievalService.stateMatches(" screening/title ", "SCREENING/TITLE"));
        assertFalse(ArxivPdfRetrievalService.stateMatches(null, "SCREENING"));
        assertFalse(ArxivPdfRetrievalService.stateMatches("SCREENING", null));
    }
}
