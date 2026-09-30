package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        uniqueConstraints = @UniqueConstraint(columnNames = {"jobId", "paperId"}),
        indexes = @Index(name = "idx_arxiv_pdf_item_job_status", columnList = "jobId,status,sequenceNumber")
)
public class ArxivPdfRetrievalItem extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long jobId;

    @Column(nullable = false)
    public Long paperId;

    @Column(nullable = false)
    public Integer sequenceNumber;

    @Column(nullable = false, length = 1000)
    public String paperTitle;

    @Column(nullable = false, length = 1000)
    public String sourceUrl;

    @Column(nullable = false, length = 24)
    public String status = "QUEUED";

    @Column(length = 2000)
    public String error;
}
