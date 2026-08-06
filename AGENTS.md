# Repository Guidelines

## Project Structure & Module Organization

- `app/`: Java 21 Quarkus application. Main code is in `app/src/main/java/top/nextnet/paper/monitor/`, resources in `app/src/main/resources/`, and tests in `app/src/test/java/`.
- `paper-data-extractor/`: Python 3.11+ FastAPI service for LinkML-backed taxonomy composition and paper classification. Package code lives in `src/paper_data_extractor/`; schemas, static files, templates, and data are in sibling directories.
- `firefox-extension/`: Firefox WebExtension for authenticated PDF capture.
- `piper-api/`: FastAPI sidecar around Piper TTS.
- `exchange/`: shared review/schema examples. Runtime data is expected under top-level `data/`, which should stay local.
- `grimmory/`: separate nested project with its own contributor guide; follow its local instructions when editing there.

## Build, Test, and Development Commands

- `cd app && ./mvnw quarkus:dev`: run the Quarkus app locally with Dev Services PostgreSQL.
- `cd app && ./mvnw test`: run Java unit and Quarkus tests.
- `cd app && ./mvnw package`: build the application artifact.
- In this workspace, Maven may need an explicit JDK: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw ...`.
- `cd app && JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./mvnw -q -DskipTests -Dquarkus.container-image.build=false package`: validate Quarkus augmentation without requiring Docker image build.
- `make list`: list image-producing subprojects.
- `make build DOCKERHUB_NAMESPACE=<user> IMAGE_TAG=dev`: build configured container images.
- `cd paper-data-extractor && python -m venv .venv && . .venv/bin/activate && pip install -e .[dev]`: prepare Python development.
- `cd paper-data-extractor && uvicorn paper_data_extractor.main:app --reload --port 8091`: run the extractor service.
- `cd piper-api && pip install -r requirements.txt && uvicorn app:APP --reload --port 8090`: run the TTS sidecar.

## Java App Architecture Notes

- Main Quarkus routes are concentrated in `HomeResource`, with PDF capture endpoints in `PdfCaptureResource` and review endpoints in `ReviewResource`.
- Authentication/session enforcement is handled by `AuthFilter`; add public callback/upload routes there when introducing unauthenticated endpoints.
- PDF files are stored through `PaperStorageService` and referenced from `Paper.uploadedPdfPath` / `uploadedPdfFileName`.
- Logical paper feeds are the main permission boundary. Use `LogicalFeedAccessService` for read/admin checks.
- Git export/mirror behavior lives in `PaperGitSyncService`; remote GitHub push support lives in `GithubRepositoryService`. Avoid running git sync from ordinary GET page-render paths because remote pushes can exceed transaction timeouts.
- Google Drive sync is per-user: OAuth and token refresh are in `GoogleDriveAuthService`; PDF upload/backfill and Drive folder management are in `GoogleDriveSyncService`; per-paper Drive state is stored in `GoogleDrivePdfSync`.
- Google Drive sync treats the configured Drive folder as a root and creates/uses paper-feed-named subfolders for synced PDFs.

## Coding Style & Naming Conventions

Use existing package boundaries: Quarkus resources in `web`, services in `service`, Panache repositories in `repo`, and entities/models in `model`. Java classes use `PascalCase`; methods and fields use `camelCase`; tests end with `Test`. Python modules use `snake_case` and typed Pydantic/FastAPI patterns already present. Keep YAML identifiers stable and descriptive.

## Testing Guidelines

Add Java tests beside related code under `app/src/test/java`, using JUnit 5 and Quarkus test support. Prefer service/model tests for business rules and resource tests for user-visible behavior. Python dev dependencies include `pytest`, but no Python test suite is present; add tests under `tests/` when changing extractor logic.
- For Quarkus CDI/Arc constructor changes, run a package build, not only `compile`; Arc validation happens during Quarkus augmentation.
- The app uses Hibernate ORM schema update in dev/prod configuration, so entity-field additions may be applied automatically at startup; still keep model changes explicit and test startup where possible.

## Commit & Pull Request Guidelines

Recent history uses short informal messages such as `fix` and `fix build`; improve on that with concise imperative subjects, for example `fix rss digest filtering`. PRs should describe the change, include test commands run, link related issues, and add screenshots for UI/template changes.
- Default branch is `master`; default remote is `origin`.
- Prefer a Gitflow-style feature branch for agent work, such as `feature/<short-topic>` or `fix/<short-topic>`, created from current `master`.
- Stage only files relevant to the requested change. Do not use `git add .` in this workspace because unrelated untracked files and local data may be present.
- Commit with a concise imperative message, for example `add rss default state dropdown`.
- Push feature branches to `origin` and mention the pushed branch. Open or request a PR when collaboration/review is expected.
- Push directly to `master` only when the user explicitly asks for a direct master push.
- Git network operations may require user approval and working local SSH/GitHub credentials.

## Security & Configuration Tips

Do not commit local data, secrets, API keys, Piper models, or generated runtime uploads. Configure production services with environment variables such as `QUARKUS_DATASOURCE_*`, `PAPER_DATA_EXTRACTOR_*`, and `PIPER_*`.
- Google Drive sync additionally uses `PAPER_MONITOR_GOOGLE_ENABLED`, `PAPER_MONITOR_GOOGLE_CLIENT_ID`, `PAPER_MONITOR_GOOGLE_CLIENT_SECRET`, and `PAPER_MONITOR_GOOGLE_SCOPES`. Scopes must be space-separated; the app normalizes accidental comma-separated values, but clean env values are preferred.
- For arbitrary user-selected Google Drive folders, include `https://www.googleapis.com/auth/drive` in the Google OAuth scopes. Users must reconnect Google Drive after scope changes because old refresh tokens do not gain new scopes.
- Do not log OAuth refresh tokens, access tokens, uploaded PDF contents, OpenAI keys, or SMTP credentials.
