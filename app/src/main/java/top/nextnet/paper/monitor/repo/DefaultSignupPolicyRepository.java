package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import top.nextnet.paper.monitor.model.DefaultSignupPolicy;

@ApplicationScoped
public class DefaultSignupPolicyRepository implements PanacheRepository<DefaultSignupPolicy> {
}
