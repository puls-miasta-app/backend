package com.github.PulsMiastaApp.PulsMiasta.Ai;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulsePhoto;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulsePhotoRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseRepository;
import jakarta.persistence.EntityManager;
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
 * Fire-and-forget AI image analysis + dedup dla pulses. Analogicznie do poprzedniego
 * ReportAiAnalysisService — caller persistuje pulse pierwszy, a ten serwis dopisuje
 * pola AI i opcjonalnie mergeuje duplikaty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PulseAiAnalysisService {

    private static final double DEDUP_RADIUS_METERS = 20.0;
    private static final int DEDUP_WINDOW_DAYS = 30;

    private final GeminiImageAnalysisService geminiImageAnalysisService;
    private final GeminiProperties geminiProperties;
    private final PulseRepository pulseRepository;
    private final PulsePhotoRepository pulsePhotoRepository;
    private final EntityManager entityManager;

    @Async("photoUploadExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void analyseAsync(Long pulseId, byte[] imageBytes, String contentType) {
        try {
            log.info("Starting async AI analysis for pulse {}", pulseId);
            AiAnalysisResult result = geminiImageAnalysisService.analyse(imageBytes, contentType);
            if (result == null) {
                log.warn("Gemini returned no result for pulse {}; leaving fields untouched", pulseId);
                return;
            }

            Pulse pulse = pulseRepository.findById(pulseId).orElse(null);
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
                log.info("Pulse {}: AI confidence {} below threshold {}, leaving fields empty for manual review",
                        pulseId, result.confidence(), threshold);
                return;
            }

            Optional<Pulse> primary = findDuplicate(pulse, result.category());
            if (primary.isPresent()) {
                mergeInto(pulse, primary.get());
                return;
            }

            pulse.setCategory(result.category());
            pulse.setPriority(result.priority());
            if (result.title() != null && !result.title().isBlank()) {
                pulse.setTitle(result.title());
            }
            if (result.description() != null && !result.description().isBlank()) {
                pulse.setDescription(result.description());
            }
            pulse.setAiNote(result.aiNote());
            pulse.setImageHint(result.imageHint());
            pulse.setHeat(result.heat());
            pulseRepository.save(pulse);

            log.info("AI analysis saved for pulse {}: category={}, priority={}, confidence={}",
                    pulseId, result.category(), result.priority(), result.confidence());
        } catch (Exception e) {
            log.error("Async AI analysis failed for pulse {}: {}", pulseId, e.getMessage(), e);
        }
    }

    // ---------- dedup / merge ----------

    private Optional<Pulse> findDuplicate(Pulse pulse, PulseCategory category) {
        if (pulse.getLatitude() == null || pulse.getLongitude() == null) {
            return Optional.empty();
        }
        double lat = pulse.getLatitude();
        double lng = pulse.getLongitude();

        double latDelta = DEDUP_RADIUS_METERS / 111_000.0;
        double lngDelta = DEDUP_RADIUS_METERS / (111_000.0 * Math.cos(Math.toRadians(lat)));

        LocalDateTime since = LocalDateTime.now().minusDays(DEDUP_WINDOW_DAYS);

        List<Pulse> candidates = pulseRepository.findDuplicateCandidates(
                pulse.getId(),
                category,
                lat - latDelta, lat + latDelta,
                lng - lngDelta, lng + lngDelta,
                since);

        return candidates.stream()
                .filter(c -> c.getLatitude() != null && c.getLongitude() != null)
                .filter(c -> haversineMeters(lat, lng, c.getLatitude(), c.getLongitude()) <= DEDUP_RADIUS_METERS)
                .findFirst();
    }

    private void mergeInto(Pulse source, Pulse primary) {
        log.info("Merging pulse {} into {} (category={}, duplicateCount={} -> {})",
                source.getId(), primary.getId(), primary.getCategory(),
                primary.getDuplicateCount(), primary.getDuplicateCount() + 1);

        List<PulsePhoto> movedPhotos = List.copyOf(source.getPhotos());
        for (PulsePhoto photo : movedPhotos) {
            photo.setPulse(primary);
        }
        pulsePhotoRepository.saveAll(movedPhotos);
        entityManager.flush();

        primary.getPhotos().addAll(movedPhotos);
        primary.setDuplicateCount(primary.getDuplicateCount() + 1);

        source.setMergedIntoPulseId(primary.getId());

        pulseRepository.saveAll(List.of(primary, source));
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
