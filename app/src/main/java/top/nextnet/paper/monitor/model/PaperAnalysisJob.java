package top.nextnet.paper.monitor.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class PaperAnalysisJob extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long userId;

    @Column(nullable = false)
    public Long paperId;

    public Long reviewId;

    @Column(nullable = false, length = 1000)
    public String paperTitle;

    @Column(nullable = false, length = 24)
    public String status = "QUEUED";

    @Column(length = 96)
    public String phase;

    @Column(length = 24)
    public String trigger;

    public Integer completedSteps = 0;

    public Integer totalSteps = 3;

    @Column(length = 2000)
    public String error;

    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean notesUpdated;

    @Column(nullable = false, columnDefinition = "boolean default false")
    public boolean reviewDraftUpdated;

    @Column(nullable = false)
    public Instant startedAt;

    public Instant finishedAt;
}
