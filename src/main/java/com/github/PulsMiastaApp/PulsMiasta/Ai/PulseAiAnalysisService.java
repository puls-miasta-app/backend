package com.github.PulsMiastaApp.PulsMiasta.Ai;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulsePhoto;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;
import com.github.PulsMiastaApp.PulsMiasta.Push.PushNotificationService;
import com.github.PulsMiastaApp.PulsMiasta.Service.ReverseGeocodingService;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseFeedJdbcRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulsePhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Fire-and-forget AI image analysis + dedup dla pulses.
 * Wszystkie operacje na tabeli pulses używają JDBC żeby ominąć bug
 * Hibernate 7 + MySQL Connector/J (SQLState S1009).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PulseAiAnalysisService {

    private static final double DEDUP_RADIUS_METERS = 20.0;
    private static final int DEDUP_WINDOW_DAYS = 30;

    private final GeminiImageAnalysisService geminiImageAnalysisService;
    private final GeminiProperties geminiProperties;
    private final PulseFeedJdbcRepository pulseFeedJdbcRepository;
    private final PulsePhotoRepository pulsePhotoRepository;
    private final PushNotificationService pushNotificationService;
    private final ReverseGeocodingService reverseGeocodingService;

    @Async("photoUploadExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void analyseAsync(Long pulseId, byte[] imageBytes, String contentType,
                              Double latitude, Double longitude) {
        try {
            log.info("Starting async AI analysis for pulse {}", pulseId);
            String roadContext = buildRoadContext(latitude, longitude);
            if (roadContext != null) {
                log.info("Pulse {}: road context for AI = \"{}\"", pulseId, roadContext);
            } else {
                log.debug("Pulse {}: no road context (lat/lng absent or highway type unknown)", pulseId);
            }
            AiAnalysisResult result = geminiImageAnalysisService.analyse(imageBytes, contentType, roadContext);
            if (result == null) {
                log.warn("Gemini returned no result for pulse {}; leaving fields untouched", pulseId);
                return;
            }

            Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId).orElse(null);
            if (pulse == null) {
                log.warn("Pulse {} not found when writing back AI analysis", pulseId);
                return;
            }

            if (pulse.getMergedIntoPulseId() != null) {
                log.info("Pulse {} was already merged into {}, skipping AI writeback",
                        pulseId, pulse.getMergedIntoPulseId());
                return;
            }

            double threshold = geminiProperties.getMinConfidence();
            if (result.confidence() < threshold) {
                log.info("Pulse {}: AI confidence {} below threshold {}, marking PENDING_REVIEW",
                        pulseId, result.confidence(), threshold);
                String imageHint = result.imageHint() != null && !result.imageHint().isBlank()
                        ? result.imageHint() : null;
                pulseFeedJdbcRepository.updateAiNoteAndImageHint(pulseId,
                        "Automatyczna analiza zdjęcia nie była możliwa. Zgłoszenie wymaga ręcznej weryfikacji.",
                        imageHint);
                pulseFeedJdbcRepository.markPendingReview(pulseId);
                return;
            }

            Optional<Pulse> primary = findDuplicate(pulse, result);
            if (primary.isPresent()) {
                mergeInto(pulse, primary.get());
                return;
            }

            String title = (result.title() != null && !result.title().isBlank())
                    ? result.title() : pulse.getTitle();
            String description = (result.description() != null && !result.description().isBlank())
                    ? result.description() : pulse.getDescription();

            pulseFeedJdbcRepository.updateAiFields(
                    pulseId,
                    result.category() != null ? result.category().name() : null,
                    result.priority() != null ? result.priority().name() : null,
                    title,
                    description,
                    result.aiNote(),
                    result.imageHint(),
                    result.heat());

            log.info("AI analysis saved for pulse {}: category={}, priority={}, confidence={}",
                    pulseId, result.category(), result.priority(), result.confidence());

            for (AiAnalysisResult.AdditionalThreat threat : result.additionalThreats()) {
                try {
                    createSplitPulse(pulse, threat);
                } catch (Exception e) {
                    log.error("Failed to create split pulse for threat {} from pulse {}: {}",
                            threat.category(), pulseId, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Async AI analysis failed for pulse {}: {}", pulseId, e.getMessage(), e);
        }
    }

    // ---------- split pulses ----------

    /**
     * Tworzy osobny puls dla dodatkowego zagrożenia wykrytego na tym samym zdjęciu.
     * Jeżeli istnieje już podobne zgłoszenie w pobliżu tej samej kategorii, dołącza
     * do niego referencję do zdjęcia zamiast tworzyć duplikat.
     */
    private void createSplitPulse(Pulse sourcePulse, AiAnalysisResult.AdditionalThreat threat) {
        if (threat.category() == null) return;
        Long userId = sourcePulse.getUser() != null ? sourcePulse.getUser().getId() : null;
        if (userId == null) return;

        String category = threat.category().name();
        String priority = threat.priority() != null ? threat.priority().name() : PulsePriority.STANDARD.name();

        String titleVal = threat.title() != null && !threat.title().isBlank()
                ? threat.title() : defaultTitleFor(threat.category());

        Optional<Pulse> duplicate = findDuplicate(sourcePulse, category, titleVal, threat.description(), threat.imageHint());
        if (duplicate.isPresent()) {
            log.info("Split threat {} from pulse {} matched duplicate {}, attaching photo only",
                    category, sourcePulse.getId(), duplicate.get().getId());
            attachPhotoToPulse(sourcePulse, duplicate.get().getId(), userId);
            return;
        }
        String descVal = threat.description() != null && !threat.description().isBlank()
                ? threat.description() : sourcePulse.getDescription();

        Long newPulseId = pulseFeedJdbcRepository.insertPulse(
                userId, category, priority,
                titleVal, descVal,
                threat.aiNote(), threat.imageHint(), threat.heat(),
                sourcePulse.getLatitude(), sourcePulse.getLongitude(),
                sourcePulse.getAddress(), sourcePulse.getDistrict(), sourcePulse.getStreet(), sourcePulse.getCity(),
                sourcePulse.getGmina(), sourcePulse.getPowiat(), sourcePulse.getWojewodztwo(),
                sourcePulse.getGminaId(), sourcePulse.getPowiatId(), sourcePulse.getWojewodztwoId());

        attachPhotoToPulse(sourcePulse, newPulseId, userId);

        log.info("Created split pulse {} (category={}) from source pulse {}",
                newPulseId, category, sourcePulse.getId());
    }

    private void attachPhotoToPulse(Pulse sourcePulse, Long targetPulseId, Long userId) {
        if (sourcePulse.getPhotos().isEmpty()) return;
        PulsePhoto photo = sourcePulse.getPhotos().get(0);
        pulseFeedJdbcRepository.insertPulsePhoto(
                targetPulseId, userId,
                photo.getObjectKey(),
                photo.getOriginalFilename(),
                photo.getContentType(),
                photo.getFileSize());
    }

    private static String defaultTitleFor(com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory category) {
        return switch (category) {
            case RUCH -> "Zgłoszenie: ruch";
            case BEZPIECZENSTWO -> "Zgłoszenie: bezpieczeństwo";
            case ZIELEN -> "Zgłoszenie: zieleń";
            case INCYDENTY -> "Zgłoszenie: incydent";
        };
    }

    // ---------- dedup / merge ----------

    private Optional<Pulse> findDuplicate(Pulse pulse, AiAnalysisResult result) {
        String category = result.category() != null ? result.category().name() : null;
        return findDuplicate(pulse, category, result.title(), result.description(), result.imageHint());
    }

    /**
     * Szuka kandydata do merge'u: ta sama kategoria, w promieniu {@value DEDUP_RADIUS_METERS}m,
     * ostatnie {@value DEDUP_WINDOW_DAYS} dni. Dla każdego kandydata pyta Gemini (tekst)
     * czy oba opisy dotyczą tego samego fizycznego problemu — merge następuje tylko po
     * potwierdzeniu. Błąd API → bezpiecznie zwraca brak duplikatu.
     */
    private Optional<Pulse> findDuplicate(Pulse pulse, String category,
                                           String title, String description, String imageHint) {
        if (pulse.getLatitude() == null || pulse.getLongitude() == null || category == null) {
            return Optional.empty();
        }
        double lat = pulse.getLatitude();
        double lng = pulse.getLongitude();

        double latDelta = DEDUP_RADIUS_METERS / 111_000.0;
        double lngDelta = DEDUP_RADIUS_METERS / (111_000.0 * Math.cos(Math.toRadians(lat)));

        LocalDateTime since = LocalDateTime.now().minusDays(DEDUP_WINDOW_DAYS);

        List<Pulse> candidates = pulseFeedJdbcRepository.findDuplicateCandidates(
                pulse.getId(),
                category,
                lat - latDelta, lat + latDelta,
                lng - lngDelta, lng + lngDelta,
                since);

        return candidates.stream()
                .filter(c -> c.getLatitude() != null && c.getLongitude() != null)
                .filter(c -> haversineMeters(lat, lng, c.getLatitude(), c.getLongitude()) <= DEDUP_RADIUS_METERS)
                .filter(c -> isSameProblem(title, description, imageHint, c))
                .findFirst();
    }

    private boolean isSameProblem(String newTitle, String newDesc, String newHint, Pulse existing) {
        boolean existingHasAiData = !isBlank(existing.getImageHint())
                || !isBlank(existing.getTitle())
                || !isBlank(existing.getDescription());
        if (!existingHasAiData) {
            log.debug("Candidate pulse {} has no AI data yet — skipping merge", existing.getId());
            return false;
        }
        boolean same = geminiImageAnalysisService.areSameProblem(
                newTitle, newDesc, newHint,
                existing.getTitle(), existing.getDescription(), existing.getImageHint());
        log.debug("Gemini same-problem check vs pulse {}: {}", existing.getId(), same);
        return same;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void mergeInto(Pulse source, Pulse primary) {
        log.info("Merging pulse {} into {} (category={}, duplicateCount={} -> {})",
                source.getId(), primary.getId(), primary.getCategory(),
                primary.getDuplicateCount(), primary.getDuplicateCount() + 1);

        List<PulsePhoto> movedPhotos = List.copyOf(source.getPhotos());
        for (PulsePhoto photo : movedPhotos) {
            photo.setPulse(primary);
        }
        if (!movedPhotos.isEmpty()) {
            pulsePhotoRepository.saveAll(movedPhotos);
        }

        pulseFeedJdbcRepository.incrementDuplicateCount(primary.getId());
        pulseFeedJdbcRepository.markMerged(source.getId(), primary.getId());

        if (source.getUser() != null) {
            pushNotificationService.notifyMerged(source.getUser().getId(), primary.getId());
        }
    }

    private String buildRoadContext(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) return null;
        String highwayType = reverseGeocodingService.detectHighwayType(latitude, longitude);
        if (highwayType == null) return null;

        return switch (highwayType) {
            case "motorway", "trunk", "primary" ->
                    "Lokalizacja: główna droga miejska lub arteria z dużym natężeniem ruchu.";
            case "secondary", "tertiary" ->
                    "Lokalizacja: droga zbiorcza z umiarkowanym natężeniem ruchu.";
            case "residential", "unclassified", "living_street" ->
                    "Lokalizacja: droga lokalna lub boczna z małym ruchem. Problemy czysto kosmetyczne mają tu priorytet NISKIE.";
            case "service", "path", "footway", "cycleway", "pedestrian" ->
                    "Lokalizacja: droga serwisowa, ścieżka lub droga osiedlowa z minimalnym ruchem. Problemy kosmetyczne mają priorytet NISKIE.";
            default -> null;
        };
    }

    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
