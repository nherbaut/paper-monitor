package top.nextnet.paper.monitor.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaperRecordTypeTest {

    @Test
    void treatsLegacyNullValuesAsPapers() {
        Paper paper = new Paper();
        paper.recordType = null;

        assertEquals(Paper.TYPE_PAPER, paper.recordTypeValue());
        assertFalse(paper.isGrayLiterature());
    }

    @Test
    void recognizesGrayLiterature() {
        Paper paper = new Paper();
        paper.recordType = Paper.TYPE_GRAY_LITERATURE;

        assertTrue(paper.isGrayLiterature());
    }
}
