package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void workflowSupportsAtLeastOneExclusionCriteria() {
        WorkflowStateConfig workflow = WorkflowStateConfig.parse("""
                version: 2
                initial_state: REVIEW
                states:
                  - id: REVIEW
                  - id: EXCLUDED
                    requires:
                      exclusion_criteria:
                        taxonomy: EXCLUSION
                        min: 1
                transitions:
                  - from: REVIEW
                    to: [EXCLUDED]
                taxonomies:
                  EXCLUSION:
                    label: Exclusion criteria
                    values:
                      - id: OUT_OF_SCOPE
                        label: Out of scope
                      - id: WRONG_METHOD
                        label: Wrong method
                """);

        assertEquals(1, workflow.requirementsFor("EXCLUDED").exclusionCriterion().minimum());
        assertTrue(workflow.containsTaxonomyLeaf("EXCLUSION", "WRONG_METHOD"));
        assertTrue(workflow.toYaml().contains("exclusion_criteria:"));
    }

    @Test
    void graphWorkflowsRequireConnectedStatesButAllowBranching() {
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

        assertDoesNotThrow(() -> WorkflowStateConfig.parse("""
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

    @Test
    void workflowLayoutIsCanonicalizedAndValidated() {
        WorkflowStateConfig workflow = WorkflowStateConfig.parse("""
                version: 2
                initial_state: A
                layout:
                  nodes:
                    A:
                      x: 120
                      y: 75.5
                states:
                  - id: A
                  - id: B
                transitions:
                  - from: A
                    to: [B]
                """);

        assertEquals(120.0, workflow.layoutNodes().get("A").x());
        assertEquals(75.5, workflow.layoutNodes().get("A").y());
        assertTrue(workflow.toYaml().contains("layout:\n  nodes:\n    A:"));
        assertTrue(workflow.configJson().contains("\"layout\""));

        assertThrows(IllegalArgumentException.class, () -> WorkflowStateConfig.parse("""
                version: 2
                initial_state: A
                layout:
                  nodes:
                    MISSING: { x: 1, y: 2 }
                states:
                  - id: A
                  - id: B
                transitions:
                  - from: A
                    to: [B]
                """));
    }
}
