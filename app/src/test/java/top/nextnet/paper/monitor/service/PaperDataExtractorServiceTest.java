package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PaperDataExtractorServiceTest {

    @Test
    void extractsFastApiErrorDetail() {
        assertEquals("Create a derivation before creating revisions",
                PaperDataExtractorService.upstreamErrorMessage(
                        400, "{\"detail\":\"Create a derivation before creating revisions\"}"));
    }

    @Test
    void retainsPlainTextAndProvidesFallback() {
        assertEquals("Invalid review design",
                PaperDataExtractorService.upstreamErrorMessage(422, " Invalid review design "));
        assertEquals("Paper Data Extractor returned 502",
                PaperDataExtractorService.upstreamErrorMessage(502, ""));
    }
}
