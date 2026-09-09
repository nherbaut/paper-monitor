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
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "logicalFeed_id"}))
public class MendeleyFeedSync extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false)
    public AppUser user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false)
    public LogicalFeed logicalFeed;
    @Column(length = 64)
    public String rootFolderId;
    @Column(length = 255)
    public String rootFolderName;
    @Column(columnDefinition = "text")
    public String stateFolderMappingsJson;
    @Column(columnDefinition = "text")
    public String pendingPreviewJson;
    public Instant previewedAt;
    public Instant lastSyncedAt;
    @Column(length = 2000)
    public String lastError;
    @Column(length = 24)
    public String syncStatus = "IDLE";
    @Column(length = 64)
    public String syncPhase;
    @Column(length = 24)
    public String syncTrigger;
    public Integer syncCompletedActions = 0;
    public Integer syncTotalActions = 0;
    public Instant syncStartedAt;
    public Instant syncFinishedAt;
    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean enabled;
    @OneToMany(mappedBy = "feedSync", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<MendeleyPaperSync> paperSyncs = new ArrayList<>();
}
