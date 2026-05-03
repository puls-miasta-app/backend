package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "pulse_comments", indexes = {
        @Index(name = "idx_comment_pulse", columnList = "pulse_id"),
        @Index(name = "idx_comment_user", columnList = "user_id"),
        @Index(name = "idx_comment_created_at", columnList = "created_at"),
        @Index(name = "idx_comment_parent", columnList = "parent_comment_id")
})
@Getter
@Setter
@NoArgsConstructor
public class PulseComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pulse_id", nullable = false)
    private Pulse pulse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** null = komentarz najwyższego poziomu; non-null = odpowiedź na inny komentarz */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    private PulseComment parentComment;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "likes_count", nullable = false)
    private int likesCount = 0;

    @Column(name = "reply_count", nullable = false)
    private int replyCount = 0;

    /** Ustawiony przy soft-delete — komentarz pozostaje w bazie, body jest zastępowane. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
