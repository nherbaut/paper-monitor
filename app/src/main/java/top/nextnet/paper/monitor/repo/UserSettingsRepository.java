package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.UserSettings;

@ApplicationScoped
public class UserSettingsRepository implements PanacheRepository<UserSettings> {

    public Optional<UserSettings> findByUser(AppUser user) {
        return find("user", user).firstResultOptional();
    }
}
