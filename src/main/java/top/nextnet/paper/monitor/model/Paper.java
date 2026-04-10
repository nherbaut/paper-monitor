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
import java.time.Instant;
import java.time.LocalDate;

@Entity
public class Paper extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 1000)
    public String title;

    @Column(nullable = false, unique = true, length = 1000)
    public String sourceLink;

    @Column(length = 1000)
    public String openAccessLink;

    @Column(length = 1000)
    public String uploadedPdfPath;

    @Column(length = 255)
    public String uploadedPdfFileName;

    @Column(length = 4000)
    public String summary;

    @Column(length = 20000)
    public String notes;

    @Column(length = 1000)
    public String authors;

    @Column(length = 255)
    public String publisher;

    public LocalDate publishedOn;

    @Column(length = 64)
    public String status = "NEW";

    @Column(nullable = false)
    public Instant discoveredAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public Feed feed;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public LogicalFeed logicalFeed;
}
