package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(indexes = {
        @Index(name = "idx_arxiv_pdf_job_feed_started", columnList = "logicalFeedId,startedAt"),
        @Index(name = "idx_arxiv_pdf_job_status", columnList = "status")
})
public class ArxivPdfRetrievalJob extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long logicalFeedId;

    @Column(nullable = false)
    public Long userId;

    public Long sourceJobId;

    @Column(nullable = false, length = 255)
    public String feedName;

    @Column(nullable = false, length = 128)
    public String stateId;

    @Column(nullable = false, length = 255)
    public String stateLabel;

    @Column(nullable = false, length = 32)
    public String status = "QUEUED";

    @Column(length = 255)
    public String phase;

    @Column(length = 1000)
    public String currentPaperTitle;

    public Integer totalItems = 0;
    public Integer completedItems = 0;
    public Integer importedItems = 0;
    public Integer skippedItems = 0;
    public Integer failedItems = 0;

    @Column(length = 2000)
    public String error;

    @Column(nullable = false)
    public Instant startedAt;

    public Instant finishedAt;
}
