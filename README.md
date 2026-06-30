# paper-monitor

Repository layout:

- `app/`: Quarkus application
- `data/`: local runtime data for the app
- `piper-api/`: HTTP sidecar for Piper TTS

## Run the app

```bash
cd app
./mvnw quarkus:dev
```

The app uses the shared top-level `data/` directory for:

- PostgreSQL data volume: `data/postgres/`
- uploaded PDFs and note images: `data/uploads/`
- Git paper mirrors: `data/git-remotes/`
- Piper models: `data/tts-models/`

When you run the app in dev mode, Quarkus Dev Services will automatically start a disposable PostgreSQL container for you.

```bash
cd app
./mvnw quarkus:dev
```

For the containerized stack, the repository `docker-compose.yml` uses a regular PostgreSQL service instead.

## Quick setup

Once you are signed in, you can bootstrap a paper feed from a single URL:

```text
/admin/quick-setup?paperFeedName=My%20Review&rssUrl=https%3A%2F%2Fscholar.miage.dev%2Ffeed%2F4.rss
```

This flow:

- shows a confirmation recap first
- creates a paper feed with the default workflow
  - `DISCARDED`
  - `NEW`
  - `TODO`
  - `DONE`
- creates one RSS feed attached to that paper feed
- forces imported papers from that RSS feed into the `NEW` state
- polls the RSS feed immediately
- redirects to the reader filtered on the newly created paper feed

Required query params:

- `paperFeedName`
- `rssUrl`

## Workflow YAML

Paper feeds now accept a canonical workflow `v2` YAML format. Legacy list-style workflows are still accepted as input and are converted automatically to `v2`.

Minimal Kanban-style example:

```yaml
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
      - TODO
      - DONE

  - from: NEW
    to:
      - DISCARDED
      - TODO
      - DONE

  - from: TODO
    to:
      - DISCARDED
      - NEW
      - DONE

  - from: DONE
    to:
      - DISCARDED
      - NEW
      - TODO
```

PRISMA-oriented example with eligibility exclusion and inclusion criteria:

```yaml
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
      - id: POPULATION
        label: Population
        children:
          - id: WRONG_POPULATION
            label: Wrong population
          - id: AGE_RANGE_MISMATCH
            label: Age range mismatch

      - id: INTERVENTION
        label: Intervention
        children:
          - id: WRONG_INTERVENTION
            label: Wrong intervention
          - id: NO_INTERVENTION_OF_INTEREST
            label: No intervention of interest

      - id: OUTCOME
        label: Outcome
        children:
          - id: WRONG_OUTCOME
            label: Wrong outcome
          - id: NO_RELEVANT_OUTCOME
            label: No relevant outcome reported

      - id: STUDY_DESIGN
        label: Study design
        children:
          - id: WRONG_STUDY_DESIGN
            label: Wrong study design
          - id: SECONDARY_STUDY
            label: Secondary study only

      - id: PUBLICATION
        label: Publication
        children:
          - id: FULL_TEXT_UNAVAILABLE
            label: Full text unavailable
          - id: NON_PEER_REVIEWED
            label: Not peer reviewed

      - id: OTHER
        label: Other
        children:
          - id: OTHER_EXCLUSION
            label: Other exclusion reason

  INCLUSION:
    label: Inclusion criteria
    values:
      - id: TOPIC_RELEVANCE
        label: Topic relevance
        children:
          - id: ADDRESSES_TARGET_TOPIC
            label: Addresses the target topic
          - id: DIRECTLY_EVALUATES_TARGET
            label: Directly evaluates the target phenomenon

      - id: EMPIRICAL_EVIDENCE
        label: Empirical evidence
        children:
          - id: REPORTS_PRIMARY_DATA
            label: Reports primary data
          - id: CONTAINS_EMPIRICAL_EVALUATION
            label: Contains empirical evaluation

      - id: METHOD_FIT
        label: Method fit
        children:
          - id: ACCEPTABLE_STUDY_DESIGN
            label: Acceptable study design
          - id: SUFFICIENT_METHOD_DETAIL
            label: Sufficient method detail

      - id: REPORTING_QUALITY
        label: Reporting quality
        children:
          - id: CLEAR_RESULTS
            label: Clear results
          - id: REPRODUCIBLE_DESCRIPTION
            label: Reproducible description
```

## Piper sidecar

The repository now includes a separate `piper-api/` project plus a top-level `docker-compose.yml`.

The sidecar exposes:

- `GET /healthz`
- `GET /voices`
- `POST /v1/speak`

Compose assumes you provide:

- a host `piper` binary at `/usr/local/bin/piper`
- voice models under `data/tts-models/`

That keeps the sidecar isolated without coupling Piper installation details into the Quarkus app.

## Build and push container images

The repository root includes a `Makefile` that discovers every immediate subfolder containing a `Dockerfile` or `Containerfile`.
The `app/` subfolder is also included and built through Quarkus container-image support instead of a plain `docker build`.

Examples:

```bash
make list
make build DOCKERHUB_NAMESPACE=mydockerhubuser IMAGE_TAG=latest
make push DOCKERHUB_NAMESPACE=mydockerhubuser IMAGE_TAG=latest
```

Image names follow this pattern:

- `<DOCKERHUB_NAMESPACE>/paper-monitor-<folder>:<IMAGE_TAG>`

For the Quarkus app specifically, the image name is:

- `<DOCKERHUB_NAMESPACE>/paper-monitor-app:<IMAGE_TAG>`
