package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant;

@Entity
public class MendeleyLoginRequest extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, unique = true, length = 96)
    public String state;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(nullable = false)
    public AppUser user;
    @Column(length = 2000)
    public String returnTo;
    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
