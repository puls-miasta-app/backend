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
     * Zgłoszenia bez filtrowania zakresu i statusu — dla SUPER_ADMIN.
     * Brak parametru nullable eliminuje SQLState S1009 MySQL Connector/J.
     */
    @Query(value = """
            SELECT r FROM PulseReport r
            JOIN FETCH r.pulse p
            JOIN FETCH r.reporter
            ORDER BY r.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM PulseReport r
            JOIN r.pulse p
            """)
    Page<PulseReport> findAllReports(Pageable pageable);

    /** Jak wyżej, ale z filtrem statusu. Wywoływać tylko gdy status != null. */
    @Query(value = """
            SELECT r FROM PulseReport r
            JOIN FETCH r.pulse p
            JOIN FETCH r.reporter
            WHERE r.status = :status
            ORDER BY r.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM PulseReport r
            JOIN r.pulse p
            WHERE r.status = :status
            """)
    Page<PulseReport> findAllReportsByStatus(
            @Param("status") PulseReportStatus status,
            Pageable pageable);

    /**
     * Zgłoszenia w zasięgu admina bez filtra statusu.
     * Wywoływać tylko gdy scopeValues jest niepuste.
     */
    @Query(value = """
            SELECT r FROM PulseReport r
            JOIN FETCH r.pulse p
            JOIN FETCH r.reporter
            WHERE (:scopeColumn = 'city'           AND p.city                         IN :scopeValues) OR
                  (:scopeColumn = 'gmina_id'       AND CAST(p.gminaId AS String)       IN :scopeValues) OR
                  (:scopeColumn = 'powiat_id'      AND CAST(p.powiatId AS String)      IN :scopeValues) OR
                  (:scopeColumn = 'wojewodztwo_id' AND CAST(p.wojewodztwoId AS String) IN :scopeValues)
            ORDER BY r.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM PulseReport r
            JOIN r.pulse p
            WHERE (:scopeColumn = 'city'           AND p.city                         IN :scopeValues) OR
                  (:scopeColumn = 'gmina_id'       AND CAST(p.gminaId AS String)       IN :scopeValues) OR
                  (:scopeColumn = 'powiat_id'      AND CAST(p.powiatId AS String)      IN :scopeValues) OR
                  (:scopeColumn = 'wojewodztwo_id' AND CAST(p.wojewodztwoId AS String) IN :scopeValues)
            """)
    Page<PulseReport> findInScope(
            @Param("scopeColumn") String scopeColumn,
            @Param("scopeValues") Collection<String> scopeValues,
            Pageable pageable);

    /** Jak wyżej, ale z filtrem statusu. Wywoływać tylko gdy status != null i scopeValues niepuste. */
    @Query(value = """
            SELECT r FROM PulseReport r
            JOIN FETCH r.pulse p
            JOIN FETCH r.reporter
            WHERE r.status = :status
              AND ((:scopeColumn = 'city'           AND p.city                         IN :scopeValues) OR
                   (:scopeColumn = 'gmina_id'       AND CAST(p.gminaId AS String)       IN :scopeValues) OR
                   (:scopeColumn = 'powiat_id'      AND CAST(p.powiatId AS String)      IN :scopeValues) OR
                   (:scopeColumn = 'wojewodztwo_id' AND CAST(p.wojewodztwoId AS String) IN :scopeValues))
            ORDER BY r.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(r) FROM PulseReport r
            JOIN r.pulse p
            WHERE r.status = :status
              AND ((:scopeColumn = 'city'           AND p.city                         IN :scopeValues) OR
                   (:scopeColumn = 'gmina_id'       AND CAST(p.gminaId AS String)       IN :scopeValues) OR
                   (:scopeColumn = 'powiat_id'      AND CAST(p.powiatId AS String)      IN :scopeValues) OR
                   (:scopeColumn = 'wojewodztwo_id' AND CAST(p.wojewodztwoId AS String) IN :scopeValues))
            """)
    Page<PulseReport> findInScopeByStatus(
            @Param("status") PulseReportStatus status,
            @Param("scopeColumn") String scopeColumn,
            @Param("scopeValues") Collection<String> scopeValues,
            Pageable pageable);

    @Query("""
            SELECT r FROM PulseReport r
            JOIN FETCH r.pulse p
            JOIN FETCH r.reporter
            WHERE r.id = :id
            """)
    Optional<PulseReport> findByIdWithPulse(@Param("id") Long id);
}
