package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.ChatThread;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ChatThreadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChatThreadRepository extends JpaRepository<ChatThread, Long> {

    Page<ChatThread> findAllByUserIdOrderByUpdatedAtDesc(Long userId, Pageable pageable);

    Page<ChatThread> findAllByPulseIdOrderByUpdatedAtDesc(Long pulseId, Pageable pageable);

    java.util.List<ChatThread> findAllByPulseIdOrderByUpdatedAtDesc(Long pulseId);

    Optional<ChatThread> findByPulseIdAndUserId(Long pulseId, Long userId);

    boolean existsByPulseIdAndUserId(Long pulseId, Long userId);

    /** Admin — wszystkie wątki w danym statusie. */
    Page<ChatThread> findAllByStatusOrderByUpdatedAtDesc(ChatThreadStatus status, Pageable pageable);

    // Uwaga: celowo NIE robimy JOIN FETCH t.pulse — każdy JOIN z tabelą pulses
    // powoduje SQLState S1009 przez bug Hibernate 7 + MySQL Connector/J.
    // Dane pulse są pobierane przez JdbcTemplate w ChatService.queryPulseInfo().
    @Query("""
            SELECT t FROM ChatThread t
            LEFT JOIN FETCH t.user
            LEFT JOIN FETCH t.assignedTo
            WHERE t.id = :id
            """)
    Optional<ChatThread> findByIdWithDetails(@Param("id") Long id);

    // Metody admin filtrujące po danych pulse używają natywnego SQL zamiast JPQL,
    // żeby ominąć ten sam bug (JPQL JOIN t.pulse generuje ten sam błędny query).

    @Query(value = """
            SELECT t.* FROM chat_threads t
            INNER JOIN pulses p ON p.id = t.pulse_id
            WHERE p.city IN (:cities)
              AND (:status IS NULL OR t.status = :status)
            ORDER BY t.updated_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM chat_threads t
            INNER JOIN pulses p ON p.id = t.pulse_id
            WHERE p.city IN (:cities)
              AND (:status IS NULL OR t.status = :status)
            """,
            nativeQuery = true)
    Page<ChatThread> findAllByCityIn(@Param("cities") java.util.Set<String> cities,
                                     @Param("status") String status, Pageable pageable);

    @Query(value = """
            SELECT t.* FROM chat_threads t
            INNER JOIN pulses p ON p.id = t.pulse_id
            WHERE p.gmina_id IN (:gminaIds)
              AND (:status IS NULL OR t.status = :status)
            ORDER BY t.updated_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM chat_threads t
            INNER JOIN pulses p ON p.id = t.pulse_id
            WHERE p.gmina_id IN (:gminaIds)
              AND (:status IS NULL OR t.status = :status)
            """,
            nativeQuery = true)
    Page<ChatThread> findAllByGminaIdIn(@Param("gminaIds") java.util.Set<Long> gminaIds,
                                        @Param("status") String status, Pageable pageable);

    @Query(value = """
            SELECT t.* FROM chat_threads t
            INNER JOIN pulses p ON p.id = t.pulse_id
            WHERE p.powiat_id IN (:powiatIds)
              AND (:status IS NULL OR t.status = :status)
            ORDER BY t.updated_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM chat_threads t
            INNER JOIN pulses p ON p.id = t.pulse_id
            WHERE p.powiat_id IN (:powiatIds)
              AND (:status IS NULL OR t.status = :status)
            """,
            nativeQuery = true)
    Page<ChatThread> findAllByPowiatIdIn(@Param("powiatIds") java.util.Set<Long> powiatIds,
                                         @Param("status") String status, Pageable pageable);

    @Query(value = """
            SELECT t.* FROM chat_threads t
            INNER JOIN pulses p ON p.id = t.pulse_id
            WHERE p.wojewodztwo_id IN (:wojIds)
              AND (:status IS NULL OR t.status = :status)
            ORDER BY t.updated_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM chat_threads t
            INNER JOIN pulses p ON p.id = t.pulse_id
            WHERE p.wojewodztwo_id IN (:wojIds)
              AND (:status IS NULL OR t.status = :status)
            """,
            nativeQuery = true)
    Page<ChatThread> findAllByWojewodztwoIdIn(@Param("wojIds") java.util.Set<Long> wojIds,
                                              @Param("status") String status, Pageable pageable);
}
