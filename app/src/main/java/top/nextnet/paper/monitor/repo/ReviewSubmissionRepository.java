package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.Review;
import top.nextnet.paper.monitor.model.ReviewSubmission;

@ApplicationScoped
public class ReviewSubmissionRepository implements PanacheRepository<ReviewSubmission> {

    public Optional<ReviewSubmission> findByReviewAndPaper(Review review, Paper paper) {
        return find("review = ?1 and paper = ?2", review, paper).firstResultOptional();
    }

    public Map<Long, ReviewSubmission> findByReviewIndexedByPaperId(Review review) {
        List<ReviewSubmission> submissions = find("""
                select submission from ReviewSubmission submission
                join fetch submission.paper
                where submission.review = ?1
                """, review).list();
        Map<Long, ReviewSubmission> indexed = new LinkedHashMap<>();
        for (ReviewSubmission submission : submissions) {
            indexed.put(submission.paper.id, submission);
        }
        return indexed;
    }

    public long deleteByReview(Review review) {
        return delete("review", review);
    }
}
