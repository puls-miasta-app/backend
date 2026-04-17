package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.VoteDirection;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Pojedynczy głos użytkownika na pulse. Każdy użytkownik może mieć co najwyżej jeden wpis
 * per pulse — {@code uniqueConstraint} na (pulse_id, user_id) zapewnia to na poziomie DB,
 * a logika serwisu toggle'uje / zmienia kierunek istniejącego wiersza zamiast tworzyć nowy.
 */
@Entity
@Table(name = "pulse_votes",
        uniqueConstraints = @UniqueConstraint(name = "uk_pulse_vote_pulse_user", columnNames = {"pulse_id", "user_id"}),
        indexes = {
                @Index(name = "idx_pulse_vote_pulse", columnList = "pulse_id"),
                @Index(name = "idx_pulse_vote_user", columnList = "user_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class PulseVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pulse_id", nullable = false)
    private Pulse pulse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private VoteDirection direction;

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
