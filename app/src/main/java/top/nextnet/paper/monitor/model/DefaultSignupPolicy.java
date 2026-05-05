package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class DefaultSignupPolicy extends PanacheEntityBase {

    public static final long SINGLETON_ID = 1L;

    @Id
    public Long id = SINGLETON_ID;

    @Column(nullable = false, columnDefinition = "boolean default true")
    public boolean canCreateAccounts = true;

    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean autoApprove = false;
}
