package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import top.nextnet.paper.monitor.model.ArxivPdfRetrievalItem;

@ApplicationScoped
public class ArxivPdfRetrievalItemRepository implements PanacheRepository<ArxivPdfRetrievalItem> {

    public List<ArxivPdfRetrievalItem> findQueuedByJob(Long jobId) {
        return find("jobId = ?1 and status in ?2 order by sequenceNumber", jobId,
                List.of("QUEUED", "DOWNLOADING")).list();
    }

    public List<ArxivPdfRetrievalItem> findFailedByJob(Long jobId) {
        return find("jobId = ?1 and status = 'FAILED' order by sequenceNumber", jobId).list();
    }
}
