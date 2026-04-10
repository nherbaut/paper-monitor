package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import top.nextnet.paper.monitor.model.TtsSettings;

@ApplicationScoped
public class TtsSettingsRepository implements PanacheRepository<TtsSettings> {

    public TtsSettings first() {
        return findAll().firstResult();
    }
}
