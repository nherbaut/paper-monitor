package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        TypedQuery<Paper> statement = entityManager.createQuery(
                "select p from Paper p join fetch p.logicalFeed join fetch p.feed f "
                        + built.where() + " " + orderBy(), Paper.class);
        bind(statement, built.parameters());
        List<Paper> rows = statement.setMaxResults(limit + 1).getResultList();
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
        List<String> storedTagRows = entityManager.createQuery(
                        "select p.tags from Paper p where p.logicalFeed = :feed and p.tags is not null",
                        String.class)
                .setParameter("feed", feed)
                .getResultList();
        Set<String> tags = new LinkedHashSet<>();
        for (String row : storedTagRows) {
            if (row == null) continue;
            for (String tag : row.split("\\R")) {
                if (!tag.isBlank()) tags.add(tag.trim());
            }
        }
        tags.add("arxiv");
        tags.add("hal");

        List<Facet> facets = new ArrayList<>();
        for (String state : feed.workflowStateList()) {
            String key = "state:" + state;
            facets.add(new Facet(key, state, count(feed, activeQuery.withAdditionalTag(key)), true));
        }
        facets.add(new Facet("has-pdf", "Has PDF", count(feed, activeQuery.withAdditionalTag("has-pdf")), true));
        facets.add(new Facet("new-papers", "New papers", count(feed, activeQuery.withAdditionalTag("new-papers")), true));
        for (String tag : tags) {
            long count = count(feed, activeQuery.withAdditionalTag(tag));
            if (count > 0) facets.add(new Facet(tag, tag, count, false));
        }
        facets.sort((left, right) -> {
            int secondary = Boolean.compare(left.secondary(), right.secondary());
            if (secondary != 0) return secondary;
            int count = Long.compare(right.count(), left.count());
            return count != 0 ? count : left.label().compareToIgnoreCase(right.label());
        });
        return facets;
    }

    public List<Facet> stateFacets(LogicalFeed feed, Query activeQuery) {
        List<Facet> facets = new ArrayList<>();
        for (String state : feed.workflowStateList()) {
            String key = "state:" + state;
            facets.add(new Facet(key, state, count(feed, activeQuery.withAdditionalTag(key)), true));
        }
        return facets;
    }

    private BuiltQuery buildQuery(LogicalFeed feed, Query query, Cursor cursor, boolean ignoreTags) {
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

        if (!ignoreTags) {
            int tagIndex = 0;
            for (String rawTag : query.tags()) {
                String tag = rawTag == null ? "" : rawTag.trim();
                if (tag.isBlank()) continue;
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

    private String encodeCursor(Paper paper) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("publishedOn", paper.publishedOn == null ? null : paper.publishedOn.toString());
        value.put("discoveredAt", paper.discoveredAt.toString());
        value.put("id", paper.id);
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

    public record Query(String status, List<String> tags, String search,
                        boolean classificationQueue, List<String> classificationStates) {
        public Query {
            tags = tags == null ? List.of() : tags.stream().filter(java.util.Objects::nonNull).distinct().toList();
            classificationStates = classificationStates == null ? List.of() : List.copyOf(classificationStates);
        }

        public Query withAdditionalTag(String tag) {
            List<String> values = new ArrayList<>(tags);
            if (tag.startsWith("state:")) values.removeIf(value -> value.startsWith("state:"));
            if (!values.contains(tag)) values.add(tag);
            return new Query(status, values, search, classificationQueue, classificationStates);
        }
    }

    public record Page(List<Paper> items, String nextCursor, long total) {}
    public record Facet(String key, String label, long count, boolean secondary) {}
    private record Cursor(LocalDate publishedOn, java.time.Instant discoveredAt, Long id) {}
    private record BuiltQuery(String where, Map<String, Object> parameters) {}
    private record DateRange(LocalDate start, LocalDate end) {}
    private record Bound(LocalDate date, boolean inclusive) {}
    private record Search(String text, LocalDate after, boolean afterInclusive,
                          LocalDate before, boolean beforeInclusive) {
        private static final Search EMPTY = new Search(null, null, false, null, false);
    }
}
