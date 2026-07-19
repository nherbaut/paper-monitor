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
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "paper_id"}))
public class GoogleDrivePdfSync extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public Paper paper;

    @Column(length = 255)
    public String driveFileId;

    @Column(length = 255)
    public String driveFileName;

    @Column(length = 1000)
    public String driveWebViewLink;

    @Column(length = 255)
    public String driveFolderId;

    @Column(length = 1000)
    public String driveFolderName;

    @Column(length = 1000)
    public String syncedStoredPdfPath;

    @Column(length = 255)
    public String syncedOriginalFileName;

    public Instant syncedAt;

    @Column(length = 2000)
    public String lastError;
}
