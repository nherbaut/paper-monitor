package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import top.nextnet.paper.monitor.model.DefaultSignupPolicy;
import top.nextnet.paper.monitor.repo.DefaultSignupPolicyRepository;

@ApplicationScoped
public class DefaultSignupPolicyService {

    private final DefaultSignupPolicyRepository defaultSignupPolicyRepository;

    public DefaultSignupPolicyService(DefaultSignupPolicyRepository defaultSignupPolicyRepository) {
        this.defaultSignupPolicyRepository = defaultSignupPolicyRepository;
    }

    @Transactional
    public DefaultSignupPolicy get() {
        DefaultSignupPolicy policy = defaultSignupPolicyRepository.findById(DefaultSignupPolicy.SINGLETON_ID);
        if (policy == null) {
            policy = new DefaultSignupPolicy();
            defaultSignupPolicyRepository.persist(policy);
        }
        return policy;
    }

    @Transactional
    public DefaultSignupPolicy update(boolean canCreateAccounts, boolean autoApprove) {
        DefaultSignupPolicy policy = get();
        policy.canCreateAccounts = canCreateAccounts;
        policy.autoApprove = autoApprove;
        return policy;
    }
}
