package top.nextnet.paper.monitor.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;

public final class WorkflowStateConfig {

    private static final int VERSION = 2;

    private final String initialState;
    private final List<State> states;
    private final List<Transition> transitions;
    private final Map<String, Taxonomy> taxonomies;
    private final Map<String, State> statesById;
    private final Map<String, List<String>> transitionTargetsBySource;
    private final List<Group> groups;

    private WorkflowStateConfig(
            String initialState,
            List<State> states,
            List<Transition> transitions,
            Map<String, Taxonomy> taxonomies
    ) {
        this.initialState = initialState;
        this.states = List.copyOf(states);
        this.transitions = List.copyOf(transitions);
        this.taxonomies = Map.copyOf(taxonomies);
        this.statesById = states.stream().collect(Collectors.toMap(State::id, (state) -> state, (left, right) -> left, LinkedHashMap::new));
        this.transitionTargetsBySource = buildTransitionTargets(this.transitions);
        this.groups = buildGroups(this.states);
    }

    public static WorkflowStateConfig parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return legacy(List.of());
        }
        Object parsed;
        try {
            parsed = new Yaml().load(yaml);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid workflow YAML");
        }
        if (parsed instanceof List<?> list) {
            return legacy(list);
        }
        if (parsed instanceof Map<?, ?> map) {
            return v2(map);
        }
        throw new IllegalArgumentException("Workflow YAML must be either a list or a map");
    }

    public static String normalizeTaxonomyYaml(String yaml, String defaultLabel) {
        return parseStandaloneTaxonomy(yaml, defaultLabel).toYaml();
    }

    public static String taxonomyJson(String yaml, String defaultId, String defaultLabel) {
        Taxonomy taxonomy = parseStandaloneTaxonomy(yaml, defaultLabel, defaultId);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", taxonomy.id());
        item.put("label", taxonomy.label());
        item.put("values", criterionItems(taxonomy.values()));
        return JsonCodec.stringify(item);
    }

    public static boolean standaloneTaxonomyContainsLeaf(String yaml, String taxonomyId, String defaultLabel, String criterionId) {
        Taxonomy taxonomy = parseStandaloneTaxonomy(yaml, defaultLabel, taxonomyId);
        return criterionId != null && taxonomy.leafIds().contains(normalizeStateSegment(criterionId));
    }

    public static Taxonomy standaloneTaxonomy(String yaml, String defaultId, String defaultLabel) {
        return parseStandaloneTaxonomy(yaml, defaultLabel, defaultId);
    }

    private static WorkflowStateConfig legacy(List<?> list) {
        if (list.isEmpty()) {
            throw new IllegalArgumentException("At least one workflow state is required");
        }
        List<Group> legacyGroups = parseLegacyGroups(list);
        List<State> states = new ArrayList<>();
        for (Group group : legacyGroups) {
            if (group.children().isEmpty()) {
                states.add(new State(group.name(), humanize(group.name()), null, false, Requirements.none(), Report.none()));
                continue;
            }
            for (String child : group.children()) {
                String id = group.name() + "/" + child;
                states.add(new State(id, humanize(child), group.name(), false, Requirements.none(), Report.none()));
            }
        }
        String initialState = legacyInitialState(legacyGroups);
        List<Transition> transitions = new ArrayList<>();
        List<String> ids = states.stream().map(State::id).toList();
        for (String from : ids) {
            List<String> to = ids.stream().filter((candidate) -> !candidate.equals(from)).toList();
            transitions.add(new Transition(from, to));
        }
        return new WorkflowStateConfig(initialState, states, transitions, Map.of());
    }

    private static WorkflowStateConfig v2(Map<?, ?> rawMap) {
        int version = intValue(rawMap.get("version"), VERSION);
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported workflow version: " + version);
        }
        List<State> states = parseStates(rawMap.get("states"));
        if (states.isEmpty()) {
            throw new IllegalArgumentException("At least one workflow state is required");
        }
        validateStateIds(states);
        String configuredInitialState = normalizeStateId(stringValue(rawMap.get("initial_state")));
        String initialState = configuredInitialState == null ? inferInitialState(states) : configuredInitialState;
        if (states.stream().noneMatch((state) -> state.id().equals(initialState))) {
            throw new IllegalArgumentException("initial_state must reference an existing state");
        }
        List<Transition> transitions = parseTransitions(rawMap.get("transitions"), states);
        Map<String, Taxonomy> taxonomies = parseTaxonomies(rawMap.get("taxonomies"));
        validateRequirements(states, taxonomies);
        return new WorkflowStateConfig(initialState, states, transitions, taxonomies);
    }

    public List<Group> groups() {
        return groups;
    }

    public List<State> states() {
        return states;
    }

    public List<Transition> transitions() {
        return transitions;
    }

    public List<String> topLevelStates() {
        return groups.stream().map(Group::name).toList();
    }

    public List<String> leafStates() {
        return states.stream().map(State::id).toList();
    }

    public boolean containsLeafState(String state) {
        return state != null && statesById.containsKey(normalizeStateId(state));
    }

    public String topLevelStateOf(String state) {
        String normalized = normalizeStateId(state);
        State workflowState = statesById.get(normalized);
        if (workflowState == null) {
            if (normalized == null) {
                return null;
            }
            int separator = normalized.indexOf('/');
            return separator < 0 ? normalized : normalized.substring(0, separator);
        }
        return workflowState.group() == null ? workflowState.id() : workflowState.group();
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
        return initialState;
    }

    public boolean allowsTransition(String from, String to) {
        String normalizedFrom = normalizeStateId(from);
        String normalizedTo = normalizeStateId(to);
        if (normalizedFrom == null || normalizedTo == null) {
            return false;
        }
        if (!statesById.containsKey(normalizedTo)) {
            return false;
        }
        return transitionTargetsBySource.getOrDefault(normalizedFrom, List.of()).contains(normalizedTo);
    }

    public Requirements requirementsFor(String state) {
        State workflowState = statesById.get(normalizeStateId(state));
        return workflowState == null ? Requirements.none() : workflowState.requirements();
    }

    public Taxonomy taxonomy(String name) {
        if (name == null) {
            return null;
        }
        return taxonomies.get(normalizeStateSegment(name));
    }

    public boolean containsTaxonomyLeaf(String taxonomyName, String criterionId) {
        Taxonomy taxonomy = taxonomy(taxonomyName);
        if (taxonomy == null || criterionId == null) {
            return false;
        }
        return taxonomy.leafIds().contains(normalizeStateSegment(criterionId));
    }

    public String toYaml() {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, 0, "version: " + VERSION);
        appendLine(builder, 0, "");
        appendLine(builder, 0, "initial_state: " + initialState);
        appendLine(builder, 0, "");
        appendLine(builder, 0, "states:");
        for (State state : states) {
            appendLine(builder, 1, "- id: " + state.id());
            appendLine(builder, 2, "label: " + quoteIfNeeded(state.label()));
            if (state.group() != null) {
                appendLine(builder, 2, "group: " + state.group());
            }
            if (state.terminal()) {
                appendLine(builder, 2, "terminal: true");
            }
            if (state.requirements().hasEntries()) {
                appendLine(builder, 2, "requires:");
                if (state.requirements().exclusionCriterion() != null) {
                    appendLine(builder, 3, "exclusion_criterion:");
                    appendLine(builder, 4, "taxonomy: " + state.requirements().exclusionCriterion().taxonomy());
                    appendLine(builder, 4, "exactly: " + state.requirements().exclusionCriterion().exactly());
                }
                if (state.requirements().inclusionCriteria() != null) {
                    appendLine(builder, 3, "inclusion_criteria:");
                    appendLine(builder, 4, "taxonomy: " + state.requirements().inclusionCriteria().taxonomy());
                    appendLine(builder, 4, "min: " + state.requirements().inclusionCriteria().min());
                }
                if (state.requirements().exclusionNotesOptional()) {
                    appendLine(builder, 3, "exclusion_notes: optional");
                }
                if (state.requirements().inclusionNotesOptional()) {
                    appendLine(builder, 3, "inclusion_notes: optional");
                }
            }
            if (state.report().prismaBucket() != null) {
                appendLine(builder, 2, "report:");
                appendLine(builder, 3, "prisma_bucket: " + state.report().prismaBucket());
            }
        }
        appendLine(builder, 0, "");
        appendLine(builder, 0, "transitions:");
        for (Transition transition : transitions) {
            appendLine(builder, 1, "- from: " + transition.from());
            appendLine(builder, 2, "to:");
            for (String target : transition.to()) {
                appendLine(builder, 3, "- " + target);
            }
        }
        if (!taxonomies.isEmpty()) {
            appendLine(builder, 0, "");
            appendLine(builder, 0, "taxonomies:");
            for (Taxonomy taxonomy : taxonomies.values()) {
                appendLine(builder, 1, taxonomy.id() + ":");
                if (taxonomy.label() != null) {
                    appendLine(builder, 2, "label: " + quoteIfNeeded(taxonomy.label()));
                }
                appendLine(builder, 2, "values:");
                appendTaxonomyCriteria(builder, 3, taxonomy.values());
            }
        }
        return builder.toString().trim();
    }

    public String treeJson() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Group group : groups) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", group.name());
            item.put("children", group.children());
            items.add(item);
        }
        return JsonCodec.stringify(items);
    }

    public String topLevelToken() {
        return String.join("|", topLevelStates());
    }

    public String configJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", VERSION);
        payload.put("initialState", initialState);
        List<Map<String, Object>> stateItems = new ArrayList<>();
        for (State state : states) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", state.id());
            item.put("label", state.label());
            item.put("group", state.group());
            item.put("terminal", state.terminal());
            Map<String, Object> requires = new LinkedHashMap<>();
            if (state.requirements().exclusionCriterion() != null) {
                requires.put("exclusionCriterion", Map.of(
                        "taxonomy", state.requirements().exclusionCriterion().taxonomy(),
                        "exactly", state.requirements().exclusionCriterion().exactly()));
            }
            if (state.requirements().inclusionCriteria() != null) {
                requires.put("inclusionCriteria", Map.of(
                        "taxonomy", state.requirements().inclusionCriteria().taxonomy(),
                        "min", state.requirements().inclusionCriteria().min()));
            }
            if (state.requirements().exclusionNotesOptional()) {
                requires.put("exclusionNotes", "optional");
            }
            if (state.requirements().inclusionNotesOptional()) {
                requires.put("inclusionNotes", "optional");
            }
            item.put("requires", requires);
            item.put("report", Map.of(
                    "prismaBucket", state.report().prismaBucket() == null ? "" : state.report().prismaBucket()));
            stateItems.add(item);
        }
        payload.put("states", stateItems);
        List<Map<String, Object>> transitionItems = new ArrayList<>();
        for (Transition transition : transitions) {
            transitionItems.add(Map.of("from", transition.from(), "to", transition.to()));
        }
        payload.put("transitions", transitionItems);
        Map<String, Object> taxonomyItems = new LinkedHashMap<>();
        for (Taxonomy taxonomy : taxonomies.values()) {
            Map<String, Object> taxonomyItem = new LinkedHashMap<>();
            taxonomyItem.put("label", taxonomy.label());
            taxonomyItem.put("values", criterionItems(taxonomy.values()));
            taxonomyItems.put(taxonomy.id(), taxonomyItem);
        }
        payload.put("taxonomies", taxonomyItems);
        return JsonCodec.stringify(payload);
    }

    public static String normalizeStateSegment(String state) {
        if (state == null) {
            throw new IllegalArgumentException("Workflow state is required");
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (normalized.isEmpty() || normalized.contains("/")) {
            throw new IllegalArgumentException("Invalid workflow state: " + state);
        }
        return normalized;
    }

    public static String normalizeStateId(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String[] segments = state.trim().split("/");
        List<String> normalizedSegments = new ArrayList<>();
        for (String segment : segments) {
            normalizedSegments.add(normalizeStateSegment(segment));
        }
        return String.join("/", normalizedSegments);
    }

    private static List<Group> parseLegacyGroups(List<?> rawItems) {
        List<Group> groups = new ArrayList<>();
        for (Object rawItem : rawItems) {
            if (rawItem instanceof String string) {
                groups.add(new Group(normalizeStateSegment(string), List.of()));
                continue;
            }
            if (!(rawItem instanceof Map<?, ?> map) || map.size() != 1) {
                throw new IllegalArgumentException("Workflow states must be a YAML list");
            }
            Map.Entry<?, ?> entry = map.entrySet().iterator().next();
            String groupName = normalizeStateSegment(String.valueOf(entry.getKey()));
            List<String> children = asList(entry.getValue()).stream()
                    .map((value) -> normalizeStateSegment(String.valueOf(value)))
                    .toList();
            groups.add(new Group(groupName, children));
        }
        validateLegacyGroups(groups);
        return groups;
    }

    private static List<State> parseStates(Object rawStates) {
        List<Object> rows = asList(rawStates);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("At least one workflow state is required");
        }
        List<State> states = new ArrayList<>();
        for (Object row : rows) {
            Map<?, ?> item = asMap(row, "Each workflow state must be an object");
            String id = normalizeStateId(stringValue(item.get("id")));
            if (id == null) {
                throw new IllegalArgumentException("Workflow state id is required");
            }
            String label = firstNonBlank(stringValue(item.get("label")), humanize(id));
            String group = normalizeOptionalSegment(stringValue(item.get("group")));
            boolean terminal = boolValue(item.get("terminal"));
            Requirements requirements = parseRequirements(item.get("requires"));
            Report report = parseReport(item.get("report"));
            states.add(new State(id, label, group, terminal, requirements, report));
        }
        return states;
    }

    private static List<Transition> parseTransitions(Object rawTransitions, List<State> states) {
        List<String> stateIds = states.stream().map(State::id).toList();
        List<Transition> transitions = new ArrayList<>();
        for (Object row : asList(rawTransitions)) {
            Map<?, ?> item = asMap(row, "Each transition must be an object");
            String from = normalizeStateId(stringValue(item.get("from")));
            if (from == null) {
                throw new IllegalArgumentException("Transition source is required");
            }
            if (!stateIds.contains(from)) {
                throw new IllegalArgumentException("Transition source references an unknown state: " + from);
            }
            List<String> to = asList(item.get("to")).stream()
                    .map((value) -> normalizeStateId(String.valueOf(value)))
                    .filter(Objects::nonNull)
                    .toList();
            if (to.isEmpty()) {
                throw new IllegalArgumentException("Transition targets are required for " + from);
            }
            for (String target : to) {
                if (!stateIds.contains(target)) {
                    throw new IllegalArgumentException("Transition target references an unknown state: " + target);
                }
            }
            transitions.add(new Transition(from, to));
        }
        return transitions;
    }

    private static Map<String, Taxonomy> parseTaxonomies(Object rawTaxonomies) {
        if (rawTaxonomies == null) {
            return Map.of();
        }
        Map<?, ?> values = asMap(rawTaxonomies, "Taxonomies must be an object");
        LinkedHashMap<String, Taxonomy> taxonomies = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String id = normalizeStateSegment(String.valueOf(entry.getKey()));
            Map<?, ?> item = asMap(entry.getValue(), "Taxonomy definition must be an object");
            String label = firstNonBlank(stringValue(item.get("label")), humanize(id));
            List<Criterion> criteria = parseCriteria(item.get("values"));
            taxonomies.put(id, new Taxonomy(id, label, criteria));
        }
        return taxonomies;
    }

    private static Taxonomy parseStandaloneTaxonomy(String yaml, String defaultLabel) {
        return parseStandaloneTaxonomy(yaml, "TAXONOMY", defaultLabel);
    }

    private static Taxonomy parseStandaloneTaxonomy(String yaml, String defaultLabel, String defaultId) {
        Object parsed;
        try {
            parsed = new Yaml().load(yaml == null || yaml.isBlank() ? "[]" : yaml);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid taxonomy YAML");
        }
        List<Criterion> criteria;
        if (parsed instanceof List<?> list) {
            criteria = parseCriteria(list);
        } else if (parsed instanceof Map<?, ?> map) {
            Map<String, Taxonomy> taxonomies = parseTaxonomies(map);
            if (taxonomies.size() != 1) {
                throw new IllegalArgumentException("Standalone taxonomy YAML must be a list or a single taxonomy object");
            }
            return taxonomies.values().iterator().next();
        } else {
            throw new IllegalArgumentException("Taxonomy YAML must be a list");
        }
        return new Taxonomy(normalizeStateSegment(defaultId), defaultLabel, criteria);
    }

    private static List<Criterion> parseCriteria(Object rawCriteria) {
        List<Criterion> criteria = new ArrayList<>();
        for (Object row : asList(rawCriteria)) {
            if (row instanceof String string) {
                String id = normalizeStateSegment(string);
                criteria.add(new Criterion(id, humanize(id), null, List.of()));
                continue;
            }
            Map<?, ?> item = asMap(row, "Taxonomy criteria must be objects");
            String id = normalizeStateSegment(stringValue(item.get("id")));
            if (id == null) {
                throw new IllegalArgumentException("Taxonomy criterion id is required");
            }
            String label = firstNonBlank(stringValue(item.get("label")), humanize(id));
            String description = normalizeText(stringValue(item.get("description")));
            List<Criterion> children = parseCriteria(item.get("children"));
            criteria.add(new Criterion(id, label, description, children));
        }
        validateCriteria(criteria);
        return criteria;
    }

    private static Requirements parseRequirements(Object rawRequirements) {
        if (rawRequirements == null) {
            return Requirements.none();
        }
        Map<?, ?> item = asMap(rawRequirements, "Workflow state requires must be an object");
        CriterionRule exclusionCriterion = parseCriterionRule(item.get("exclusion_criterion"), true);
        CriterionRule inclusionCriteria = parseCriterionRule(item.get("inclusion_criteria"), false);
        boolean exclusionNotesOptional = "optional".equalsIgnoreCase(stringValue(item.get("exclusion_notes")));
        boolean inclusionNotesOptional = "optional".equalsIgnoreCase(stringValue(item.get("inclusion_notes")));
        return new Requirements(exclusionCriterion, inclusionCriteria, exclusionNotesOptional, inclusionNotesOptional);
    }

    private static CriterionRule parseCriterionRule(Object rawRule, boolean exact) {
        if (rawRule == null) {
            return null;
        }
        Map<?, ?> item = asMap(rawRule, "Criterion rules must be objects");
        String taxonomy = normalizeOptionalSegment(stringValue(item.get("taxonomy")));
        if (taxonomy == null) {
            throw new IllegalArgumentException("Criterion rule taxonomy is required");
        }
        if (exact) {
            int count = intValue(item.get("exactly"), 1);
            if (count < 1) {
                throw new IllegalArgumentException("Criterion rule exactly must be positive");
            }
            return new CriterionRule(taxonomy, count, null);
        }
        int min = intValue(item.get("min"), 1);
        if (min < 1) {
            throw new IllegalArgumentException("Criterion rule min must be positive");
        }
        return new CriterionRule(taxonomy, null, min);
    }

    private static Report parseReport(Object rawReport) {
        if (rawReport == null) {
            return Report.none();
        }
        Map<?, ?> item = asMap(rawReport, "Workflow state report must be an object");
        String prismaBucket = normalizeOptionalSegment(stringValue(item.get("prisma_bucket")));
        return new Report(prismaBucket);
    }

    private static void validateStateIds(List<State> states) {
        Set<String> seen = new LinkedHashSet<>();
        for (State state : states) {
            if (!seen.add(state.id())) {
                throw new IllegalArgumentException("Duplicate workflow state: " + state.id());
            }
        }
    }

    private static void validateRequirements(List<State> states, Map<String, Taxonomy> taxonomies) {
        for (State state : states) {
            Requirements requirements = state.requirements();
            if (requirements.exclusionCriterion() != null && !taxonomies.containsKey(requirements.exclusionCriterion().taxonomy())) {
                throw new IllegalArgumentException("Unknown taxonomy referenced by state " + state.id());
            }
            if (requirements.inclusionCriteria() != null && !taxonomies.containsKey(requirements.inclusionCriteria().taxonomy())) {
                throw new IllegalArgumentException("Unknown taxonomy referenced by state " + state.id());
            }
        }
    }

    private static void validateLegacyGroups(List<Group> groups) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (Group group : groups) {
            if (group.children().isEmpty()) {
                if (seen.put(group.name(), group.name()) != null) {
                    throw new IllegalArgumentException("Duplicate workflow state: " + group.name());
                }
                continue;
            }
            if (seen.put(group.name(), group.name()) != null) {
                throw new IllegalArgumentException("Duplicate workflow state: " + group.name());
            }
            for (String leaf : group.leafStates()) {
                if (seen.put(leaf, leaf) != null) {
                    throw new IllegalArgumentException("Duplicate workflow state: " + leaf);
                }
            }
        }
    }

    private static void validateCriteria(List<Criterion> criteria) {
        Set<String> seen = new LinkedHashSet<>();
        for (Criterion criterion : criteria) {
            validateCriterionRecursively(criterion, seen);
        }
    }

    private static void validateCriterionRecursively(Criterion criterion, Set<String> seen) {
        if (!seen.add(criterion.id())) {
            throw new IllegalArgumentException("Duplicate taxonomy criterion: " + criterion.id());
        }
        for (Criterion child : criterion.children()) {
            validateCriterionRecursively(child, seen);
        }
    }

    private static List<Group> buildGroups(List<State> states) {
        LinkedHashMap<String, List<String>> childrenByGroup = new LinkedHashMap<>();
        for (State state : states) {
            if (state.group() == null) {
                childrenByGroup.putIfAbsent(state.id(), new ArrayList<>());
                continue;
            }
            childrenByGroup.computeIfAbsent(state.group(), (ignored) -> new ArrayList<>()).add(childSegment(state));
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : childrenByGroup.entrySet()) {
            groups.add(new Group(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return groups;
    }

    private static Map<String, List<String>> buildTransitionTargets(List<Transition> transitions) {
        LinkedHashMap<String, List<String>> targets = new LinkedHashMap<>();
        for (Transition transition : transitions) {
            targets.put(transition.from(), List.copyOf(transition.to()));
        }
        return targets;
    }

    private static String childSegment(State state) {
        if (state.group() == null) {
            return state.id();
        }
        String prefix = state.group() + "/";
        return state.id().startsWith(prefix) ? state.id().substring(prefix.length()) : state.id();
    }

    private static String inferInitialState(List<State> states) {
        for (State state : states) {
            if ("NEW".equals(state.id()) || state.id().startsWith("NEW/")) {
                return state.id();
            }
        }
        return states.getFirst().id();
    }

    private static String legacyInitialState(List<Group> groups) {
        for (Group group : groups) {
            if ("NEW".equals(group.name())) {
                return group.firstLeafState();
            }
        }
        return groups.getFirst().firstLeafState();
    }

    private static int intValue(Object rawValue, int defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(rawValue).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected an integer value");
        }
    }

    private static boolean boolValue(Object rawValue) {
        if (rawValue instanceof Boolean value) {
            return value;
        }
        return rawValue != null && Boolean.parseBoolean(String.valueOf(rawValue));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected a YAML list");
        }
        return (List<Object>) list;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> asMap(Object value, String message) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(message);
        }
        return map;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String normalizeOptionalSegment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeStateSegment(value);
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String humanize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String leaf = value.contains("/") ? value.substring(value.lastIndexOf('/') + 1) : value;
        String normalized = leaf.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static String quoteIfNeeded(String value) {
        if (value == null) {
            return "\"\"";
        }
        if (value.matches("[A-Za-z0-9_ /.-]+")) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static void appendLine(StringBuilder builder, int indentLevel, String content) {
        if (content.isEmpty()) {
            builder.append('\n');
            return;
        }
        builder.append("  ".repeat(Math.max(0, indentLevel))).append(content).append('\n');
    }

    private static void appendTaxonomyCriteria(StringBuilder builder, int indentLevel, Collection<Criterion> criteria) {
        for (Criterion criterion : criteria) {
            appendLine(builder, indentLevel, "- id: " + criterion.id());
            appendLine(builder, indentLevel + 1, "label: " + quoteIfNeeded(criterion.label()));
            if (criterion.description() != null) {
                appendLine(builder, indentLevel + 1, "description: " + quoteIfNeeded(criterion.description()));
            }
            if (!criterion.children().isEmpty()) {
                appendLine(builder, indentLevel + 1, "children:");
                appendTaxonomyCriteria(builder, indentLevel + 2, criterion.children());
            }
        }
    }

    private static List<Map<String, Object>> criterionItems(List<Criterion> criteria) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Criterion criterion : criteria) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", criterion.id());
            item.put("label", criterion.label());
            if (criterion.description() != null) {
                item.put("description", criterion.description());
            }
            item.put("children", criterionItems(criterion.children()));
            items.add(item);
        }
        return items;
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

    public record State(
            String id,
            String label,
            String group,
            boolean terminal,
            Requirements requirements,
            Report report
    ) {
    }

    public record Transition(String from, List<String> to) {
    }

    public record Requirements(
            CriterionRule exclusionCriterion,
            CriterionRule inclusionCriteria,
            boolean exclusionNotesOptional,
            boolean inclusionNotesOptional
    ) {
        public static Requirements none() {
            return new Requirements(null, null, false, false);
        }

        public boolean hasEntries() {
            return exclusionCriterion != null || inclusionCriteria != null || exclusionNotesOptional || inclusionNotesOptional;
        }
    }

    public record CriterionRule(String taxonomy, Integer exactly, Integer min) {
    }

    public record Report(String prismaBucket) {
        public static Report none() {
            return new Report(null);
        }
    }

    public record Taxonomy(String id, String label, List<Criterion> values) {
        public Set<String> leafIds() {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (Criterion criterion : values) {
                criterion.collectLeafIds(result);
            }
            return result;
        }

        public String toYaml() {
            StringBuilder builder = new StringBuilder();
            appendTaxonomyCriteria(builder, 0, values);
            return builder.toString().trim();
        }
    }

    public record Criterion(String id, String label, String description, List<Criterion> children) {
        public void collectLeafIds(Set<String> sink) {
            if (children.isEmpty()) {
                sink.add(id);
                return;
            }
            for (Criterion child : children) {
                child.collectLeafIds(sink);
            }
        }
    }
}
