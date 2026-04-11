package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Report;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportPriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

    List<Report> findAllByUserId(Long userId);

    List<Report> findAllByStatus(ReportStatus status);

    /**
     * Single-report fetch that eagerly loads the photos collection — used by the
     * GET endpoints so the subsequent mapper doesn't trigger lazy-load SELECTs.
     */
    @EntityGraph(attributePaths = "photos")
    Optional<Report> findWithPhotosById(Long id);

    /**
     * All reports the given user participated in — either submitted originally or
     * contributed a photo to (via deduplication merge). Ordered newest first.
     * {@code DISTINCT} because a user could have multiple photos on the same report.
     *
     * {@code @EntityGraph} loads photos in the same SELECT (via left join) to kill the
     * N+1 problem — without it the mapper would issue one extra query per report.
     */
    @EntityGraph(attributePaths = "photos")
    @Query("""
            select distinct r from Report r
            left join r.photos p
            where r.mergedIntoReportId is null
              and (r.user.id = :userId or p.user.id = :userId)
            order by r.createdAt desc
            """)
    List<Report> findAllVisibleToUser(@Param("userId") Long userId);

    /**
     * Deduplication lookup: find OPEN reports (NEW / IN_PROGRESS) of the given category
     * within a geographic bounding box that were created recently and are NOT themselves
     * merged stubs. Excludes the report we're merging FROM via :excludeId. The box is a
     * rough pre-filter; the caller applies an exact haversine check on the results.
     *
     * Photos are fetched eagerly so the merge step doesn't re-query them.
     */
    @EntityGraph(attributePaths = "photos")
    @Query("""
            select r from Report r
            where r.id <> :excludeId
              and r.mergedIntoReportId is null
              and r.category = :category
              and r.status in (com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportStatus.NEW,
                               com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportStatus.IN_PROGRESS)
              and r.latitude between :minLat and :maxLat
              and r.longitude between :minLng and :maxLng
              and r.createdAt >= :since
            order by r.createdAt asc
            """)
    List<Report> findDuplicateCandidates(
            @Param("excludeId") Long excludeId,
            @Param("category") ReportCategory category,
            @Param("minLat") double minLat,
            @Param("maxLat") double maxLat,
            @Param("minLng") double minLng,
            @Param("maxLng") double maxLng,
            @Param("since") LocalDateTime since);

    /** Admin listing with optional filters. Any null filter is ignored. */
    @EntityGraph(attributePaths = "photos")
    @Query(value = """
            select r from Report r
            where r.mergedIntoReportId is null
              and (:status is null or r.status = :status)
              and (:category is null or r.category = :category)
              and (:priority is null or r.priority = :priority)
            """,
            countQuery = """
            select count(r) from Report r
            where r.mergedIntoReportId is null
              and (:status is null or r.status = :status)
              and (:category is null or r.category = :category)
              and (:priority is null or r.priority = :priority)
            """)
    Page<Report> findForAdmin(
            @Param("status") ReportStatus status,
            @Param("category") ReportCategory category,
            @Param("priority") ReportPriority priority,
            Pageable pageable);
}
