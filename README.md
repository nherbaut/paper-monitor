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

- SQLite database: `data/paper-monitor.db`
- uploaded PDFs and note images: `data/uploads/`
- Git paper mirrors: `data/git-remotes/`
- Piper models: `data/tts-models/`

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
