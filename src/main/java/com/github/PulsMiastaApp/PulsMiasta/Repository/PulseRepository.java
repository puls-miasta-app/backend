package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PulseRepository extends JpaRepository<Pulse, Long> {

    @EntityGraph(attributePaths = "photos")
    Optional<Pulse> findWithPhotosById(Long id);

    /**
     * Pobiera pulse z blokadą wierszową — używane przy głosowaniu, żeby zapobiec
     * race condition na liczniku upvotes/downvotes (read-modify-write).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pulse p where p.id = :id")
    Optional<Pulse> findByIdForUpdate(@Param("id") Long id);

    // Feed (listFeed) i "visible to user" (listForUser) są zaimplementowane przez
    // PulseFeedJdbcRepository — JPA/@EntityGraph z LEFT JOIN na pulse_photos wali
    // SQLState S1009 (Hibernate 7 + MySQL Connector/J). Patrz też AreaDictionaryController.

    long countByUserId(Long userId);

    java.util.List<Pulse> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Aggregate stats dla profilu użytkownika — pozwala uniknąć ładowania
     * wszystkich pulse'ów do pamięci tylko po to, żeby zsumować liczniki
     * (patrz UserProfileService).
     */
    @Query("""
            select count(p) as pulsesSubmitted,
                   coalesce(sum(p.upvotes), 0) as totalUpvotes,
                   coalesce(sum(p.downvotes), 0) as totalDownvotes,
                   coalesce(sum(case when p.status = com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus.RESOLVED
                                     then 1 else 0 end), 0) as resolvedPulses
              from Pulse p
             where p.user.id = :userId
            """)
    UserPulseStats aggregateStatsForUser(@Param("userId") Long userId);

    interface UserPulseStats {
        long getPulsesSubmitted();
        long getTotalUpvotes();
        long getTotalDownvotes();
        long getResolvedPulses();
    }

    /** Deduplikacja — szukamy OPEN pulses tej samej kategorii w bounding boxie. */
    @Query("""
            select p from Pulse p
            where p.id <> :excludeId
              and p.mergedIntoPulseId is null
              and p.category = :category
              and p.status in (com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus.NEW,
                               com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus.IN_PROGRESS)
              and p.latitude between :minLat and :maxLat
              and p.longitude between :minLng and :maxLng
              and p.createdAt >= :since
            order by p.createdAt asc
            """)
    List<Pulse> findDuplicateCandidates(
            @Param("excludeId") Long excludeId,
            @Param("category") PulseCategory category,
            @Param("minLat") double minLat,
            @Param("maxLat") double maxLat,
            @Param("minLng") double minLng,
            @Param("maxLng") double maxLng,
            @Param("since") LocalDateTime since);

    @EntityGraph(attributePaths = "photos")
    @Query(value = """
            select p from Pulse p
            where p.mergedIntoPulseId is null
              and (:status is null or p.status = :status)
              and (:category is null or p.category = :category)
              and (:priority is null or p.priority = :priority)
            """,
            countQuery = """
            select count(p) from Pulse p
            where p.mergedIntoPulseId is null
              and (:status is null or p.status = :status)
              and (:category is null or p.category = :category)
              and (:priority is null or p.priority = :priority)
            """)
    Page<Pulse> findForAdmin(
            @Param("status") PulseStatus status,
            @Param("category") PulseCategory category,
            @Param("priority") PulsePriority priority,
            Pageable pageable);

    @Query("select p.user.id from Pulse p where p.id = :id")
    Optional<Long> findOwnerIdById(@Param("id") Long id);

    // --- Districts / Streets helpers ------------------------------------------------

    // Note: endpointy /v1/districts i /v1/streets nie używają repozytorium — są
    // zaimplementowane przez JdbcTemplate bezpośrednio w AreaDictionaryController,
    // obchodząc bug Hibernate 7 + MySQL Connector/J na zapytaniach do tabeli pulses.
}
