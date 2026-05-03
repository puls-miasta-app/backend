package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification_preferences", indexes = {
        @Index(name = "idx_notif_prefs_user", columnList = "user_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class NotificationPreferences {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled = true;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @Column(name = "nearby_pulses_enabled", nullable = false)
    private boolean nearbyPulsesEnabled = true;

    @Column(name = "status_updates_enabled", nullable = false)
    private boolean statusUpdatesEnabled = true;

    @Column(name = "comment_replies_enabled", nullable = false)
    private boolean commentRepliesEnabled = true;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }
}
