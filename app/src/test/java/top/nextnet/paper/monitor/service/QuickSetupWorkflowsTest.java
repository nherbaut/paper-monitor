package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QuickSetupWorkflowsTest {

    @Test
    void kanbanOnlyAllowsMovesBetweenAdjacentStates() {
        WorkflowStateConfig workflow = WorkflowStateConfig.parse(QuickSetupWorkflows.KANBAN);

        assertEquals("NEW", workflow.initialPaperStatus());
        assertTrue(workflow.allowsTransition("DISCARDED", "NEW"));
        assertTrue(workflow.allowsTransition("NEW", "DISCARDED"));
        assertTrue(workflow.allowsTransition("NEW", "TODO"));
        assertTrue(workflow.allowsTransition("TODO", "NEW"));
        assertTrue(workflow.allowsTransition("TODO", "DONE"));
        assertTrue(workflow.allowsTransition("DONE", "TODO"));
        assertFalse(workflow.allowsTransition("DISCARDED", "TODO"));
        assertFalse(workflow.allowsTransition("NEW", "DONE"));
        assertFalse(workflow.allowsTransition("DONE", "DISCARDED"));
    }

    @Test
    void prismaHasExpectedInitialStateAndPlaceholderCriteria() {
        WorkflowStateConfig workflow = WorkflowStateConfig.parse(QuickSetupWorkflows.PRISMA);

        assertEquals("IDENTIFICATION/DATABASE_IDENTIFIED", workflow.initialPaperStatus());
        assertTrue(workflow.containsTaxonomyLeaf("EXCLUSION", "EX1"));
        assertTrue(workflow.containsTaxonomyLeaf("EXCLUSION", "EX2"));
        assertTrue(workflow.containsTaxonomyLeaf("INCLUSION", "INC1"));
        assertTrue(workflow.containsTaxonomyLeaf("INCLUSION", "INC2"));
    }
}
