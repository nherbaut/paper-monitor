package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Entity
public class LogicalFeed extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 120)
    public String name;

    @Column(length = 500)
    public String description;

    @Column(length = 2000)
    public String workflowStates;

    @Column(length = 4000)
    public String stateGitLinks;

    @OneToMany(mappedBy = "logicalFeed", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    public List<Feed> feeds = new ArrayList<>();

    @Transient
    public Map<String, Long> paperCountsByState = new LinkedHashMap<>();

    @Transient
    public List<StateGitRemote> gitRemotes = new ArrayList<>();

    @Transient
    public String gitSyncError;

    public List<String> workflowStateList() {
        if (workflowStates == null || workflowStates.isBlank()) {
            return List.of();
        }

        List<String> values = Arrays.stream(workflowStates.split("\\R"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .collect(Collectors.toList());

        return values;
    }

    public String workflowStatesText() {
        return String.join("\n", workflowStateList());
    }

    public String workflowStatesToken() {
        return String.join("|", workflowStateList());
    }

    public String initialPaperStatus() {
        if (workflowStateList().isEmpty()) {
            throw new IllegalStateException("Logical feed has no workflow states configured");
        }
        return workflowStateList().get(0);
    }

    public Map<String, String> stateGitLinkMap() {
        Map<String, String> values = new LinkedHashMap<>();
        if (stateGitLinks == null || stateGitLinks.isBlank()) {
            return values;
        }
        for (String line : stateGitLinks.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                continue;
            }
            values.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
        }
        return values;
    }

    public void setStateGitLinkMap(Map<String, String> values) {
        this.stateGitLinks = values.entrySet().stream()
                .map((entry) -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    public String paperCountsToken() {
        return workflowStateList().stream()
                .map((state) -> state + ":" + paperCountsByState.getOrDefault(state, 0L))
                .collect(Collectors.joining("|"));
    }

    public String paperCountsSummary() {
        return workflowStateList().stream()
                .map((state) -> state + " " + paperCountsByState.getOrDefault(state, 0L))
                .collect(Collectors.joining(" · "));
    }

    public String readerLabel() {
        String summary = paperCountsSummary();
        if (summary.isBlank()) {
            return name;
        }
        return name + " (" + summary + ")";
    }

    public static class StateGitRemote {
        public final String state;
        public final String url;

        public StateGitRemote(String state, String url) {
            this.state = state;
            this.url = url;
        }
    }
}
