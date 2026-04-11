package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PulseRepository extends JpaRepository<Pulse, Long> {

    @EntityGraph(attributePaths = "photos")
    Optional<Pulse> findWithPhotosById(Long id);

    // --- Feed z filtrem district/street (4 warianty, żeby uniknąć "(:p is null or ...)"
    //     który sprawia problemy z MySQL JDBC) ---

    @EntityGraph(attributePaths = "photos")
    @Query("""
            select p from Pulse p
            where p.mergedIntoPulseId is null
            order by p.createdAt desc
            """)
    List<Pulse> findFeedAll();

    @EntityGraph(attributePaths = "photos")
    @Query("""
            select p from Pulse p
            where p.mergedIntoPulseId is null
              and p.district = :district
            order by p.createdAt desc
            """)
    List<Pulse> findFeedByDistrict(@Param("district") String district);

    @EntityGraph(attributePaths = "photos")
    @Query("""
            select p from Pulse p
            where p.mergedIntoPulseId is null
              and p.street = :street
            order by p.createdAt desc
            """)
    List<Pulse> findFeedByStreet(@Param("street") String street);

    @EntityGraph(attributePaths = "photos")
    @Query("""
            select p from Pulse p
            where p.mergedIntoPulseId is null
              and p.district = :district
              and p.street = :street
            order by p.createdAt desc
            """)
    List<Pulse> findFeedByDistrictAndStreet(@Param("district") String district,
                                            @Param("street") String street);

    @EntityGraph(attributePaths = "photos")
    @Query("""
            select distinct p from Pulse p
            left join p.photos ph
            where p.mergedIntoPulseId is null
              and (p.user.id = :userId or ph.user.id = :userId)
            order by p.createdAt desc
            """)
    List<Pulse> findAllVisibleToUser(@Param("userId") Long userId);

    /** Deduplikacja — szukamy OPEN pulses tej samej kategorii w bounding boxie. */
    @EntityGraph(attributePaths = "photos")
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

    // --- Districts / Streets helpers ------------------------------------------------

    // Note: endpointy /v1/districts i /v1/streets nie używają repozytorium — są
    // zaimplementowane przez JdbcTemplate bezpośrednio w AreaDictionaryController,
    // obchodząc bug Hibernate 7 + MySQL Connector/J na zapytaniach do tabeli pulses.
}
