package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.CommentReport;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.CommentReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentReportRepository extends JpaRepository<CommentReport, Long> {

    boolean existsByCommentIdAndReporterId(Long commentId, Long reporterId);

    Page<CommentReport> findAllByStatus(CommentReportStatus status, Pageable pageable);

    /** Zgłoszenia w zasięgu admina — filtrowanie po lokalizacji pulsu. */
    @Query("""
            SELECT r FROM CommentReport r
            JOIN r.comment c
            JOIN c.pulse p
            WHERE (:status IS NULL OR r.status = :status)
              AND (:scopeColumn IS NULL OR
                  (:scopeColumn = 'city'        AND p.city        = :scopeValue) OR
                  (:scopeColumn = 'gmina'       AND p.gmina       = :scopeValue) OR
                  (:scopeColumn = 'powiat'      AND p.powiat      = :scopeValue) OR
                  (:scopeColumn = 'wojewodztwo' AND p.wojewodztwo = :scopeValue))
            ORDER BY r.createdAt DESC
            """)
    Page<CommentReport> findInScope(
            @Param("status") CommentReportStatus status,
            @Param("scopeColumn") String scopeColumn,
            @Param("scopeValue") String scopeValue,
            Pageable pageable);
}
