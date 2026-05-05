package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import top.nextnet.paper.monitor.model.SystemSettings;
import top.nextnet.paper.monitor.repo.SystemSettingsRepository;

@ApplicationScoped
public class SystemSettingsService {

    private final SystemSettingsRepository systemSettingsRepository;

    public SystemSettingsService(SystemSettingsRepository systemSettingsRepository) {
        this.systemSettingsRepository = systemSettingsRepository;
    }

    @Transactional
    public SystemSettings get() {
        SystemSettings settings = systemSettingsRepository.findById(SystemSettings.SINGLETON_ID);
        if (settings == null) {
            settings = new SystemSettings();
            systemSettingsRepository.persist(settings);
        }
        return settings;
    }

    @Transactional
    public boolean requireAdminApprovalForNewUsers() {
        return get().requireAdminApprovalForNewUsers;
    }

    @Transactional
    public void updateRequireAdminApprovalForNewUsers(boolean required) {
        get().requireAdminApprovalForNewUsers = required;
    }
}
