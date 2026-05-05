package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"domain"}))
public class EmailDomainPolicy extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 255)
    public String domain;

    @Column(nullable = false, columnDefinition = "boolean default true")
    public boolean canCreateAccounts = true;

    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean autoApprove = false;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
