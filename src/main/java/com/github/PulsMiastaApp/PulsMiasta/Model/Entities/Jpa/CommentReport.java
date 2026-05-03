package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.CommentReportReason;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.CommentReportStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "comment_reports",
        uniqueConstraints = @UniqueConstraint(name = "uk_comment_report_comment_reporter", columnNames = {"comment_id", "reporter_id"}),
        indexes = {
                @Index(name = "idx_comment_report_comment", columnList = "comment_id"),
                @Index(name = "idx_comment_report_reporter", columnList = "reporter_id"),
                @Index(name = "idx_comment_report_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
public class CommentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comment_id", nullable = false)
    private PulseComment comment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private CommentReportReason reason;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommentReportStatus status = CommentReportStatus.PENDING;

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
