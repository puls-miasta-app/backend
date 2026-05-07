package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseReport;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PulseReportRepository extends JpaRepository<PulseReport, Long> {

    boolean existsByPulseIdAndReporterId(Long pulseId, Long reporterId);

    /**
     * Zgłoszenia w zasięgu admina — JOIN FETCH eliminuje N+1 dla wszystkich
     * lazy relacji używanych w toReportResponse (reporter, reviewedBy, pulse).
     */
    @Query(value = """
            SELECT r FROM PulseReport r
            JOIN FETCH r.pulse p
            JOIN FETCH r.reporter
            LEFT JOIN FETCH r.reviewedBy
            WHERE (:status IS NULL OR r.status = :status)
              AND (:scopeColumn IS NULL OR
                  (:scopeColumn = 'city'        AND p.city        = :scopeValue) OR
                  (:scopeColumn = 'gmina'       AND p.gmina       = :scopeValue) OR
                  (:scopeColumn = 'powiat'      AND p.powiat      = :scopeValue) OR
                  (:scopeColumn = 'wojewodztwo' AND p.wojewodztwo = :scopeValue))
            ORDER BY r.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM PulseReport r
            JOIN r.pulse p
            WHERE (:status IS NULL OR r.status = :status)
              AND (:scopeColumn IS NULL OR
                  (:scopeColumn = 'city'        AND p.city        = :scopeValue) OR
                  (:scopeColumn = 'gmina'       AND p.gmina       = :scopeValue) OR
                  (:scopeColumn = 'powiat'      AND p.powiat      = :scopeValue) OR
                  (:scopeColumn = 'wojewodztwo' AND p.wojewodztwo = :scopeValue))
            """)
    Page<PulseReport> findInScope(
            @Param("status") PulseReportStatus status,
            @Param("scopeColumn") String scopeColumn,
            @Param("scopeValue") String scopeValue,
            Pageable pageable);

    /**
     * Ładuje raport razem z pulsem (JOIN FETCH).
     * Używane w reviewReport — umożliwia sprawdzenie scope na załadowanej encji
     * bez osobnego query (eliminuje TOCTOU existsByIdInScope + findById).
     */
    @Query("""
            SELECT r FROM PulseReport r
            JOIN FETCH r.pulse p
            JOIN FETCH r.reporter
            LEFT JOIN FETCH r.reviewedBy
            WHERE r.id = :id
            """)
    Optional<PulseReport> findByIdWithPulse(@Param("id") Long id);
}
