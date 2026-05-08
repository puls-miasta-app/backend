package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseReport;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface PulseReportRepository extends JpaRepository<PulseReport, Long> {

    boolean existsByPulseIdAndReporterId(Long pulseId, Long reporterId);

    /**
     * Zgłoszenia bez filtrowania zakresu — dla SUPER_ADMIN.
     * Osobna metoda eliminuje generowanie przez Hibernate pustego IN (→ 1=0 → ?='city' and 1=0),
     * który myli klasyfikator MySQL Connector/J i powoduje SQLState S1009.
     */
    @Query(value = """
            SELECT r FROM PulseReport r
            JOIN FETCH r.pulse p
            JOIN FETCH r.reporter
            LEFT JOIN FETCH r.reviewedBy
            WHERE (:status IS NULL OR r.status = :status)
            ORDER BY r.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM PulseReport r
            JOIN r.pulse p
            WHERE (:status IS NULL OR r.status = :status)
            """)
    Page<PulseReport> findAllReports(
            @Param("status") PulseReportStatus status,
            Pageable pageable);

    @Query(value = """
            SELECT r FROM PulseReport r
            JOIN FETCH r.pulse p
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
            SELECT COUNT(r) FROM PulseReport r
            JOIN r.pulse p
            WHERE (:status IS NULL OR r.status = :status)
              AND (:scopeColumn IS NULL OR
                  (:scopeColumn = 'city'        AND p.city        IN :scopeValues) OR
                  (:scopeColumn = 'gmina'       AND p.gmina       IN :scopeValues) OR
                  (:scopeColumn = 'powiat'      AND p.powiat      IN :scopeValues) OR
                  (:scopeColumn = 'wojewodztwo' AND p.wojewodztwo IN :scopeValues))
            """)
    Page<PulseReport> findInScope(
            @Param("status") PulseReportStatus status,
            @Param("scopeColumn") String scopeColumn,
            @Param("scopeValues") Collection<String> scopeValues,
            Pageable pageable);

    @Query("""
            SELECT r FROM PulseReport r
            JOIN FETCH r.pulse p
            JOIN FETCH r.reporter
            LEFT JOIN FETCH r.reviewedBy
            WHERE r.id = :id
            """)
    Optional<PulseReport> findByIdWithPulse(@Param("id") Long id);
}
