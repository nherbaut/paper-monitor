package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.PaperEvent;
import top.nextnet.paper.monitor.repo.PaperEventRepository;

@ApplicationScoped
public class StatusChangeUndoService {
    private final PaperEventRepository events;
    private final PaperEventService eventService;
    private final LogicalFeedAccessService access;
    private final PaperGitSyncService git;

    public StatusChangeUndoService(PaperEventRepository events, PaperEventService eventService,
            LogicalFeedAccessService access, PaperGitSyncService git) {
        this.events = events;
        this.eventService = eventService;
        this.access = access;
        this.git = git;
    }

    public record Availability(String targetStatus, String reason) {
        public boolean available() { return reason == null; }
    }

    public Availability availability(PaperEvent event, Long latestStatusEventId) {
        if (!"STATE_CHANGED".equals(event.type)) return null;
        if (event.revertedAt != null) return new Availability(null, "This status change was already undone.");
        if (!Objects.equals(event.id, latestStatusEventId)) {
            return new Availability(null, "A later status change or synchronization prevents undo.");
        }
        StatusSnapshot before;
        StatusSnapshot after;
        try {
            if (event.statusBeforeJson != null || event.statusAfterJson != null) {
                if (event.statusBeforeJson == null || event.statusAfterJson == null) {
                    return new Availability(null, "This event has an incomplete status snapshot.");
                }
                before = StatusSnapshot.fromJson(event.statusBeforeJson);
                after = StatusSnapshot.fromJson(event.statusAfterJson);
            } else {
                // Older ordinary status events recorded only the two state IDs.
                String[] states = event.details == null ? new String[0] : event.details.split(" -> ", -1);
                if (states.length != 2 || states[0].isBlank() || states[1].isBlank()) {
                    return new Availability(null, "This older event has no restorable status information.");
                }
                before = new StatusSnapshot(states[0], List.of(), null, List.of());
                after = new StatusSnapshot(states[1], List.of(), null, List.of());
            }
        } catch (RuntimeException e) {
            return new Availability(null, "This event has no valid status snapshot.");
        }
        if (Objects.equals(before.status(), after.status())) return new Availability(null, "The status did not change.");
        Paper paper = event.paper;
        if (!Objects.equals(paper.status, after.status())
                || (event.statusAfterJson != null && !after.equals(StatusSnapshot.capture(paper)))) {
            return new Availability(null, "The paper's classification has changed since this event.");
        }
        try {
            WorkflowStateConfig workflow = paper.logicalFeed.workflowConfig();
            if (!workflow.containsLeafState(before.status())) {
                return new Availability(null, "The previous state is no longer in this workflow.");
            }
            var requirements = workflow.requirementsFor(before.status());
            if (!validCriteria(workflow, requirements.exclusionCriterion(), before.exclusions())
                    || !validCriteria(workflow, requirements.inclusionCriteria(), before.inclusions())) {
                return new Availability(null, "The saved classification reasons do not satisfy the current workflow. Change state in the reader.");
            }
        } catch (IllegalArgumentException e) {
            return new Availability(null, "The current workflow is invalid.");
        }
        return new Availability(before.status(), null);
    }

    private boolean validCriteria(WorkflowStateConfig workflow, WorkflowStateConfig.CriterionRule rule, List<String> values) {
        return rule == null || (values.size() >= rule.minimum()
                && values.stream().allMatch(id -> workflow.containsTaxonomyLeaf(rule.taxonomy(), id)));
    }

    @Transactional
    public void undo(Long eventId, AppUser user) {
        PaperEvent event = events.findById(eventId);
        if (event == null) throw new NotFoundException();
        Paper paper = event.paper;
        access.requireAdminLogicalFeed(paper.logicalFeed.id, user);
        // Serialize duplicate undo attempts and reload state after waiting for another writer.
        events.getEntityManager().refresh(paper, LockModeType.PESSIMISTIC_WRITE);
        events.getEntityManager().refresh(event);
        Long latest = events.latestStatusEventIds(List.of(paper.id)).get(paper.id);
        Availability decision = availability(event, latest);
        if (decision == null || !decision.available()) {
            throw new WebApplicationException(decision == null ? "This event cannot be undone." : decision.reason(),
                    Response.Status.CONFLICT);
        }
        StatusSnapshot before = event.statusBeforeJson == null
                ? new StatusSnapshot(decision.targetStatus(), List.of(), null, List.of())
                : StatusSnapshot.fromJson(event.statusBeforeJson);
        String oldStatus = paper.status;
        paper.status = before.status();
        paper.setEligibilityExclusionCriteriaIds(before.exclusions());
        paper.eligibilityExclusionNotes = before.exclusionNotes();
        paper.setEligibilityInclusionCriteriaIds(before.inclusions());
        event.revertedAt = Instant.now();
        eventService.log(paper, "STATE_REVERTED", "Undid event #" + event.id + ": " + oldStatus + " -> " + paper.status);
        git.syncLogicalFeed(paper.logicalFeed);
    }

    public record StatusSnapshot(String status, List<String> exclusions, String exclusionNotes, List<String> inclusions) {
        public static StatusSnapshot capture(Paper paper) {
            return new StatusSnapshot(paper.status, paper.eligibilityExclusionCriteriaIds(),
                    paper.eligibilityExclusionNotes, paper.eligibilityInclusionCriteriaIds());
        }

        public String toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("status", status);
            value.put("exclusions", exclusions);
            value.put("exclusionNotes", exclusionNotes);
            value.put("inclusions", inclusions);
            return JsonCodec.stringify(value);
        }

        public static StatusSnapshot fromJson(String json) {
            if (!(JsonCodec.parse(json) instanceof Map<?, ?> value) || !(value.get("status") instanceof String status)) {
                throw new IllegalArgumentException("Invalid status snapshot");
            }
            return new StatusSnapshot(status, strings(value.get("exclusions")),
                    value.get("exclusionNotes") instanceof String notes ? notes : null, strings(value.get("inclusions")));
        }

        private static List<String> strings(Object value) {
            if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
                throw new IllegalArgumentException("Invalid classification reasons");
            }
            return list.stream().map(String.class::cast).toList();
        }
    }
}
