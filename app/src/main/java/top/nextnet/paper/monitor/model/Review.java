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
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "logicalFeed_id"}))
public class Review extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public AppUser owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public LogicalFeed logicalFeed;

    @Column(nullable = false, length = 255)
    public String title;

    @Column(nullable = false, length = 255)
    public String templateId;

    @Column(nullable = false, length = 255)
    public String templateTitle;

    @Column(nullable = false, length = 4000)
    public String selectedStatesJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String reviewDesignJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String formSchemaJson;

    @Column(columnDefinition = "TEXT")
    public String reviewJsonSchemaJson;

    @Column(columnDefinition = "TEXT")
    public String reviewLinkmlSchemaJson;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;
}
