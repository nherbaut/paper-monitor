package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.EmailDomainPolicy;

@ApplicationScoped
public class EmailDomainPolicyRepository implements PanacheRepository<EmailDomainPolicy> {

    public List<EmailDomainPolicy> findAllOrdered() {
        return find("order by domain asc").list();
    }

    public Optional<EmailDomainPolicy> findByDomain(String domain) {
        return find("lower(domain) = ?1", domain.toLowerCase()).firstResultOptional();
    }
}
