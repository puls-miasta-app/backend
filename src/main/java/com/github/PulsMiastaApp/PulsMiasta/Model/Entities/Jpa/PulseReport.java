package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseReportReason;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseReportStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "pulse_reports",
        uniqueConstraints = @UniqueConstraint(name = "uk_pulse_report_pulse_reporter", columnNames = {"pulse_id", "reporter_id"}),
        indexes = {
                @Index(name = "idx_pulse_report_pulse", columnList = "pulse_id"),
                @Index(name = "idx_pulse_report_reporter", columnList = "reporter_id"),
                @Index(name = "idx_pulse_report_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
public class PulseReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pulse_id", nullable = false)
    private Pulse pulse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private PulseReportReason reason;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PulseReportStatus status = PulseReportStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "admin_note", columnDefinition = "TEXT")
    private String adminNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
