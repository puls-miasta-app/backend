package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.CommentReport;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.CommentReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface CommentReportRepository extends JpaRepository<CommentReport, Long> {

    boolean existsByCommentIdAndReporterId(Long commentId, Long reporterId);

    /*
     * Listing zgłoszeń: paginacja po samym ID (te metody niżej), a doładowanie wierszy
     * z komentarzem i pulsem odbywa się natywnym SQL przez JdbcTemplate w
     * PulseCommentService.loadReportResponses.
     *
     * Dlaczego nie JOIN FETCH cr+c+p przez JPQL: na stosie Hibernate 7 / Spring Boot 4 /
     * Connector-J 9 / MySQL 9 dowolna kombinacja JOIN FETCH wielu encji z kolumnami TEXT
     * (description, body, ai_note, admin_note, original_body) — niezależnie od WHERE
     * (id=?, id IN(?), status=? LIMIT ?) — kończy się SQLState S1009
     * "Statement.executeQuery() cannot issue statements that do not produce result sets".
     * Natywny SELECT przez JdbcTemplate omija query-builder Hibernate i działa.
     */

    @Query(value = "SELECT r.id FROM CommentReport r ORDER BY r.createdAt DESC",
            countQuery = "SELECT COUNT(r) FROM CommentReport r")
    Page<Long> findAllReportIds(Pageable pageable);

    @Query(value = "SELECT r.id FROM CommentReport r WHERE r.status = :status ORDER BY r.createdAt DESC",
            countQuery = "SELECT COUNT(r) FROM CommentReport r WHERE r.status = :status")
    Page<Long> findReportIdsByStatus(
            @Param("status") CommentReportStatus status,
            Pageable pageable);

    /**
     * Wywoływać tylko gdy scopeValues niepuste (puste IN → 1=0 → bug Connector-J S1009).
     */
    @Query(value = """
            SELECT r.id FROM CommentReport r
            JOIN r.comment c
            JOIN c.pulse p
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
    Page<Long> findReportIdsInScope(
            @Param("scopeColumn") String scopeColumn,
            @Param("scopeValues") Collection<String> scopeValues,
            Pageable pageable);

    @Query(value = """
            SELECT r.id FROM CommentReport r
            JOIN r.comment c
            JOIN c.pulse p
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
    Page<Long> findReportIdsInScopeByStatus(
            @Param("status") CommentReportStatus status,
            @Param("scopeColumn") String scopeColumn,
            @Param("scopeValues") Collection<String> scopeValues,
            Pageable pageable);


    /**
     * Ładuje raport razem z komentarzem i pulsem (JOIN FETCH).
     * Używane w reviewReport — umożliwia sprawdzenie scope na załadowanej encji
     * bez osobnego query (eliminuje TOCTOU existsByIdInScope + findById).
     */
}
