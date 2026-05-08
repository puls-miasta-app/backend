package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Zgłoszenie użytkownika ("pulse"). Zawiera wszystkie pola wymagane przez aplikację
 * mobilną (patrz {@code PulseItem} w {@code frontend_mobile/src/components/dashboard/types.ts})
 * oraz pola adminowe i służące deduplikacji (jak wcześniej w {@code Report}).
 */
@Entity
@Table(name = "pulses", indexes = {
        @Index(name = "idx_pulse_user", columnList = "user_id"),
        @Index(name = "idx_pulse_status", columnList = "status"),
        @Index(name = "idx_pulse_category", columnList = "category"),
        @Index(name = "idx_pulse_created_at", columnList = "created_at"),
        @Index(name = "idx_pulse_district", columnList = "district"),
        @Index(name = "idx_pulse_street", columnList = "street"),
        @Index(name = "idx_pulse_city", columnList = "city"),
        @Index(name = "idx_pulse_dedup",
                columnList = "merged_into_pulse_id, category, status, latitude, longitude, created_at"),
        @Index(name = "idx_pulse_admin_list",
                columnList = "merged_into_pulse_id, status, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Pulse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30)
    private PulseCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 20)
    private PulsePriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PulseStatus status = PulseStatus.NEW;

    /** Krótki tytuł (zazwyczaj generowany przez AI lub pochodzący z kategorii). */
    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "address")
    private String address;

    /** Dzielnica — przypisywana przez reverse geocoding lub manualnie. */
    @Column(name = "district", length = 120)
    private String district;

    /** Ulica — przypisywana przez reverse geocoding lub manualnie. */
    @Column(name = "street", length = 200)
    private String street;

    /** Miasto — przypisywane przez reverse geocoding lub manualnie. */
    @Column(name = "city", length = 120)
    private String city;

    /** Gmina — wypełniana przez reverse geocoding (znormalizowana lowercase, bez prefiksu "gmina "). */
    @Column(name = "gmina", length = 120)
    private String gmina;

    /** Powiat — wypełniany przez reverse geocoding (znormalizowany lowercase, bez prefiksu "powiat "). */
    @Column(name = "powiat", length = 120)
    private String powiat;

    /** Województwo — wypełniane przez reverse geocoding (znormalizowane lowercase, bez prefiksu "województwo "). */
    @Column(name = "wojewodztwo", length = 120)
    private String wojewodztwo;

    /** FK do geo_gminy — wypełniany przez reverse geocoding; null gdy nie udało się dopasować. */
    @Column(name = "gmina_id")
    private Long gminaId;

    /** FK do geo_powiaty — wypełniany przez reverse geocoding; null gdy nie udało się dopasować. */
    @Column(name = "powiat_id")
    private Long powiatId;

    /** FK do geo_wojewodztwa — wypełniany przez reverse geocoding; null gdy nie udało się dopasować. */
    @Column(name = "wojewodztwo_id")
    private Long wojewodztwoId;

    /** Tekstowy marker nasilenia (np. "Wysokie", "Średnie", "Niskie"). Wyświetlany w feedzie mobile. */
    @Column(name = "heat", length = 30)
    private String heat;

    /** Krótka notatka AI widoczna w kartce zgłoszenia. */
    @Column(name = "ai_note", columnDefinition = "TEXT")
    private String aiNote;

    /** Podpowiedź opisująca zawartość zdjęcia (generowana przez AI). */
    @Column(name = "image_hint", length = 500)
    private String imageHint;

    /** Licznik komentarzy zdenormalizowany — mobile wyświetla tylko wartość. */
    @Column(name = "comments_count", nullable = false)
    private Integer commentsCount = 0;

    /** Liczba głosów "up" (zdenormalizowana — aktualizowana razem z wierszami w {@code pulse_votes}). */
    @Column(name = "upvotes", nullable = false)
    private Integer upvotes = 0;

    /** Liczba głosów "down". */
    @Column(name = "downvotes", nullable = false)
    private Integer downvotes = 0;

    /** Ile niezależnych zgłoszeń zostało zmergowanych w to (start = 1 dla oryginału). */
    @Column(name = "duplicate_count", nullable = false, columnDefinition = "INT NOT NULL DEFAULT 1")
    private Integer duplicateCount = 1;

    /**
     * Jeżeli ustawione, to zgłoszenie zostało scalone z innym — pokazuje id głównego.
     * Scalone stuby są ukryte z list i GET transparentnie przekierowuje do głównego.
     */
    @Column(name = "merged_into_pulse_id")
    private Long mergedIntoPulseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Brak cascade + orphanRemoval — zdjęcia są zapisywane jawnie przez PulsePhotoRepository.
    // Logika deduplikacji przeparentowuje zdjęcia (photo.setPulse(primary)) bezpośrednio,
    // a orphanRemoval=true na source.photos.clear() wyrzuciłby DELETE na świeżo przeniesione wiersze.
    @OneToMany(mappedBy = "pulse")
    private List<PulsePhoto> photos = new ArrayList<>();

    /** Znormalizowany score pokazywany w mobile. */
    public int score() {
        return (upvotes == null ? 0 : upvotes) - (downvotes == null ? 0 : downvotes);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (commentsCount == null) commentsCount = 0;
        if (upvotes == null) upvotes = 0;
        if (downvotes == null) downvotes = 0;
        if (duplicateCount == null) duplicateCount = 1;
        if (status == null) status = PulseStatus.NEW;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
