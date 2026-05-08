package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.CommentReport;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.CommentReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface CommentReportRepository extends JpaRepository<CommentReport, Long> {

    boolean existsByCommentIdAndReporterId(Long commentId, Long reporterId);

    /**
     * Zgłoszenia w zasięgu admina — JOIN FETCH eliminuje N+1 dla wszystkich
     * lazy relacji używanych w toReportResponse (reporter, reviewedBy, comment, pulse).
     */
    @Query(value = """
            SELECT r FROM CommentReport r
            JOIN FETCH r.comment c
            JOIN FETCH c.pulse p
            JOIN FETCH r.reporter
            LEFT JOIN FETCH r.reviewedBy
            WHERE (:status IS NULL OR r.status = :status)
              AND (:scopeColumn IS NULL OR
                  (:scopeColumn = 'city'        AND p.city        IN :scopeValues) OR
                  (:scopeColumn = 'gmina'       AND p.gmina       IN :scopeValues) OR
                  (:scopeColumn = 'powiat'      AND p.powiat      IN :scopeValues) OR
                  (:scopeColumn = 'wojewodztwo' AND p.wojewodztwo IN :scopeValues))
            ORDER BY r.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM CommentReport r
            JOIN r.comment c
            JOIN c.pulse p
            WHERE (:status IS NULL OR r.status = :status)
              AND (:scopeColumn IS NULL OR
                  (:scopeColumn = 'city'        AND p.city        IN :scopeValues) OR
                  (:scopeColumn = 'gmina'       AND p.gmina       IN :scopeValues) OR
                  (:scopeColumn = 'powiat'      AND p.powiat      IN :scopeValues) OR
                  (:scopeColumn = 'wojewodztwo' AND p.wojewodztwo IN :scopeValues))
            """)
    Page<CommentReport> findInScope(
            @Param("status") CommentReportStatus status,
            @Param("scopeColumn") String scopeColumn,
            @Param("scopeValues") Collection<String> scopeValues,
            Pageable pageable);

    /**
     * Ładuje raport razem z komentarzem i pulsem (JOIN FETCH).
     * Używane w reviewReport — umożliwia sprawdzenie scope na załadowanej encji
     * bez osobnego query (eliminuje TOCTOU existsByIdInScope + findById).
     */
    @Query("""
            SELECT r FROM CommentReport r
            JOIN FETCH r.comment c
            JOIN FETCH c.pulse
            JOIN FETCH r.reporter
            LEFT JOIN FETCH r.reviewedBy
            WHERE r.id = :id
            """)
    Optional<CommentReport> findByIdWithCommentAndPulse(@Param("id") Long id);
}
