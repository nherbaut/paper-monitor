package top.nextnet.paper.monitor.service;

public final class QuickSetupWorkflows {

    public static final String KANBAN = """
            version: 2

            initial_state: NEW

            states:
              - id: DISCARDED
                label: Discarded
              - id: NEW
                label: New
              - id: TODO
                label: Todo
              - id: DONE
                label: Done

            transitions:
              - from: DISCARDED
                to:
                  - NEW
              - from: NEW
                to:
                  - DISCARDED
                  - TODO
              - from: TODO
                to:
                  - NEW
                  - DONE
              - from: DONE
                to:
                  - TODO
            """;

    public static final String PRISMA = """
            version: 2

            initial_state: IDENTIFICATION/DATABASE_IDENTIFIED

            states:
              - id: IDENTIFICATION/PREVIOUS
                label: Previous studies
                group: IDENTIFICATION
                terminal: true
                report:
                  prisma_bucket: previous
              - id: IDENTIFICATION/DATABASE_IDENTIFIED
                label: Database identified
                group: IDENTIFICATION
                report:
                  prisma_bucket: database_identified
              - id: IDENTIFICATION/OTHER_IDENTIFIED
                label: Other identified
                group: IDENTIFICATION
                report:
                  prisma_bucket: other_identified
              - id: SCREENING/DATABASE_SCREENED
                label: Database screened
                group: SCREENING
                report:
                  prisma_bucket: database_screened
              - id: SCREENING/DATABASE_EXCLUDED
                label: Database excluded at screening
                group: SCREENING
                terminal: true
                report:
                  prisma_bucket: database_screening_excluded
              - id: SCREENING/OTHER_SCREENED
                label: Other screened
                group: SCREENING
                report:
                  prisma_bucket: other_screened
              - id: SCREENING/OTHER_EXCLUDED
                label: Other excluded at screening
                group: SCREENING
                terminal: true
                report:
                  prisma_bucket: other_screening_excluded
              - id: RETRIEVAL/DATABASE_SOUGHT_FOR_RETRIEVAL
                label: Database sought for retrieval
                group: RETRIEVAL
                report:
                  prisma_bucket: database_sought_for_retrieval
              - id: RETRIEVAL/DATABASE_NOT_RETRIEVED
                label: Database not retrieved
                group: RETRIEVAL
                terminal: true
                report:
                  prisma_bucket: database_not_retrieved
              - id: RETRIEVAL/OTHER_SOUGHT_FOR_RETRIEVAL
                label: Other sought for retrieval
                group: RETRIEVAL
                report:
                  prisma_bucket: other_sought_for_retrieval
              - id: RETRIEVAL/OTHER_NOT_RETRIEVED
                label: Other not retrieved
                group: RETRIEVAL
                terminal: true
                report:
                  prisma_bucket: other_not_retrieved
              - id: ELIGIBILITY/DATABASE_ASSESSED_FOR_ELIGIBILITY
                label: Database assessed for eligibility
                group: ELIGIBILITY
                report:
                  prisma_bucket: database_assessed_for_eligibility
              - id: ELIGIBILITY/DATABASE_EXCLUDED
                label: Database excluded at eligibility
                group: ELIGIBILITY
                terminal: true
                requires:
                  exclusion_criterion:
                    taxonomy: EXCLUSION
                    exactly: 1
                  exclusion_notes: optional
                report:
                  prisma_bucket: database_eligibility_excluded
              - id: ELIGIBILITY/OTHER_ASSESSED_FOR_ELIGIBILITY
                label: Other assessed for eligibility
                group: ELIGIBILITY
                report:
                  prisma_bucket: other_assessed_for_eligibility
              - id: ELIGIBILITY/OTHER_EXCLUDED
                label: Other excluded at eligibility
                group: ELIGIBILITY
                terminal: true
                requires:
                  exclusion_criterion:
                    taxonomy: EXCLUSION
                    exactly: 1
                  exclusion_notes: optional
                report:
                  prisma_bucket: other_eligibility_excluded
              - id: INCLUDED/DATABASE_INCLUDED_IN_REVIEW
                label: Database included in review
                group: INCLUDED
                terminal: true
                requires:
                  inclusion_criteria:
                    taxonomy: INCLUSION
                    min: 1
                report:
                  prisma_bucket: database_included
              - id: INCLUDED/OTHER_INCLUDED_IN_REVIEW
                label: Other included in review
                group: INCLUDED
                terminal: true
                requires:
                  inclusion_criteria:
                    taxonomy: INCLUSION
                    min: 1
                report:
                  prisma_bucket: other_included

            transitions:
              - from: IDENTIFICATION/DATABASE_IDENTIFIED
                to:
                  - SCREENING/DATABASE_SCREENED
                  - SCREENING/DATABASE_EXCLUDED
              - from: IDENTIFICATION/OTHER_IDENTIFIED
                to:
                  - SCREENING/OTHER_SCREENED
                  - SCREENING/OTHER_EXCLUDED
              - from: SCREENING/DATABASE_SCREENED
                to:
                  - RETRIEVAL/DATABASE_SOUGHT_FOR_RETRIEVAL
                  - SCREENING/DATABASE_EXCLUDED
              - from: SCREENING/OTHER_SCREENED
                to:
                  - RETRIEVAL/OTHER_SOUGHT_FOR_RETRIEVAL
                  - SCREENING/OTHER_EXCLUDED
              - from: RETRIEVAL/DATABASE_SOUGHT_FOR_RETRIEVAL
                to:
                  - RETRIEVAL/DATABASE_NOT_RETRIEVED
                  - ELIGIBILITY/DATABASE_ASSESSED_FOR_ELIGIBILITY
              - from: RETRIEVAL/OTHER_SOUGHT_FOR_RETRIEVAL
                to:
                  - RETRIEVAL/OTHER_NOT_RETRIEVED
                  - ELIGIBILITY/OTHER_ASSESSED_FOR_ELIGIBILITY
              - from: ELIGIBILITY/DATABASE_ASSESSED_FOR_ELIGIBILITY
                to:
                  - ELIGIBILITY/DATABASE_EXCLUDED
                  - INCLUDED/DATABASE_INCLUDED_IN_REVIEW
              - from: ELIGIBILITY/OTHER_ASSESSED_FOR_ELIGIBILITY
                to:
                  - ELIGIBILITY/OTHER_EXCLUDED
                  - INCLUDED/OTHER_INCLUDED_IN_REVIEW

            taxonomies:
              EXCLUSION:
                label: Eligibility exclusion criteria
                values:
                  - id: EX1
                    label: ex1
                  - id: EX2
                    label: ex2
              INCLUSION:
                label: Inclusion criteria
                values:
                  - id: INC1
                    label: inc1
                  - id: INC2
                    label: inc2
            """;

    private QuickSetupWorkflows() {
    }

    public static String yamlFor(String workflowType) {
        return switch (normalizeType(workflowType)) {
            case "prisma" -> PRISMA;
            case "kanban" -> KANBAN;
            default -> throw new IllegalArgumentException("workflowType must be either prisma or kanban");
        };
    }

    public static String normalizeType(String workflowType) {
        return workflowType == null || workflowType.isBlank() ? "kanban" : workflowType.trim().toLowerCase();
    }
}
