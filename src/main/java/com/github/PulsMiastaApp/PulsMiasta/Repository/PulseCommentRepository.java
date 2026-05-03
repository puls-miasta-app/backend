package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PulseCommentRepository extends JpaRepository<PulseComment, Long> {

    /** Komentarze najwyższego poziomu dla danego pulsu (bez odpowiedzi). */
    List<PulseComment> findAllByPulseIdAndParentCommentIsNullOrderByCreatedAtAsc(Long pulseId);

    /** Odpowiedzi na dany komentarz. */
    List<PulseComment> findAllByParentCommentIdOrderByCreatedAtAsc(Long parentCommentId);

    /** Liczba wszystkich komentarzy dla pulsu (bez odpowiedzi) — do denormalizacji. */
    long countByPulseIdAndParentCommentIsNull(Long pulseId);

    long countByPulseId(Long pulseId);

    long countByUserId(Long userId);

    /** Paginacja dla admina — wszystkie komentarze pulsu (top-level + replies). */
    Page<PulseComment> findAllByPulseId(Long pulseId, Pageable pageable);

    @Modifying
    @Query("UPDATE PulseComment c SET c.likesCount = c.likesCount + 1 WHERE c.id = :id")
    void incrementLikes(@Param("id") Long id);

    @Modifying
    @Query("UPDATE PulseComment c SET c.likesCount = GREATEST(0, c.likesCount - 1) WHERE c.id = :id")
    void decrementLikes(@Param("id") Long id);

    @Modifying
    @Query("UPDATE PulseComment c SET c.replyCount = c.replyCount + 1 WHERE c.id = :id")
    void incrementReplyCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE PulseComment c SET c.replyCount = GREATEST(0, c.replyCount - 1) WHERE c.id = :id")
    void decrementReplyCount(@Param("id") Long id);
}
