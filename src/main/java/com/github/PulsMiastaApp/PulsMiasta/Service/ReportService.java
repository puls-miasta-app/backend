package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Report;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.ReportPhoto;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportStatus;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ReportRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Storage.PhotoStorageService;
import com.github.PulsMiastaApp.PulsMiasta.Storage.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
                .orElseThrow(() -> new StorageException("User not found"));

        Report report = new Report();
        report.setUser(user);
        report.setStatus(ReportStatus.NEW);
        report.setLatitude(latitude);
        report.setLongitude(longitude);
        report.setAddress(address);
        reportRepository.save(report);

        ReportPhoto reportPhoto = photoStorageService.uploadAndSavePhoto(photo, user, report);
        report.getPhotos().add(reportPhoto);

        log.info("Report created: id={}, photoKey={}, lat={}, lng={}",
                report.getId(), reportPhoto.getObjectKey(), latitude, longitude);

        return report;
    }
}
