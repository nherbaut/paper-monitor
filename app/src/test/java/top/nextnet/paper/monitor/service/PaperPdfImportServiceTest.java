package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.Paper;

class PaperPdfImportServiceTest {

    private final PaperPdfImportService service = new PaperPdfImportService(null);

    @Test
    void resolvesModernArxivDoiUrlToPdf() {
        Paper paper = new Paper();
        paper.sourceLink = "https://doi.org/10.48550/arXiv.2603.26487";

        assertEquals("https://arxiv.org/pdf/2603.26487.pdf",
                service.supportedPdfUrl(paper).orElseThrow());
    }

    @Test
    void resolvesBareAndLegacyArxivDois() {
        assertEquals("https://arxiv.org/pdf/2509.01234v2.pdf",
                service.supportedPdfUrl("doi:10.48550/arXiv.2509.01234v2").orElseThrow());
        assertEquals("https://arxiv.org/pdf/hep-th/9901001.pdf",
                service.supportedPdfUrl("10.48550/arXiv.hep-th/9901001").orElseThrow());
    }

    @Test
    void ignoresMissingAndUnrelatedLinks() {
        Paper paper = new Paper();
        paper.sourceLink = "https://doi.org/10.1000/example";

        assertTrue(service.supportedPdfUrl(paper).isEmpty());
        assertTrue(service.supportedPdfUrl((Paper) null).isEmpty());
    }
}
