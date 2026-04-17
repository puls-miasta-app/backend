package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Zdjęcie dołączone do pulse'a. Przy dedup-merge zdjęcia są reparentowane
 * (photo.setPulse(primary)) zamiast kopiowane — dlatego collection {@code Pulse.photos}
 * nie używa cascade / orphanRemoval.
 */
@Entity
@Table(name = "pulse_photos", indexes = {
        @Index(name = "idx_pulse_photo_pulse", columnList = "pulse_id"),
        @Index(name = "idx_pulse_photo_user", columnList = "user_id"),
        @Index(name = "idx_pulse_photo_object_key", columnList = "object_key", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class PulsePhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pulse_id")
    private Pulse pulse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "content_type", length = 50)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }
}
