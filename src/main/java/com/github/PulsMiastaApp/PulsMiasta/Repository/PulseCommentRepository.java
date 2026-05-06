package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PulseCommentRepository extends JpaRepository<PulseComment, Long> {

    /** Top-level komentarze dla pulsu — JOIN FETCH user eliminuje N+1. */
    @Query("SELECT c FROM PulseComment c JOIN FETCH c.user WHERE c.pulse.id = :pulseId AND c.parentComment IS NULL ORDER BY c.createdAt ASC")
    List<PulseComment> findAllByPulseIdAndParentCommentIsNullOrderByCreatedAtAsc(@Param("pulseId") Long pulseId);

    /** Odpowiedzi na komentarz — JOIN FETCH user eliminuje N+1. */
    @Query("SELECT c FROM PulseComment c JOIN FETCH c.user WHERE c.parentComment.id = :parentId ORDER BY c.createdAt ASC")
    List<PulseComment> findAllByParentCommentIdOrderByCreatedAtAsc(@Param("parentId") Long parentId);

    /** Komentarz razem z pulsem — używane do weryfikacji scope admina. */
    @Query("SELECT c FROM PulseComment c JOIN FETCH c.pulse WHERE c.id = :id")
    Optional<PulseComment> findByIdWithPulse(@Param("id") Long id);

    /** Komentarz z autorem — używane do powiadomień push. */
    @Query("SELECT c FROM PulseComment c JOIN FETCH c.user WHERE c.id = :id")
    Optional<PulseComment> findByIdWithUser(@Param("id") Long id);

    long countByPulseIdAndParentCommentIsNull(Long pulseId);

    long countByPulseId(Long pulseId);

    long countByUserId(Long userId);

    /** Admin — wszystkie komentarze pulsu (paginacja). EntityGraph fetchuje user bez N+1. */
    @EntityGraph(attributePaths = {"user"})
    Page<PulseComment> findAllByPulseId(Long pulseId, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE PulseComment c SET c.likesCount = c.likesCount + 1 WHERE c.id = :id")
    void incrementLikes(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE PulseComment c SET c.likesCount = GREATEST(0, c.likesCount - 1) WHERE c.id = :id")
    void decrementLikes(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE PulseComment c SET c.replyCount = c.replyCount + 1 WHERE c.id = :id")
    void incrementReplyCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE PulseComment c SET c.replyCount = GREATEST(0, c.replyCount - 1) WHERE c.id = :id")
    void decrementReplyCount(@Param("id") Long id);
}
