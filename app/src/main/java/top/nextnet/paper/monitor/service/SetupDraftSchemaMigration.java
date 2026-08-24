package top.nextnet.paper.monitor.service;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class SetupDraftSchemaMigration {

    private final EntityManager entityManager;

    public SetupDraftSchemaMigration(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    void allowAnonymousDrafts(@Observes StartupEvent ignored) {
        entityManager.createNativeQuery(
                "alter table paperfeedsetupdraft alter column user_id drop not null")
                .executeUpdate();
    }
}
