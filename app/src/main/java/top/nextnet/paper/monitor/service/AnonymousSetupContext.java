package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.RequestScoped;
import java.time.Instant;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.PaperFeedSetupDraft;

@RequestScoped
public class AnonymousSetupContext {

    private String draftId;
    private Long logicalFeedId;
    private Instant expiresAt;

    public void set(PaperFeedSetupDraft draft) {
        draftId = draft.id;
        logicalFeedId = draft.logicalFeed.id;
        expiresAt = draft.expiresAt;
    }

    public boolean canAccess(LogicalFeed logicalFeed) {
        return logicalFeed != null
                && logicalFeed.id != null
                && logicalFeed.id.equals(logicalFeedId)
                && expiresAt != null
                && expiresAt.isAfter(Instant.now());
    }

    public String draftId() {
        return draftId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
