package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Set;

public interface CommentLikeRepository extends JpaRepository<CommentLike, Long> {

    Optional<CommentLike> findByCommentIdAndUserId(Long commentId, Long userId);

    boolean existsByCommentIdAndUserId(Long commentId, Long userId);

    /**
     * Zwraca zbiór ID komentarzy (z podanego zbioru), które polubił dany użytkownik.
     * Używane do batch-oznaczenia userLiked=true bez N+1.
     */
    @Query("SELECT cl.comment.id FROM CommentLike cl WHERE cl.user.id = :userId AND cl.comment.id IN :commentIds")
    Set<Long> findLikedCommentIds(@Param("userId") Long userId, @Param("commentIds") Set<Long> commentIds);
}
