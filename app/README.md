# Paper Monitor App

Quarkus application that polls RSS feeds containing scientific papers, stores discovered items in SQLite, and exposes the reader/admin/log pages.

## Run

```bash
./mvnw quarkus:dev
```

When run from `app/`, the default runtime paths point to the shared repository-level `../data/` directory.

## Container image

The Quarkus container image build now uses [src/main/docker/Dockerfile.jvm](/home/nherbaut/workspace/paper-monitor/app/src/main/docker/Dockerfile.jvm) via the Docker builder.

That runtime image includes:

- OpenJDK 21
- `pandoc`
- `texlive-xetex`
- `texlive-latex-extra`
- `texlive-fonts-recommended`
