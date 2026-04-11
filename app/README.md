# Paper Monitor App

Quarkus application that polls RSS feeds containing scientific papers, stores discovered items in PostgreSQL, and exposes the reader/admin/log pages.

## Run

```bash
./mvnw quarkus:dev
```

When run from `app/`, Quarkus Dev Services will automatically start a PostgreSQL container for development and tests.

Production-style runs should provide:

```bash
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://host:5432/paper_monitor
QUARKUS_DATASOURCE_USERNAME=paper_monitor
QUARKUS_DATASOURCE_PASSWORD=paper_monitor
```

## Container image

The Quarkus container image build now uses [src/main/docker/Dockerfile.jvm](/home/nherbaut/workspace/paper-monitor/app/src/main/docker/Dockerfile.jvm) via the Docker builder.

That runtime image includes:

- OpenJDK 21
- `pandoc`
- `texlive-xetex`
- `texlive-latex-extra`
- `texlive-fonts-recommended`
