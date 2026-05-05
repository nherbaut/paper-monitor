package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.EmailDomainPolicy;
import top.nextnet.paper.monitor.repo.EmailDomainPolicyRepository;

@ApplicationScoped
public class EmailDomainPolicyService {

    private final EmailDomainPolicyRepository emailDomainPolicyRepository;
    private final DefaultSignupPolicyService defaultSignupPolicyService;

    public EmailDomainPolicyService(
            EmailDomainPolicyRepository emailDomainPolicyRepository,
            DefaultSignupPolicyService defaultSignupPolicyService
    ) {
        this.emailDomainPolicyRepository = emailDomainPolicyRepository;
        this.defaultSignupPolicyService = defaultSignupPolicyService;
    }

    public List<EmailDomainPolicy> all() {
        return emailDomainPolicyRepository.findAllOrdered();
    }

    public DomainSignupPolicy resolveForEmail(String email) {
        String domain = extractDomain(email);
        DomainSignupPolicy defaultPolicy = defaultPolicy(domain);
        if (domain == null) {
            return defaultPolicy;
        }
        Optional<EmailDomainPolicy> policy = emailDomainPolicyRepository.findByDomain(domain);
        return policy.map(value -> new DomainSignupPolicy(value.domain, value.canCreateAccounts, value.autoApprove, true))
                .orElse(defaultPolicy);
    }

    public DomainSignupPolicy defaultPolicy() {
        return defaultPolicy(null);
    }

    @Transactional
    public EmailDomainPolicy create(String domain, boolean canCreateAccounts, boolean autoApprove) {
        String normalizedDomain = normalizeDomain(domain);
        if (emailDomainPolicyRepository.findByDomain(normalizedDomain).isPresent()) {
            throw new IllegalArgumentException("An email domain rule for " + normalizedDomain + " already exists");
        }
        EmailDomainPolicy policy = new EmailDomainPolicy();
        policy.domain = normalizedDomain;
        policy.canCreateAccounts = canCreateAccounts;
        policy.autoApprove = autoApprove;
        emailDomainPolicyRepository.persist(policy);
        return policy;
    }

    @Transactional
    public EmailDomainPolicy update(Long id, String domain, boolean canCreateAccounts, boolean autoApprove) {
        EmailDomainPolicy policy = emailDomainPolicyRepository.findByIdOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown email domain rule"));
        String normalizedDomain = normalizeDomain(domain);
        Optional<EmailDomainPolicy> existing = emailDomainPolicyRepository.findByDomain(normalizedDomain);
        if (existing.isPresent() && !existing.get().id.equals(policy.id)) {
            throw new IllegalArgumentException("An email domain rule for " + normalizedDomain + " already exists");
        }
        policy.domain = normalizedDomain;
        policy.canCreateAccounts = canCreateAccounts;
        policy.autoApprove = autoApprove;
        return policy;
    }

    @Transactional
    public void delete(Long id) {
        EmailDomainPolicy policy = emailDomainPolicyRepository.findByIdOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown email domain rule"));
        policy.delete();
    }

    private String normalizeDomain(String domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Email domain is required");
        }
        String normalized = domain.trim().toLowerCase();
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Email domain is required");
        }
        if (normalized.contains(" ") || normalized.contains("@") || !normalized.contains(".")) {
            throw new IllegalArgumentException("Enter a valid email domain like example.org");
        }
        return normalized;
    }

    private String extractDomain(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase();
        int atIndex = normalized.lastIndexOf('@');
        if (atIndex < 0 || atIndex == normalized.length() - 1) {
            return null;
        }
        return normalized.substring(atIndex + 1);
    }

    private DomainSignupPolicy defaultPolicy(String domain) {
        var policy = defaultSignupPolicyService.get();
        return new DomainSignupPolicy(domain, policy.canCreateAccounts, policy.autoApprove, false);
    }

    public record DomainSignupPolicy(String domain, boolean canCreateAccounts, boolean autoApprove, boolean configured) {
    }
}
