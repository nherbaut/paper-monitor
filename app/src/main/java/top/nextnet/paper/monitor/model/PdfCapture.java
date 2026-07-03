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

@Entity
public class PdfCapture extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 64)
    public String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public Paper paper;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public AppUser createdBy;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant expiresAt;

    public Instant consumedAt;

    @Column(length = 1000)
    public String capturedSourceUrl;

    @Column(length = 32, nullable = false)
    public String status;

    @Column(length = 1000)
    public String error;

    public boolean expired(Instant now) {
        return consumedAt == null && !expiresAt.isAfter(now);
    }

    public String effectiveStatus(Instant now) {
        return expired(now) ? "EXPIRED" : status;
    }
}
