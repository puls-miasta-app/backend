package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Ai.ReportAiAnalysisService;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Report;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.ReportPhoto;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportPriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportStatus;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ReportPhotoRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ReportRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Storage.PhotoStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final ReportPhotoRepository reportPhotoRepository;
    private final UserRepository userRepository;
    private final PhotoStorageService photoStorageService;
    private final ReportAiAnalysisService reportAiAnalysisService;

    // ---------- CREATE ----------

    @Transactional
    public Report createReport(Long userId, MultipartFile photo,
                               Double latitude, Double longitude, String address) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Grab the bytes up-front so we can hand them to the async AI worker.
        // MultipartFile is tied to the request thread and is recycled once the
        // controller returns, so reading later from a background thread is not safe.
        byte[] imageBytes = readBytes(photo);
        String contentType = photo.getContentType();

        // Deduplication is deferred to the async AI worker — we need the AI-assigned
        // category before merging so a pothole and a broken lamp at the same spot stay
        // separate reports.
        Report report = new Report();
        report.setUser(user);
        report.setStatus(ReportStatus.NEW);
        report.setLatitude(latitude);
        report.setLongitude(longitude);
        report.setAddress(address);
        reportRepository.save(report);

        ReportPhoto reportPhoto = photoStorageService.uploadAndSavePhoto(photo, user, report);
        report.getPhotos().add(reportPhoto);

        registerRollbackCleanup(reportPhoto.getObjectKey());

        Long reportId = report.getId();
        registerAfterCommit(() ->
                reportAiAnalysisService.analyseAsync(reportId, imageBytes, contentType));

        log.info("Report created: id={}, photoKey={}, lat={}, lng={}",
                report.getId(), reportPhoto.getObjectKey(), latitude, longitude);

        return report;
    }

    // ---------- READ ----------

    @Transactional(readOnly = true)
    public List<Report> listForUser(Long userId) {
        return reportRepository.findAllVisibleToUser(userId);
    }

    @Transactional(readOnly = true)
    public Report getForUser(Long reportId, Long userId) {
        Report report = reportRepository.findWithPhotosById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));

        // If this is a merged stub, transparently follow the link to the primary report.
        report = resolveMerged(report);

        boolean isOwner = report.getUser() != null && userId.equals(report.getUser().getId());
        boolean hasContributed = report.getPhotos().stream()
                .anyMatch(p -> p.getUser() != null && userId.equals(p.getUser().getId()));
        if (!isOwner && !hasContributed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to view this report");
        }
        return report;
    }

    private Report resolveMerged(Report report) {
        // A single level of indirection is enough since merge never chains (we always
        // redirect to the ultimate primary), but guard against a short loop just in case.
        int hops = 0;
        while (report.getMergedIntoReportId() != null && hops++ < 3) {
            Long target = report.getMergedIntoReportId();
            report = reportRepository.findById(target)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merged target not found"));
        }
        return report;
    }

    @Transactional(readOnly = true)
    public Page<Report> listForAdmin(ReportStatus status, ReportCategory category, ReportPriority priority,
                                     int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return reportRepository.findForAdmin(status, category, priority, pageable);
    }

    @Transactional(readOnly = true)
    public Report getForAdmin(Long reportId) {
        Report report = reportRepository.findWithPhotosById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        return resolveMerged(report);
    }

    // ---------- PHOTO DOWNLOAD ----------

    /**
     * Lightweight descriptor of a photo: just enough metadata to stream the
     * decrypted body from R2 and set correct response headers. No byte arrays
     * pass through this record, so it's cheap to pass between layers.
     */
    public record PhotoRef(
            Long id,
            String objectKey,
            String contentType,
            String originalFilename,
            Long fileSize
    ) {
        /** Strong ETag derived from the immutable object key — photos never change in place. */
        public String etag() {
            return "\"" + Integer.toHexString(objectKey.hashCode()) + "-" + fileSize + "\"";
        }
    }

    /**
     * Authorize the user and return a reference to the photo. The controller
     * then streams the decrypted content straight from R2 — no full-file buffer
     * in the service layer.
     */
    @Transactional(readOnly = true)
    public PhotoRef resolvePhotoForUser(Long photoId, Long userId) {
        ReportPhoto photo = loadPhoto(photoId);
        Report report = resolveMerged(photo.getReport());

        boolean isOwner = report.getUser() != null && userId.equals(report.getUser().getId());
        boolean hasContributed = report.getPhotos().stream()
                .anyMatch(p -> p.getUser() != null && userId.equals(p.getUser().getId()));
        if (!isOwner && !hasContributed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to view this photo");
        }
        return toRef(photo);
    }

    /** Same as {@link #resolvePhotoForUser} but without the ownership check — admin use. */
    @Transactional(readOnly = true)
    public PhotoRef resolvePhotoForAdmin(Long photoId) {
        return toRef(loadPhoto(photoId));
    }

    private ReportPhoto loadPhoto(Long photoId) {
        return reportPhotoRepository.findById(photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found"));
    }

    private PhotoRef toRef(ReportPhoto photo) {
        return new PhotoRef(
                photo.getId(),
                photo.getObjectKey(),
                photo.getContentType(),
                photo.getOriginalFilename(),
                photo.getFileSize()
        );
    }

    // ---------- STATUS UPDATE ----------

    @Transactional
    public Report updateStatus(Long reportId, ReportStatus newStatus) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        report.setStatus(newStatus);
        return reportRepository.save(report);
    }

    // ---------- HELPERS ----------

    private byte[] readBytes(MultipartFile photo) {
        try {
            return photo.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read uploaded photo", e);
        }
    }

    private void registerAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private void registerRollbackCleanup(String objectKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        log.warn("Transaction rolled back, deleting orphaned object: {}", objectKey);
                        photoStorageService.deleteObject(objectKey);
                    }
                }
            });
        }
    }
}
