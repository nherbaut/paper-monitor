package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.Paper;

class PaperStateRepairServiceTest {

    @Test
    void repairsUnknownAndBlankStatesToWorkflowInitialState() {
        WorkflowStateConfig workflow = WorkflowStateConfig.parse("""
                version: 2
                initial_state: INTAKE
                states:
                - id: INTAKE
                  label: Intake
                - id: REVIEWED
                  label: Reviewed
                transitions:
                - from: INTAKE
                  to:
                  - REVIEWED
                """);
        Paper paper = new Paper();

        paper.status = "REVIEWED";
        assertFalse(PaperStateRepairService.repairPaperStateIfOrphan(paper, workflow));
        assertEquals("REVIEWED", paper.status);

        paper.status = "UNKNOWN";
        assertTrue(PaperStateRepairService.repairPaperStateIfOrphan(paper, workflow));
        assertEquals("INTAKE", paper.status);

        paper.status = " ";
        assertTrue(PaperStateRepairService.repairPaperStateIfOrphan(paper, workflow));
        assertEquals("INTAKE", paper.status);
    }
}
