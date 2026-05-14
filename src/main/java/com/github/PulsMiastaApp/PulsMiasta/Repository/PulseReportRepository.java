package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseReport;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface PulseReportRepository extends JpaRepository<PulseReport, Long> {

    boolean existsByPulseIdAndReporterId(Long pulseId, Long reporterId);

    /*
     * Identyczny problem co w CommentReportRepository: JPQL JOIN FETCH PulseReport + Pulse + User
     * z kolumnami TEXT (Pulse.description, Pulse.ai_note) rzuca SQLState S1009 na stosie
     * Hibernate 7 / Connector-J 9 / MySQL 9 — niezależnie od LIMIT/IN.
     *
     * Wzór: paginacja po samym ID (tutaj), doładowanie wierszy natywnym SQL przez JdbcTemplate
     * (PulseReportService.loadReportResponses). Single-row review przez plain findById + lazy
     * w obrębie @Transactional.
     */

    @Query(value = "SELECT r.id FROM PulseReport r ORDER BY r.createdAt DESC",
            countQuery = "SELECT COUNT(r) FROM PulseReport r")
    Page<Long> findAllReportIds(Pageable pageable);

    @Query(value = "SELECT r.id FROM PulseReport r WHERE r.status = :status ORDER BY r.createdAt DESC",
            countQuery = "SELECT COUNT(r) FROM PulseReport r WHERE r.status = :status")
    Page<Long> findReportIdsByStatus(
            @Param("status") PulseReportStatus status,
            Pageable pageable);

    /** Wywoływać tylko gdy scopeValues niepuste (puste IN → 1=0 → bug Connector-J S1009). */
    @Query(value = """
            SELECT r.id FROM PulseReport r
            JOIN r.pulse p
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
    Page<Long> findReportIdsInScope(
            @Param("scopeColumn") String scopeColumn,
            @Param("scopeValues") Collection<String> scopeValues,
            Pageable pageable);

    @Query(value = """
            SELECT r.id FROM PulseReport r
            JOIN r.pulse p
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
    Page<Long> findReportIdsInScopeByStatus(
            @Param("status") PulseReportStatus status,
            @Param("scopeColumn") String scopeColumn,
            @Param("scopeValues") Collection<String> scopeValues,
            Pageable pageable);
}
