package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Feed extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 120)
    public String name;

    @Column(nullable = false, unique = true, length = 1000)
    public String url;

    @Column(nullable = false)
    public Integer pollIntervalMinutes = 60;

    public Instant lastPolledAt;

    @Column(length = 1000)
    public String lastError;

    public Integer lastPollCreatedPaperCount;

    @Column(length = 64)
    public String defaultPaperStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    public LogicalFeed logicalFeed;

    @OneToMany(mappedBy = "feed", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<Paper> papers = new ArrayList<>();

    public String initialPaperStatus() {
        if (defaultPaperStatus != null && !defaultPaperStatus.isBlank()) {
            return defaultPaperStatus;
        }
        return logicalFeed.initialPaperStatus();
    }

    public int lastPollCreatedPaperCountValue() {
        return lastPollCreatedPaperCount == null || lastPollCreatedPaperCount < 0 ? 0 : lastPollCreatedPaperCount;
    }

    public boolean lastPollSucceeded() {
        return lastError == null || lastError.isBlank();
    }
}
