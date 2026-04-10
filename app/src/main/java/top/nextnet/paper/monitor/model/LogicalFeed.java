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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import top.nextnet.paper.monitor.service.WorkflowStateConfig;

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

    @Column(length = 255)
    public String gitRepoToken;

    @Column(length = 64)
    public String lastProcessedGitCommit;

    @OneToMany(mappedBy = "logicalFeed", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    public List<Feed> feeds = new ArrayList<>();

    @Transient
    public Map<String, Long> paperCountsByState = new LinkedHashMap<>();

    @Transient
    public String gitRepoUrl;

    @Transient
    public String gitSyncError;

    public List<String> workflowStateList() {
        return workflowConfig().leafStates();
    }

    public List<String> topLevelWorkflowStateList() {
        return workflowConfig().topLevelStates();
    }

    public WorkflowStateConfig workflowConfig() {
        return WorkflowStateConfig.parse(workflowStates);
    }

    public String workflowStatesText() {
        return workflowConfig().toYaml();
    }

    public String workflowStatesToken() {
        return workflowConfig().topLevelToken();
    }

    public String workflowTreeJson() {
        return workflowConfig().treeJson();
    }

    public String initialPaperStatus() {
        if (workflowStateList().isEmpty()) {
            throw new IllegalStateException("Logical feed has no workflow states configured");
        }
        return workflowConfig().initialPaperStatus();
    }

    public String paperCountsToken() {
        return topLevelWorkflowStateList().stream()
                .map((state) -> state + ":" + paperCountsByState.getOrDefault(state, 0L))
                .collect(Collectors.joining("|"));
    }

    public String paperCountsSummary() {
        return topLevelWorkflowStateList().stream()
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
}
