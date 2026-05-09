package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ChatThreadStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_threads",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_chat_thread_pulse_user", columnNames = {"pulse_id", "user_id"})
        },
        indexes = {
                @Index(name = "idx_chat_thread_pulse",    columnList = "pulse_id"),
                @Index(name = "idx_chat_thread_user",     columnList = "user_id"),
                @Index(name = "idx_chat_thread_status",   columnList = "status"),
                @Index(name = "idx_chat_thread_assigned", columnList = "assigned_to_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class ChatThread {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pulse_id", nullable = false)
    private Pulse pulse;

    /** Bezpośredni dostęp do FK bez lazy-load encji Pulse. */
    @Column(name = "pulse_id", insertable = false, updatable = false)
    private Long pulseId;

    /** Obywatel, który zainicjował wątek. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Urzędnik przypisany do obsługi wątku (może być null). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChatThreadStatus status = ChatThreadStatus.OPEN;

    /** Tytuł / temat wątku (opcjonalny skrót). */
    @Column(name = "subject", length = 300)
    private String subject;

    @Column(name = "messages_count", nullable = false)
    private int messagesCount = 0;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
