package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import top.nextnet.paper.monitor.model.PdfCapture;

@ApplicationScoped
public class PdfCaptureRepository implements PanacheRepository<PdfCapture> {

    public Optional<PdfCapture> findByTokenHashForUpdate(String tokenHash) {
        return find("tokenHash", tokenHash)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResultOptional();
    }
}
