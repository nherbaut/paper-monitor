package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant;

@Entity
public class PaperFeedSetupDraft extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    public String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn
    public AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn
    public LogicalFeed logicalFeed;

    public Long scholarQueryId;

    @Column(length = 20000)
    public String scholarQuery;

    public Long scholarReportedCount;

    @Column(length = 1000)
    public String rssUrl;

    public Integer availableCount;

    @Column(length = 30000)
    public String previewJson;

    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean previewConfirmed;

    @Column(length = 120)
    public String title;

    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean driveEnabled;

    @Column(length = 255)
    public String driveFolderId;

    @Column(length = 1000)
    public String driveFolderName;

    @Column(length = 16)
    public String workflowType;

    @Column(length = 20000)
    public String customWorkflow;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false)
    public Instant updatedAt = Instant.now();

    @Column(nullable = false)
    public Instant expiresAt;
}
