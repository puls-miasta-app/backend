package com.github.PulsMiastaApp.PulsMiasta.Ai;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Report;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.ReportPhoto;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportCategory;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ReportPhotoRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ReportRepository;
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
 * Fire-and-forget AI image analysis + deduplication. The caller persists the report first
 * and returns immediately; this service runs Gemini on a background thread, writes the
 * AI fields back, and then checks whether the report should be merged into an existing
 * open report of the same category nearby.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportAiAnalysisService {

    /** Duplicate detection radius in metres. */
    private static final double DEDUP_RADIUS_METERS = 20.0;

    /** Look back this many days when searching for an existing report to merge into. */
    private static final int DEDUP_WINDOW_DAYS = 30;

    private final GeminiImageAnalysisService geminiImageAnalysisService;
    private final GeminiProperties geminiProperties;
    private final ReportRepository reportRepository;
    private final ReportPhotoRepository reportPhotoRepository;
    private final EntityManager entityManager;

    @Async("photoUploadExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void analyseAsync(Long reportId, byte[] imageBytes, String contentType) {
        try {
            log.info("Starting async AI analysis for report {}", reportId);
            AiAnalysisResult result = geminiImageAnalysisService.analyse(imageBytes, contentType);
            if (result == null) {
                log.warn("Gemini returned no result for report {}; leaving fields untouched", reportId);
                return;
            }

            Report report = reportRepository.findById(reportId).orElse(null);
            if (report == null) {
                log.warn("Report {} not found when writing back AI analysis", reportId);
                return;
            }

            // If this report was already merged by some other worker while we were waiting
            // for Gemini, nothing to do.
            if (report.getMergedIntoReportId() != null) {
                log.info("Report {} was already merged into {}, skipping AI writeback",
                        reportId, report.getMergedIntoReportId());
                return;
            }

            // Confidence gate: if Gemini isn't sure the photo depicts a real issue,
            // leave the AI fields blank for manual review and skip dedup entirely —
            // we don't want to merge an ambiguous photo into a real report.
            double threshold = geminiProperties.getMinConfidence();
            if (result.confidence() < threshold) {
                log.info("Report {}: AI confidence {} below threshold {}, leaving fields empty for manual review",
                        reportId, result.confidence(), threshold);
                return;
            }

            // Look for an existing open report of the same category nearby. If found,
            // merge THIS report into it; otherwise keep it as a standalone report.
            Optional<Report> primary = findDuplicate(report, result.category());
            if (primary.isPresent()) {
                mergeInto(report, primary.get());
                return;
            }

            report.setCategory(result.category());
            report.setPriority(result.priority());
            report.setDescription(result.description());
            reportRepository.save(report);

            log.info("AI analysis saved for report {}: category={}, priority={}, confidence={}",
                    reportId, result.category(), result.priority(), result.confidence());
        } catch (Exception e) {
            // Swallow — this runs on a background thread and must not crash the executor.
            log.error("Async AI analysis failed for report {}: {}", reportId, e.getMessage(), e);
        }
    }

    // ---------- dedup / merge ----------

    private Optional<Report> findDuplicate(Report report, ReportCategory category) {
        double lat = report.getLatitude();
        double lng = report.getLongitude();

        // Rough degree-delta for the bounding box. 1° lat ≈ 111 km.
        double latDelta = DEDUP_RADIUS_METERS / 111_000.0;
        // Longitude degrees shrink with latitude. Use cos(lat) to compensate.
        double lngDelta = DEDUP_RADIUS_METERS / (111_000.0 * Math.cos(Math.toRadians(lat)));

        LocalDateTime since = LocalDateTime.now().minusDays(DEDUP_WINDOW_DAYS);

        List<Report> candidates = reportRepository.findDuplicateCandidates(
                report.getId(),
                category,
                lat - latDelta, lat + latDelta,
                lng - lngDelta, lng + lngDelta,
                since);

        // Exact haversine filter — bounding box is a square, we want a circle.
        return candidates.stream()
                .filter(c -> haversineMeters(lat, lng, c.getLatitude(), c.getLongitude()) <= DEDUP_RADIUS_METERS)
                .findFirst();
    }

    /**
     * Move photos from {@code source} onto {@code primary}, bump the primary's counter,
     * and mark the source as a merged stub so listings skip it.
     *
     * <p>The reparenting UPDATEs are <em>flushed to the DB before</em> we touch the
     * managed collections. This way, even if {@code Report.photos} were ever annotated
     * with {@code orphanRemoval = true}, Hibernate cannot issue a DELETE against a row
     * we just moved — because by then the row already belongs to the primary report.
     */
    private void mergeInto(Report source, Report primary) {
        log.info("Merging report {} into {} (category={}, duplicateCount={} -> {})",
                source.getId(), primary.getId(), primary.getCategory(),
                primary.getDuplicateCount(), primary.getDuplicateCount() + 1);

        List<ReportPhoto> movedPhotos = List.copyOf(source.getPhotos());
        for (ReportPhoto photo : movedPhotos) {
            photo.setReport(primary);
        }
        reportPhotoRepository.saveAll(movedPhotos);
        // Force the UPDATE report_photos SET report_id = primary to hit the DB now,
        // before the managed collections are mutated below.
        entityManager.flush();

        primary.getPhotos().addAll(movedPhotos);
        primary.setDuplicateCount(primary.getDuplicateCount() + 1);

        source.setMergedIntoReportId(primary.getId());
        // Intentionally do NOT mutate source.getPhotos() — the source is now a merged
        // stub that nothing queries anyway, and touching the collection adds no value
        // while exposing us to orphan-removal foot-guns.

        reportRepository.saveAll(List.of(primary, source));
    }

    /** Great-circle distance in metres between two WGS84 coordinates. */
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
