package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.GoogleDrivePdfSync;
import top.nextnet.paper.monitor.model.Paper;

@ApplicationScoped
public class GoogleDrivePdfSyncRepository implements PanacheRepository<GoogleDrivePdfSync> {

    public Optional<GoogleDrivePdfSync> findByUserAndPaper(AppUser user, Paper paper) {
        return find("user = ?1 and paper = ?2", user, paper).firstResultOptional();
    }
}
