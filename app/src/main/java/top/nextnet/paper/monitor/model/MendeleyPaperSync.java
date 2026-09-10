package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"feedSync_id", "paper_id"}),
        @UniqueConstraint(columnNames = {"feedSync_id", "mendeleyDocumentId"})
})
public class MendeleyPaperSync extends PanacheEntityBase {
    public static final String SYNCED = "SYNCED";
    public static final String PDF_PENDING = "PDF_PENDING";
    public static final String CONFLICT = "CONFLICT";
    public static final String REMOTE_DELETED = "REMOTE_DELETED";
    public static final String LOCAL_DELETED = "LOCAL_DELETED";
    public static final String IGNORED = "IGNORED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false)
    public MendeleyFeedSync feedSync;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn
    public Paper paper;
    @Column(nullable = false, length = 64)
    public String mendeleyDocumentId;
    @Column(length = 64)
    public String localFingerprint;
    @Column(length = 64)
    public String remoteFingerprint;
    public Instant remoteModifiedAt;
    @Column(length = 32)
    public String status = SYNCED;
    @Column(columnDefinition = "text")
    public String conflictJson;
    public Instant syncedAt;
}
