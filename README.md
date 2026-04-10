# paper-monitor

Quarkus application that polls RSS feeds containing scientific papers, stores discovered items in a JDBC database, and exposes a homepage to manage:

- physical RSS feeds
- logical feeds aggregating multiple RSS feeds
- recently discovered papers

## Main behavior

- Each RSS feed belongs to exactly one logical feed.
- A scheduler periodically polls due feeds.
- New items are parsed and stored once, keyed by source link.
- The homepage at `/` lets you create, update, delete, and manually poll feeds.

## Database

The app defaults to SQLite and expects the JDBC driver to be available at runtime.

Default configuration in [`application.properties`](./src/main/resources/application.properties):

```properties
quarkus.datasource.db-kind=sqlite
quarkus.datasource.jdbc.url=jdbc:sqlite:paper-monitor.db
quarkus.hibernate-orm.schema-management.strategy=update
paper-monitor.poller.every=60s
```

You can override these settings from `application.properties`, environment variables, or JVM system properties to target another JDBC database.

## Run

```bash
./mvnw quarkus:dev
```

The admin homepage is available at <http://localhost:8080/>.
