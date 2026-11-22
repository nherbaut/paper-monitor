package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"logicalFeed_id", "email"}))
public class LogicalFeedInvitation extends PanacheEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "logicalFeed_id", nullable = false)
    public LogicalFeed logicalFeed;

    @Column(nullable = false, length = 255)
    public String email;

    @Column(nullable = false, length = 16)
    public String role;

    @Column(nullable = false)
    public Instant expiresAt;
}
