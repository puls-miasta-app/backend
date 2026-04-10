package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Report;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.ReportPhoto;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportStatus;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ReportRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Storage.PhotoStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final PhotoStorageService photoStorageService;

    @Transactional
    public Report createReport(Long userId, MultipartFile photo,
                               Double latitude, Double longitude, String address) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Report report = new Report();
        report.setUser(user);
        report.setStatus(ReportStatus.NEW);
        report.setLatitude(latitude);
        report.setLongitude(longitude);
        report.setAddress(address);
        reportRepository.save(report);

        ReportPhoto reportPhoto = photoStorageService.uploadAndSavePhoto(photo, user, report);
        report.getPhotos().add(reportPhoto);

        // If the surrounding transaction rolls back, the DB changes are undone but the
        // file already exists in R2. Register a compensating delete so we don't leak
        // orphaned objects in storage.
        registerRollbackCleanup(reportPhoto.getObjectKey());

        log.info("Report created: id={}, photoKey={}, lat={}, lng={}",
                report.getId(), reportPhoto.getObjectKey(), latitude, longitude);

        return report;
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
