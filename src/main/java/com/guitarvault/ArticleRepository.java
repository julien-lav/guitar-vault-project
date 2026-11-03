package com.guitarvault;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<Article, Long> {
    Optional<Article> findByIdAndPublishedTrue(Long id);
    long countByPublished(boolean published);
    long countByAuthorEmailIgnoreCaseAndPublished(String email, boolean published);

    @Query("""
        select a from Article a where (:published is null or a.published = :published)
        and (:category = '' or a.category = :category)
        and (:authorEmail = '' or lower(a.author.email) = lower(:authorEmail))
        and (:query = '' or lower(a.title) like :query escape '!' or lower(a.summary) like :query escape '!')
        """)
    Page<Article> search(@Param("published") Boolean published, @Param("category") String category,
                        @Param("query") String query, @Param("authorEmail") String authorEmail, Pageable pageable);
}
