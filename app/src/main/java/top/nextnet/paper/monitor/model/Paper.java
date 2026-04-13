package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Entity
public class Paper extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 1000)
    public String title;

    @Column(nullable = false, unique = true, length = 1000)
    public String sourceLink;

    @Column(length = 1000)
    public String openAccessLink;

    @Column(length = 1000)
    public String uploadedPdfPath;

    @Column(length = 255)
    public String uploadedPdfFileName;

    @Column(length = 4000)
    public String summary;

    @Column(length = 20000)
    public String notes;

    @Column(length = 2000)
    public String tags;

    @Column(length = 1000)
    public String authors;

    @Column(length = 255)
    public String publisher;

    public LocalDate publishedOn;

    @Column(length = 64)
    public String status = "NEW";

    @Column(nullable = false)
    public Instant discoveredAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public Feed feed;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public LogicalFeed logicalFeed;

    @Transient
    public boolean viewerCanEdit;

    public String topLevelStatus() {
        if (status == null || status.isBlank()) {
            return "NEW";
        }
        int separator = status.indexOf('/');
        return separator < 0 ? status : status.substring(0, separator);
    }

    public List<String> tagList() {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split("\\R"))
                .map(String::trim)
                .filter((value) -> !value.isBlank())
                .distinct()
                .toList();
    }

    public String tagsToken() {
        return tagList().stream().collect(Collectors.joining("|"));
    }

    public static String normalizeTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return null;
        }
        List<String> orderedTags = Arrays.stream(rawTags.replace(",", "\n").split("\\R"))
                .map(String::trim)
                .filter((value) -> !value.isBlank())
                .collect(Collectors.toMap(
                        (value) -> value.toLowerCase(Locale.ROOT),
                        (value) -> value,
                        (left, right) -> left))
                .values()
                .stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return orderedTags.isEmpty() ? null : String.join("\n", orderedTags);
    }
}
