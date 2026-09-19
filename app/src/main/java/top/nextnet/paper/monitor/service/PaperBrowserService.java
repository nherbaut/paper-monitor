package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Paper;

@ApplicationScoped
public class PaperBrowserService {

    public static final int DEFAULT_LIMIT = 30;
    public static final int MIN_LIMIT = 10;
    public static final int MAX_LIMIT = 100;

    private final EntityManager entityManager;

    public PaperBrowserService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page page(LogicalFeed feed, Query query, String rawCursor, Integer requestedLimit) {
        int limit = Math.max(MIN_LIMIT, Math.min(MAX_LIMIT,
                requestedLimit == null ? DEFAULT_LIMIT : requestedLimit));
        Cursor cursor = decodeCursor(rawCursor);
        BuiltQuery built = buildQuery(feed, query, cursor, false);
        TypedQuery<Object[]> statement = entityManager.createQuery(
                "select p.id, p.logicalFeed.id, p.logicalFeed.name, p.status, p.recordType, "
                        + "p.uploadedPdfPath, p.title, p.authors, p.publishedOn, p.publisher, "
                        + "f.name, f.url, p.summary, p.sourceLink, p.openAccessLink, p.tags, p.discoveredAt "
                        + "from Paper p join p.feed f " + built.where() + " " + orderBy(), Object[].class);
        bind(statement, built.parameters());
        List<BrowserPaper> rows = statement.setMaxResults(limit + 1).getResultList().stream()
                .map(this::browserPaper)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        String nextCursor = hasMore && !rows.isEmpty() ? encodeCursor(rows.getLast()) : null;
        long total = rawCursor == null || rawCursor.isBlank() ? count(feed, query) : -1L;
        return new Page(List.copyOf(rows), nextCursor, total);
    }

    public long count(LogicalFeed feed, Query query) {
        BuiltQuery built = buildQuery(feed, query, null, false);
        TypedQuery<Long> statement = entityManager.createQuery(
                "select count(p) from Paper p join p.feed f " + built.where(), Long.class);
        bind(statement, built.parameters());
        return statement.getSingleResult();
    }

    public List<Paper> allMatching(LogicalFeed feed, Query query) {
        BuiltQuery built = buildQuery(feed, query, null, false);
        TypedQuery<Paper> statement = entityManager.createQuery(
                "select p from Paper p join fetch p.logicalFeed join fetch p.feed f "
                        + built.where() + " " + orderBy(), Paper.class);
        bind(statement, built.parameters());
        return statement.getResultList();
    }

    public List<Facet> facets(LogicalFeed feed, Query activeQuery) {
        BuiltQuery built = buildQuery(feed, activeQuery, null, true);
        TypedQuery<Object[]> statement = entityManager.createQuery(
                "select p.tags, p.status, p.uploadedPdfPath, p.sourceLink, p.openAccessLink, f.url "
                        + "from Paper p join p.feed f " + built.where(), Object[].class);
        bind(statement, built.parameters());

        String activeState = activeQuery.tags().stream()
                .filter((tag) -> tag != null && tag.startsWith("state:"))
                .map((tag) -> WorkflowStateConfig.normalizeStateId(tag.substring("state:".length())))
                .findFirst().orElse(null);
        Map<String, Long> stateCounts = new LinkedHashMap<>();
        Map<String, TagCount> tagCounts = new LinkedHashMap<>();
        List<String> workflowStates = feed.workflowStateList();
        String initialStatus = WorkflowStateConfig.normalizeStateId(feed.initialPaperStatus());
        long pdfCount = 0L;
        long newCount = 0L;
        for (Object[] row : statement.getResultList()) {
            String status = (String) row[1];
            for (String state : workflowStates) {
                if (stateMatches(status, state)) stateCounts.merge(state, 1L, Long::sum);
            }
            if (activeState != null && !stateMatches(status, activeState)) continue;
            if (row[2] != null) pdfCount += 1L;
            if (status != null && WorkflowStateConfig.normalizeStateId(status).equals(initialStatus)
                    && isRssUrl((String) row[5])) {
                newCount += 1L;
            }
            for (String tag : tags((String) row[0], (String) row[3], (String) row[4])) {
                String key = tag.toLowerCase(Locale.ROOT);
                tagCounts.compute(key, (ignored, current) -> current == null
                        ? new TagCount(tag, 1L) : new TagCount(current.label(), current.count() + 1L));
            }
        }

        List<Facet> facets = new ArrayList<>();
        for (String state : workflowStates) {
            facets.add(new Facet("state:" + state, state, stateCounts.getOrDefault(state, 0L), true));
        }
        facets.add(new Facet("has-pdf", "Has PDF", pdfCount, true));
        facets.add(new Facet("new-papers", "New papers", newCount, true));
        for (TagCount tag : tagCounts.values()) {
            facets.add(new Facet(tag.label(), tag.label(), tag.count(), false));
        }
        facets.sort((left, right) -> {
            int secondary = Boolean.compare(left.secondary(), right.secondary());
            if (secondary != 0) return secondary;
            int count = Long.compare(right.count(), left.count());
            return count != 0 ? count : left.label().compareToIgnoreCase(right.label());
        });
        return facets;
    }

    public List<Facet> stateFacets(LogicalFeed feed) {
        List<Object[]> rows = entityManager.createQuery(
                        "select p.status, count(p) from Paper p where p.logicalFeed = :feed group by p.status",
                        Object[].class)
                .setParameter("feed", feed)
                .getResultList();
        List<Facet> facets = new ArrayList<>();
        for (String state : feed.workflowStateList()) {
            long count = rows.stream()
                    .filter((row) -> stateMatches((String) row[0], state))
                    .mapToLong((row) -> (Long) row[1])
                    .sum();
            facets.add(new Facet("state:" + state, state, count, true));
        }
        return facets;
    }

    private BuiltQuery buildQuery(LogicalFeed feed, Query query, Cursor cursor, boolean ignoreStateTags) {
        List<String> predicates = new ArrayList<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        predicates.add("p.logicalFeed = :feed");
        parameters.put("feed", feed);

        if (query.classificationQueue()) {
            if (!query.classificationStates().isEmpty()) {
                predicates.add("p.status in :classificationStates");
                parameters.put("classificationStates", query.classificationStates());
            } else {
                predicates.add("p.status = :initialStatus");
                predicates.add("(f.url like 'http://%' or f.url like 'https://%')");
                parameters.put("initialStatus", feed.initialPaperStatus());
            }
        } else if (query.status() != null && !query.status().isBlank()) {
            String status = WorkflowStateConfig.normalizeStateId(query.status());
            if (status.contains("/")) {
                predicates.add("p.status = :status");
                parameters.put("status", status);
            } else {
                predicates.add("(p.status = :status or p.status like :statusPrefix)");
                parameters.put("status", status);
                parameters.put("statusPrefix", escapeLike(status) + "/%");
            }
        }

        Search search = parseSearch(query.search());
        if (search.text() != null) {
            predicates.add("(lower(p.title) like :text escape '\\' or lower(coalesce(p.summary, '')) like :text escape '\\' "
                    + "or lower(coalesce(p.authors, '')) like :text escape '\\' "
                    + "or lower(coalesce(p.publisher, '')) like :text escape '\\')");
            parameters.put("text", "%" + escapeLike(search.text().toLowerCase(Locale.ROOT)) + "%");
        }
        if (search.after() != null) {
            predicates.add("p.publishedOn " + (search.afterInclusive() ? ">=" : ">") + " :publishedAfter");
            parameters.put("publishedAfter", search.after());
        }
        if (search.before() != null) {
            predicates.add("p.publishedOn " + (search.beforeInclusive() ? "<=" : "<") + " :publishedBefore");
            parameters.put("publishedBefore", search.before());
        }

        int tagIndex = 0;
        for (String rawTag : query.tags()) {
            String tag = rawTag == null ? "" : rawTag.trim();
            if (tag.isBlank()) continue;
            if (ignoreStateTags && tag.startsWith("state:")) continue;
            String parameter = "tag" + tagIndex++;
            if (tag.startsWith("state:")) {
                String state = WorkflowStateConfig.normalizeStateId(tag.substring("state:".length()));
                predicates.add("(p.status = :" + parameter + " or p.status like :" + parameter + "Prefix)");
                parameters.put(parameter, state);
                parameters.put(parameter + "Prefix", escapeLike(state) + "/%");
            } else if ("has-pdf".equals(tag)) {
                predicates.add("p.uploadedPdfPath is not null");
            } else if ("new-papers".equals(tag)) {
                predicates.add("p.status = :" + parameter);
                predicates.add("(f.url like 'http://%' or f.url like 'https://%')");
                parameters.put(parameter, feed.initialPaperStatus());
            } else if ("arxiv".equalsIgnoreCase(tag)) {
                predicates.add("(" + storedTagPredicate(parameter)
                        + " or lower(p.sourceLink) like '%arxiv.org%' or lower(coalesce(p.openAccessLink, '')) like '%arxiv.org%')");
                parameters.put(parameter, "%\n" + escapeLike(tag.toLowerCase(Locale.ROOT)) + "\n%");
            } else if ("hal".equalsIgnoreCase(tag)) {
                predicates.add("(" + storedTagPredicate(parameter)
                        + " or lower(p.sourceLink) like '%hal.science%' or lower(coalesce(p.openAccessLink, '')) like '%hal.science%' "
                        + "or lower(p.sourceLink) like '%archives-ouvertes.fr%' "
                        + "or lower(coalesce(p.openAccessLink, '')) like '%archives-ouvertes.fr%')");
                parameters.put(parameter, "%\n" + escapeLike(tag.toLowerCase(Locale.ROOT)) + "\n%");
            } else {
                predicates.add(storedTagPredicate(parameter));
                parameters.put(parameter, "%\n" + escapeLike(tag.toLowerCase(Locale.ROOT)) + "\n%");
            }
        }

        if (cursor != null) {
            parameters.put("cursorDiscovered", cursor.discoveredAt());
            parameters.put("cursorId", cursor.id());
            if (cursor.publishedOn() == null) {
                predicates.add("p.publishedOn is null and (p.discoveredAt < :cursorDiscovered "
                        + "or (p.discoveredAt = :cursorDiscovered and p.id < :cursorId))");
            } else {
                parameters.put("cursorPublished", cursor.publishedOn());
                predicates.add("(p.publishedOn < :cursorPublished or p.publishedOn is null "
                        + "or (p.publishedOn = :cursorPublished and (p.discoveredAt < :cursorDiscovered "
                        + "or (p.discoveredAt = :cursorDiscovered and p.id < :cursorId))))");
            }
        }
        return new BuiltQuery("where " + String.join(" and ", predicates), parameters);
    }

    private String storedTagPredicate(String parameter) {
        return "lower(concat(concat('\n', coalesce(p.tags, '')), '\n')) like :" + parameter + " escape '\\'";
    }

    private String orderBy() {
        return "order by case when p.publishedOn is null then 1 else 0 end, "
                + "p.publishedOn desc, p.discoveredAt desc, p.id desc";
    }

    private void bind(TypedQuery<?> query, Map<String, Object> parameters) {
        parameters.forEach(query::setParameter);
    }

    private BrowserPaper browserPaper(Object[] row) {
        return new BrowserPaper(
                (Long) row[0], (Long) row[1], (String) row[2], (String) row[3], (String) row[4],
                (String) row[5], (String) row[6], (String) row[7], (LocalDate) row[8], (String) row[9],
                (String) row[10], (String) row[11], (String) row[12], (String) row[13], (String) row[14],
                (String) row[15], (Instant) row[16]);
    }

    private String encodeCursor(BrowserPaper paper) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("publishedOn", paper.publishedOn() == null ? null : paper.publishedOn().toString());
        value.put("discoveredAt", paper.discoveredAt().toString());
        value.put("id", paper.id());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JsonCodec.stringify(value).getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Object parsed = JsonCodec.parse(new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException();
            Object published = map.get("publishedOn");
            return new Cursor(
                    published == null ? null : LocalDate.parse(String.valueOf(published)),
                    java.time.Instant.parse(String.valueOf(map.get("discoveredAt"))),
                    Long.parseLong(String.valueOf(map.get("id"))));
        } catch (RuntimeException exception) {
            throw new WebApplicationException("Invalid paper browser cursor", Response.Status.BAD_REQUEST);
        }
    }

    private Search parseSearch(String raw) {
        if (raw == null || raw.isBlank()) return Search.EMPTY;
        List<String> text = new ArrayList<>();
        Bound lower = null;
        Bound upper = null;
        for (String token : raw.replaceAll("[\\u200B-\\u200D\\uFEFF]", "").trim().split("\\s+")) {
            String cleaned = token.replaceAll("[.,;:!?]+$", "");
            if (!cleaned.toLowerCase(Locale.ROOT).startsWith("date:")) {
                text.add(token);
                continue;
            }
            String value = cleaned.substring(5);
            String operator = "=";
            if (value.startsWith("<=") || value.startsWith(">=")) {
                operator = value.substring(0, 2);
                value = value.substring(2);
            } else if (value.startsWith("<") || value.startsWith(">") || value.startsWith("=")) {
                operator = value.substring(0, 1);
                value = value.substring(1);
            }
            DateRange range = dateRange(value);
            if (range == null) {
                text.add(token);
                continue;
            }
            switch (operator) {
                case ">" -> lower = later(lower, new Bound(range.end(), false));
                case ">=" -> lower = later(lower, new Bound(range.start(), true));
                case "<" -> upper = earlier(upper, new Bound(range.start(), false));
                case "<=" -> upper = earlier(upper, new Bound(range.end(), true));
                default -> {
                    lower = later(lower, new Bound(range.start(), true));
                    upper = earlier(upper, new Bound(range.end(), true));
                }
            }
        }
        String query = String.join(" ", text).replaceAll("\\s+", " ").trim();
        return new Search(query.isBlank() ? null : query,
                lower == null ? null : lower.date(), lower != null && lower.inclusive(),
                upper == null ? null : upper.date(), upper != null && upper.inclusive());
    }

    private Bound later(Bound current, Bound candidate) {
        if (current == null || candidate.date().isAfter(current.date())) return candidate;
        if (candidate.date().isBefore(current.date())) return current;
        return new Bound(current.date(), current.inclusive() && candidate.inclusive());
    }

    private Bound earlier(Bound current, Bound candidate) {
        if (current == null || candidate.date().isBefore(current.date())) return candidate;
        if (candidate.date().isAfter(current.date())) return current;
        return new Bound(current.date(), current.inclusive() && candidate.inclusive());
    }

    private DateRange dateRange(String value) {
        try {
            if (value.matches("\\d{4}")) {
                int year = Integer.parseInt(value);
                return new DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
            }
            if (value.matches("\\d{4}-\\d{2}")) {
                LocalDate start = LocalDate.parse(value + "-01");
                return new DateRange(start, start.withDayOfMonth(start.lengthOfMonth()));
            }
            if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate date = LocalDate.parse(value);
                return new DateRange(date, date);
            }
        } catch (DateTimeParseException | NumberFormatException ignored) {
        }
        return null;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private boolean stateMatches(String status, String state) {
        if (status == null || state == null) return false;
        String normalizedStatus = WorkflowStateConfig.normalizeStateId(status);
        String normalizedState = WorkflowStateConfig.normalizeStateId(state);
        return normalizedStatus.equals(normalizedState) || normalizedStatus.startsWith(normalizedState + "/");
    }

    private boolean isRssUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private static List<String> tags(String stored, String sourceLink, String openAccessLink) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (stored != null) {
            for (String value : stored.split("\\R")) {
                if (!value.isBlank()) tags.putIfAbsent(value.trim().toLowerCase(Locale.ROOT), value.trim());
            }
        }
        if (hasHost(sourceLink, "arxiv.org") || hasHost(openAccessLink, "arxiv.org")) tags.putIfAbsent("arxiv", "arxiv");
        if (hasHost(sourceLink, "hal.science") || hasHost(openAccessLink, "hal.science")
                || hasHost(sourceLink, "archives-ouvertes.fr") || hasHost(openAccessLink, "archives-ouvertes.fr")) {
            tags.putIfAbsent("hal", "hal");
        }
        return List.copyOf(tags.values());
    }

    private static boolean hasHost(String link, String host) {
        if (link == null) return false;
        try {
            String actual = java.net.URI.create(link).getHost();
            return actual != null && (actual.equalsIgnoreCase(host)
                    || actual.toLowerCase(Locale.ROOT).endsWith("." + host.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public record Query(String status, List<String> tags, String search,
                        boolean classificationQueue, List<String> classificationStates) {
        public Query {
            tags = tags == null ? List.of() : tags.stream().filter(java.util.Objects::nonNull).distinct().toList();
            classificationStates = classificationStates == null ? List.of() : List.copyOf(classificationStates);
        }
    }

    public record BrowserPaper(Long id, Long logicalFeedId, String logicalFeedName, String status, String recordType,
                               String uploadedPdfPath, String title, String authors, LocalDate publishedOn,
                               String publisher, String feedName, String feedUrl, String summary, String sourceLink,
                               String openAccessLink, String storedTags, Instant discoveredAt) {
        public String topLevelStatus() {
            String value = status == null || status.isBlank() ? "NEW" : status;
            int separator = value.indexOf('/');
            return separator < 0 ? value : value.substring(0, separator);
        }

        public String recordTypeValue() {
            return Paper.TYPE_GRAY_LITERATURE.equals(recordType) ? Paper.TYPE_GRAY_LITERATURE : Paper.TYPE_PAPER;
        }

        public String tagsToken() {
            return String.join("|", tags(storedTags, sourceLink, openAccessLink));
        }
    }

    public record Page(List<BrowserPaper> items, String nextCursor, long total) {}
    public record Facet(String key, String label, long count, boolean secondary) {}
    private record TagCount(String label, long count) {}
    private record Cursor(LocalDate publishedOn, java.time.Instant discoveredAt, Long id) {}
    private record BuiltQuery(String where, Map<String, Object> parameters) {}
    private record DateRange(LocalDate start, LocalDate end) {}
    private record Bound(LocalDate date, boolean inclusive) {}
    private record Search(String text, LocalDate after, boolean afterInclusive,
                          LocalDate before, boolean beforeInclusive) {
        private static final Search EMPTY = new Search(null, null, false, null, false);
    }
}
