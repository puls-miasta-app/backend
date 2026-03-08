package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.TaskStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_entries")
@Getter
@Setter
@NoArgsConstructor
public class TaskEntry {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** S3/R2 object key (path within the bucket). */
    @Column(nullable = true, length = 1024)
    private String r2Key;

    /**
     * Base64-encoded AES-GCM data IV stored for reference.
     * Full decryption metadata (encrypted DEK, DEK IV, KEK name) is embedded
     * in the file header by FileCryptoService.
     */
    @Column(nullable = true, length = 64)
    private String encryptionIv;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Reserved for Gemini AI enrichment — populated by a future pipeline
    @Column(nullable = true, length = 128)
    private String category;

    @Column(nullable = true, length = 32)
    private String priority;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String description;

    @PrePersist
    private void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }
}
