package top.nextnet.paper.monitor.repo;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.Review;

@ApplicationScoped
public class ReviewRepository implements PanacheRepository<Review> {

    public Optional<Review> findByOwnerAndLogicalFeed(AppUser owner, LogicalFeed logicalFeed) {
        return find("owner = ?1 and logicalFeed = ?2", owner, logicalFeed).firstResultOptional();
    }

    public List<Review> findByOwner(AppUser owner) {
        return find("""
                select review from Review review
                join fetch review.logicalFeed
                where review.owner = ?1
                order by review.updatedAt desc
                """, owner).list();
    }

    public List<Review> findByLogicalFeed(LogicalFeed logicalFeed) {
        return find("""
                select review from Review review
                join fetch review.owner
                where review.logicalFeed = ?1
                order by review.updatedAt desc, review.id desc
                """, logicalFeed).list();
    }

    public Optional<Review> findReadableById(Long id, AppUser owner) {
        return find("""
                select review from Review review
                join fetch review.logicalFeed
                join fetch review.owner
                where review.id = ?1 and review.owner = ?2
                """, id, owner).firstResultOptional();
    }
}
