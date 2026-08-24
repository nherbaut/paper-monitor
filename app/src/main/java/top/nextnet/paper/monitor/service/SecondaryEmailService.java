package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Locale;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.AppUserEmail;
import top.nextnet.paper.monitor.repo.AppUserEmailRepository;
import top.nextnet.paper.monitor.repo.AppUserRepository;

@ApplicationScoped
public class SecondaryEmailService {

    private final AppUserRepository appUserRepository;
    private final AppUserEmailRepository appUserEmailRepository;

    public SecondaryEmailService(
            AppUserRepository appUserRepository,
            AppUserEmailRepository appUserEmailRepository
    ) {
        this.appUserRepository = appUserRepository;
        this.appUserEmailRepository = appUserEmailRepository;
    }

    @Transactional
    public void addVerifiedGoogleEmail(AppUser user, String email) {
        String normalized = normalize(email);
        if (user == null || normalized == null) {
            throw new IllegalArgumentException("Google did not return a verified email address");
        }
        AppUser owner = appUserRepository.findByEmail(normalized).orElse(null);
        if (owner != null && !owner.id.equals(user.id)) {
            throw new IllegalArgumentException("This Google email address already belongs to another Paper Monitor account");
        }
        if (user.email != null && normalized.equalsIgnoreCase(user.email)) {
            return;
        }
        AppUserEmail existing = appUserEmailRepository.findByEmail(normalized).orElse(null);
        if (existing != null) {
            if (!existing.user.id.equals(user.id)) {
                throw new IllegalArgumentException("This Google email address already belongs to another Paper Monitor account");
            }
            existing.verifiedAt = Instant.now();
            existing.source = "GOOGLE";
            return;
        }
        AppUserEmail secondary = new AppUserEmail();
        secondary.user = user;
        secondary.email = normalized;
        secondary.source = "GOOGLE";
        secondary.verifiedAt = Instant.now();
        appUserEmailRepository.persist(secondary);
    }

    private String normalize(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
