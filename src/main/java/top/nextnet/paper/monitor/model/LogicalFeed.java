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

    @OneToMany(mappedBy = "logicalFeed", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    public List<Feed> feeds = new ArrayList<>();

    @Transient
    public Map<String, Long> paperCountsByState = new LinkedHashMap<>();

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
}
