package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QuickSetupWorkflowsTest {

    @Test
    void kanbanOnlyAllowsMovesBetweenAdjacentStates() {
        WorkflowStateConfig workflow = WorkflowStateConfig.parse(QuickSetupWorkflows.KANBAN);

        assertTrue(QuickSetupWorkflows.isDefaultMiageWorkflow(workflow));
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
    void customizedKanbanIsNotTheDefaultMiageWorkflow() {
        WorkflowStateConfig customized = WorkflowStateConfig.parse(
                QuickSetupWorkflows.KANBAN.replace("label: Todo", "label: Review next"));

        assertFalse(QuickSetupWorkflows.isDefaultMiageWorkflow(customized));
        assertFalse(QuickSetupWorkflows.isDefaultMiageWorkflow(null));
    }

    @Test
    void prismaHasExpectedInitialStateAndPlaceholderCriteria() {
        WorkflowStateConfig workflow = WorkflowStateConfig.parse(QuickSetupWorkflows.PRISMA);

        assertEquals("IDENTIFICATION/DATABASE_IDENTIFIED", workflow.initialPaperStatus());
        assertTrue(workflow.configJson().contains("\"prismaBucket\":\"OTHER_IDENTIFIED\""));
        assertTrue(workflow.containsTaxonomyLeaf("EXCLUSION", "EX1"));
        assertTrue(workflow.containsTaxonomyLeaf("EXCLUSION", "EX2"));
        assertTrue(workflow.containsTaxonomyLeaf("INCLUSION", "INC1"));
        assertTrue(workflow.containsTaxonomyLeaf("INCLUSION", "INC2"));
    }

    @Test
    void graphWorkflowsRequireConnectedStatesAndAtMostTwoEdgesPerDirection() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowStateConfig.parse("""
                version: 2
                initial_state: A
                states:
                  - id: A
                  - id: B
                transitions:
                  - from: A
                    to: [B, A]
                """).validateGraphRules());

        assertThrows(IllegalArgumentException.class, () -> WorkflowStateConfig.parse("""
                version: 2
                initial_state: A
                states:
                  - id: A
                  - id: B
                  - id: C
                  - id: D
                transitions:
                  - from: A
                    to: [B, C, D]
                  - from: B
                    to: [A]
                  - from: C
                    to: [A]
                  - from: D
                    to: [A]
                """).validateGraphRules());

        assertThrows(IllegalArgumentException.class, () -> WorkflowStateConfig.parse("""
                version: 2
                initial_state: A
                states:
                  - id: A
                  - id: B
                  - id: C
                transitions:
                  - from: A
                    to: [B]
                """).validateGraphRules());
    }
}
