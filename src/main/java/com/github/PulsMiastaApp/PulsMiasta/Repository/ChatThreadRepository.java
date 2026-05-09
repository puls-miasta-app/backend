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

    Optional<ChatThread> findByPulseIdAndUserId(Long pulseId, Long userId);

    boolean existsByPulseIdAndUserId(Long pulseId, Long userId);

    /** Admin — wszystkie wątki w danym statusie. */
    Page<ChatThread> findAllByStatusOrderByUpdatedAtDesc(ChatThreadStatus status, Pageable pageable);

    /** Admin — wątki dla pulsu z danego obszaru (city). */
    @Query("""
            SELECT t FROM ChatThread t
            JOIN t.pulse p
            WHERE p.city IN :cities
            ORDER BY t.updatedAt DESC
            """)
    Page<ChatThread> findAllByCityIn(@Param("cities") java.util.Set<String> cities, Pageable pageable);

    @Query("""
            SELECT t FROM ChatThread t
            JOIN t.pulse p
            WHERE p.gminaId IN :gminaIds
            ORDER BY t.updatedAt DESC
            """)
    Page<ChatThread> findAllByGminaIdIn(@Param("gminaIds") java.util.Set<Long> gminaIds, Pageable pageable);

    @Query("""
            SELECT t FROM ChatThread t
            JOIN t.pulse p
            WHERE p.powiatId IN :powiatIds
            ORDER BY t.updatedAt DESC
            """)
    Page<ChatThread> findAllByPowiatIdIn(@Param("powiatIds") java.util.Set<Long> powiatIds, Pageable pageable);

    @Query("""
            SELECT t FROM ChatThread t
            JOIN t.pulse p
            WHERE p.wojewodztwoId IN :wojIds
            ORDER BY t.updatedAt DESC
            """)
    Page<ChatThread> findAllByWojewodztwoIdIn(@Param("wojIds") java.util.Set<Long> wojIds, Pageable pageable);

    @Query("""
            SELECT t FROM ChatThread t
            JOIN FETCH t.pulse
            JOIN FETCH t.user
            WHERE t.id = :id
            """)
    Optional<ChatThread> findByIdWithDetails(@Param("id") Long id);
}
