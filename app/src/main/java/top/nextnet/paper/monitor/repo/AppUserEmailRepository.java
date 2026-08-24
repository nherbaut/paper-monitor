package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.AppUserEmail;

@ApplicationScoped
public class AppUserEmailRepository implements PanacheRepository<AppUserEmail> {

    public Optional<AppUserEmail> findByEmail(String email) {
        return find("lower(email) = ?1", email.toLowerCase()).firstResultOptional();
    }

    public List<AppUserEmail> findByUser(AppUser user) {
        return find("user = ?1 order by email", user).list();
    }
}
