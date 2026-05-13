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
     * Zgłoszenia bez filtrowania zakresu i statusu — dla SUPER_ADMIN.
     * Brak parametru nullable eliminuje SQLState S1009 MySQL Connector/J
     * (trigger: (:status IS NULL OR r.status = :status) z null parametrem).
     */
    @Query(value = """
            SELECT r FROM CommentReport r
            JOIN FETCH r.comment c
            JOIN FETCH c.pulse p
            JOIN FETCH r.reporter
            ORDER BY r.createdAt DESC
""",
            countQuery = """
            SELECT COUNT(r) FROM CommentReport r
            JOIN r.comment c
            JOIN c.pulse p
            """)
    Page<CommentReport> findAllReports(Pageable pageable);

    /** Jak wyżej, ale z filtrem statusu. Wywoływać tylko gdy status != null. */
    @Query(value = """
            SELECT r FROM CommentReport r
            JOIN FETCH r.comment c
            JOIN FETCH c.pulse p
            JOIN FETCH r.reporter
            WHERE r.status = :status
            ORDER BY r.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM CommentReport r
            JOIN r.comment c
            JOIN c.pulse p
            WHERE r.status = :status
            """)
    Page<CommentReport> findAllReportsByStatus(
            @Param("status") CommentReportStatus status,
            Pageable pageable);

    /**
     * Zgłoszenia w zasięgu admina bez filtra statusu.
     * Wywoływać tylko gdy scopeValues jest niepuste — puste IN generuje 1=0 i triggeruje
     * bug MySQL Connector/J S1009.
     */
    @Query(value = """
            SELECT r FROM CommentReport r
            JOIN FETCH r.comment c
            JOIN FETCH c.pulse p
            JOIN FETCH r.reporter
            WHERE (:scopeColumn = 'city'           AND p.city                         IN :scopeValues) OR
                  (:scopeColumn = 'gmina_id'       AND CAST(p.gminaId AS String)       IN :scopeValues) OR
                  (:scopeColumn = 'powiat_id'      AND CAST(p.powiatId AS String)      IN :scopeValues) OR
                  (:scopeColumn = 'wojewodztwo_id' AND CAST(p.wojewodztwoId AS String) IN :scopeValues)
            ORDER BY r.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM CommentReport r
            JOIN r.comment c
            JOIN c.pulse p
            WHERE (:scopeColumn = 'city'           AND p.city                         IN :scopeValues) OR
                  (:scopeColumn = 'gmina_id'       AND CAST(p.gminaId AS String)       IN :scopeValues) OR
                  (:scopeColumn = 'powiat_id'      AND CAST(p.powiatId AS String)      IN :scopeValues) OR
                  (:scopeColumn = 'wojewodztwo_id' AND CAST(p.wojewodztwoId AS String) IN :scopeValues)
            """)
    Page<CommentReport> findInScope(
            @Param("scopeColumn") String scopeColumn,
            @Param("scopeValues") Collection<String> scopeValues,
            Pageable pageable);

    /** Jak wyżej, ale z filtrem statusu. Wywoływać tylko gdy status != null i scopeValues niepuste. */
    @Query(value = """
            SELECT r FROM CommentReport r
            JOIN FETCH r.comment c
            JOIN FETCH c.pulse p
            JOIN FETCH r.reporter
            WHERE r.status = :status
              AND ((:scopeColumn = 'city'           AND p.city                         IN :scopeValues) OR
                   (:scopeColumn = 'gmina_id'       AND CAST(p.gminaId AS String)       IN :scopeValues) OR
                   (:scopeColumn = 'powiat_id'      AND CAST(p.powiatId AS String)      IN :scopeValues) OR
                   (:scopeColumn = 'wojewodztwo_id' AND CAST(p.wojewodztwoId AS String) IN :scopeValues))
            ORDER BY r.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM CommentReport r
            JOIN r.comment c
            JOIN c.pulse p
            WHERE r.status = :status
              AND ((:scopeColumn = 'city'           AND p.city                         IN :scopeValues) OR
                   (:scopeColumn = 'gmina_id'       AND CAST(p.gminaId AS String)       IN :scopeValues) OR
                   (:scopeColumn = 'powiat_id'      AND CAST(p.powiatId AS String)      IN :scopeValues) OR
                   (:scopeColumn = 'wojewodztwo_id' AND CAST(p.wojewodztwoId AS String) IN :scopeValues))
            """)
    Page<CommentReport> findInScopeByStatus(
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
            WHERE r.id = :id
            """)
    Optional<CommentReport> findByIdWithCommentAndPulse(@Param("id") Long id);
}
