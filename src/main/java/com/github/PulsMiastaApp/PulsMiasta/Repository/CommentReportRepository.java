package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.CommentReport;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.CommentReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommentReportRepository extends JpaRepository<CommentReport, Long> {

    boolean existsByCommentIdAndReporterId(Long commentId, Long reporterId);

    /*
     * Listing zgłoszeń jest realizowany w dwóch krokach (zob. PulseCommentService.listReports):
     *   1) paginowane query po samym ID + count,
     *   2) doładowanie pełnych encji przez findByIdsWithFetch (bez LIMIT).
     *
     * Dlaczego nie JOIN FETCH + Pageable w jednym query:
     * kombinacja JOIN FETCH na 4 ścieżki (comment → pulse, comment → user, report → reporter)
     * + LIMIT-as-parameter na Hibernate 7 / Connector-J 9 / MySQL 9 powoduje, że serwer
     * odpowiada OK-packetem zamiast ResultSet, a sterownik rzuca SQLState S1009
     * "Statement.executeQuery() cannot issue statements that do not produce result sets".
     * Rozdzielenie eliminuje problem deterministycznie.
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
     * Ładuje encje raportów po ID z fetchem komentarza i pulsu, ale BEZ users.
     *
     * Dlaczego users nie są fetched: User ma kolumnę BINARY(16) `webauthn_user_handle`.
     * Wciągnięcie jej do masowego JOIN FETCH na stosie Hibernate 7 / Connector-J 9 / MySQL 9
     * powoduje desynchronizację protokołu i SQLState S1009 ("Statement.executeQuery()
     * cannot issue statements that do not produce result sets"). Trigger nie jest LIMIT,
     * tylko kolumna BINARY w wynikowym secie z 4 joinami.
     *
     * Reporter / commentAuthor / reviewer są wczytywane lazy z poziomu toReportResponse —
     * pojedyncze SELECT-y po PK są małe i nie hitują buga. Dla admin paginacji 20–50 rekordów
     * narzut N+1 jest akceptowalny.
     */
    @Query("""
            SELECT r FROM CommentReport r
            JOIN FETCH r.comment c
            JOIN FETCH c.pulse
            WHERE r.id IN :ids
            """)
    List<CommentReport> findByIdsWithFetch(@Param("ids") Collection<Long> ids);

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
            LEFT JOIN FETCH c.user
            WHERE r.id = :id
            """)
    Optional<CommentReport> findByIdWithCommentAndPulse(@Param("id") Long id);
}
