package top.nextnet.paper.monitor.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkflowStateConfig {

    private final List<Group> groups;

    private WorkflowStateConfig(List<Group> groups) {
        this.groups = List.copyOf(groups);
    }

    public static WorkflowStateConfig parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return new WorkflowStateConfig(List.of());
        }

        List<Group> groups = new ArrayList<>();
        Group currentGroup = null;

        for (String rawLine : yaml.split("\\R")) {
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }

            int indent = leadingSpaces(rawLine);
            String trimmed = rawLine.trim();
            if (!trimmed.startsWith("- ")) {
                throw new IllegalArgumentException("Workflow states must be a YAML list");
            }

            String value = trimmed.substring(2).trim();
            if (indent == 0) {
                if (value.endsWith(":")) {
                    String groupName = normalizeStateSegment(value.substring(0, value.length() - 1).trim());
                    currentGroup = new Group(groupName, new ArrayList<>());
                    groups.add(currentGroup);
                } else {
                    currentGroup = new Group(normalizeStateSegment(value), new ArrayList<>());
                    groups.add(currentGroup);
                    currentGroup = null;
                }
                continue;
            }

            if (indent > 0) {
                if (currentGroup == null) {
                    throw new IllegalArgumentException("Nested workflow states must belong to a parent state");
                }
                if (value.endsWith(":")) {
                    throw new IllegalArgumentException("Workflow states support at most two levels");
                }
                currentGroup.children.add(normalizeStateSegment(value));
            }
        }

        if (groups.isEmpty()) {
            throw new IllegalArgumentException("At least one workflow state is required");
        }

        validate(groups);
        return new WorkflowStateConfig(groups);
    }

    public List<Group> groups() {
        return groups;
    }

    public List<String> topLevelStates() {
        return groups.stream().map(Group::name).toList();
    }

    public List<String> leafStates() {
        List<String> leaves = new ArrayList<>();
        for (Group group : groups) {
            if (group.children.isEmpty()) {
                leaves.add(group.name);
                continue;
            }
            for (String child : group.children) {
                leaves.add(group.name + "/" + child);
            }
        }
        return leaves;
    }

    public boolean containsLeafState(String state) {
        return leafStates().contains(state);
    }

    public String topLevelStateOf(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        int separator = state.indexOf('/');
        return separator < 0 ? state : state.substring(0, separator);
    }

    public List<String> targetsBefore(String state) {
        String topLevelState = topLevelStateOf(state);
        int currentIndex = topLevelStates().indexOf(topLevelState);
        if (currentIndex <= 0) {
            return List.of();
        }
        List<String> targets = new ArrayList<>();
        for (int index = 0; index < currentIndex; index++) {
            targets.addAll(groups.get(index).leafStates());
        }
        return targets;
    }

    public List<String> targetsAfter(String state) {
        String topLevelState = topLevelStateOf(state);
        int currentIndex = topLevelStates().indexOf(topLevelState);
        if (currentIndex < 0 || currentIndex >= groups.size() - 1) {
            return List.of();
        }
        List<String> targets = new ArrayList<>();
        for (int index = currentIndex + 1; index < groups.size(); index++) {
            targets.addAll(groups.get(index).leafStates());
        }
        return targets;
    }

    public String initialPaperStatus() {
        for (Group group : groups) {
            if ("NEW".equals(group.name)) {
                return group.firstLeafState();
            }
        }
        for (Group group : groups) {
            if (group.children.isEmpty()) {
                return group.name;
            }
        }
        return groups.getFirst().firstLeafState();
    }

    public String toYaml() {
        StringBuilder builder = new StringBuilder();
        for (Group group : groups) {
            if (group.children.isEmpty()) {
                builder.append("- ").append(group.name).append("\n");
                continue;
            }
            builder.append("- ").append(group.name).append(":\n");
            for (String child : group.children) {
                builder.append("  - ").append(child).append("\n");
            }
        }
        return builder.toString().trim();
    }

    public String treeJson() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Group group : groups) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", group.name);
            item.put("children", group.children);
            items.add(item);
        }
        return JsonCodec.stringify(items);
    }

    public String topLevelToken() {
        return String.join("|", topLevelStates());
    }

    public static String normalizeStateSegment(String state) {
        if (state == null) {
            throw new IllegalArgumentException("Workflow state is required");
        }
        String normalized = state.trim().toUpperCase().replace(' ', '_');
        if (normalized.isEmpty() || normalized.contains("/")) {
            throw new IllegalArgumentException("Invalid workflow state: " + state);
        }
        return normalized;
    }

    private static int leadingSpaces(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static void validate(List<Group> groups) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (Group group : groups) {
            if (seen.put(group.name, group.name) != null) {
                throw new IllegalArgumentException("Duplicate workflow state: " + group.name);
            }
            if (group.children.isEmpty()) {
                continue;
            }
            for (String leaf : group.leafStates()) {
                if (seen.put(leaf, leaf) != null) {
                    throw new IllegalArgumentException("Duplicate workflow state: " + leaf);
                }
            }
        }
    }

    public record Group(String name, List<String> children) {
        public List<String> leafStates() {
            if (children.isEmpty()) {
                return List.of(name);
            }
            return children.stream().map((child) -> name + "/" + child).toList();
        }

        public String firstLeafState() {
            return children.isEmpty() ? name : name + "/" + children.getFirst();
        }
    }
}
