package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.Set;

public interface CommentLikeRepository extends JpaRepository<CommentLike, Long> {

    Optional<CommentLike> findByCommentIdAndUserId(Long commentId, Long userId);

    boolean existsByCommentIdAndUserId(Long commentId, Long userId);

    /** Zwraca id komentarzy (z podanego zbioru) które polubił dany użytkownik. */
    Set<Long> findCommentIdByUserIdAndCommentIdIn(Long userId, Iterable<Long> commentIds);
}
