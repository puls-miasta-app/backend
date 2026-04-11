package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportPriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "reports", indexes = {
        @Index(name = "idx_report_user", columnList = "user_id"),
        @Index(name = "idx_report_status", columnList = "status"),
        @Index(name = "idx_report_category", columnList = "category"),
        @Index(name = "idx_report_created_at", columnList = "created_at"),
        // Optimises the async dedup lookup (category + status + location + date).
        // Column order matches the most selective filters first so MySQL can use it
        // as a prefix scan for the bounding-box range on latitude.
        @Index(name = "idx_report_dedup",
               columnList = "merged_into_report_id, category, status, latitude, longitude, created_at"),
        // Optimises the admin listing (WHERE merged_into_report_id IS NULL + filters, ORDER BY created_at).
        @Index(name = "idx_report_admin_list",
               columnList = "merged_into_report_id, status, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30)
    private ReportCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 20)
    private ReportPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status = ReportStatus.NEW;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Column(name = "address")
    private String address;

    /** How many separate submissions merged into this report (starts at 1 for the original). */
    @Column(name = "duplicate_count", nullable = false, columnDefinition = "INT NOT NULL DEFAULT 1")
    private Integer duplicateCount = 1;

    /**
     * If set, this report was merged into another one (the ID points at the primary).
     * Merged stubs are hidden from user/admin listings and GET requests transparently
     * follow the link to return the primary.
     */
    @Column(name = "merged_into_report_id")
    private Long mergedIntoReportId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // No cascade + no orphanRemoval on purpose. Photos are persisted explicitly via
    // ReportPhotoRepository. Crucially, the dedup merge moves photos from a source
    // report onto its primary by reparenting them (photo.setReport(primary)) and
    // clearing source.photos — with orphanRemoval=true that clear() would queue
    // DELETEs for the very rows we just reparented, and the flush order is not
    // deterministic, so photos could be wiped mid-merge.
    @OneToMany(mappedBy = "report")
    private List<ReportPhoto> photos = new ArrayList<>();

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
