package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "device_registrations", indexes = {
        @Index(name = "idx_device_user", columnList = "user_id"),
        @Index(name = "idx_device_token", columnList = "push_token", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class DeviceRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Opaque push token (Expo / FCM / APNs). */
    @Column(name = "push_token", nullable = false, unique = true, length = 500)
    private String pushToken;

    /** "expo" | "fcm" | "apns" | "web". */
    @Column(name = "platform", length = 20)
    private String platform;

    @Column(name = "device_name", length = 120)
    private String deviceName;

    @Column(name = "locale", length = 20)
    private String locale;

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
