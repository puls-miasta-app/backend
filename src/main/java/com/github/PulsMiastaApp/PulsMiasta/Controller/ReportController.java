package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ErrorResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ReportResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Crypto.EncryptionException;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Report;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.ReportService;
import com.github.PulsMiastaApp.PulsMiasta.Storage.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createReport(
            @RequestParam("file") MultipartFile file,
            @RequestParam("latitude") Double latitude,
            @RequestParam("longitude") Double longitude,
            @RequestParam(value = "address", required = false) String address,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireEmailVerified(principal);

        try {
            Report report = reportService.createReport(principal.id(), file, latitude, longitude, address);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(SuccessResponse.of(toResponse(report)));
        } catch (StorageException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of(e.getMessage(), "storage_error"));
        } catch (EncryptionException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.of("Encryption error", "encryption_error"));
        } catch (Exception e) {
            log.error("Unexpected error creating report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.of("Internal server error", "internal_server_error"));
        }
    }

    private ReportResponse toResponse(Report report) {
        var photos = report.getPhotos().stream()
                .map(p -> new ReportResponse.PhotoInfo(
                        p.getId(),
                        p.getObjectKey(),
                        p.getOriginalFilename(),
                        p.getContentType(),
                        p.getFileSize()
                ))
                .toList();

        return new ReportResponse(
                report.getId(),
                report.getStatus().name(),
                report.getCategory() != null ? report.getCategory().name() : null,
                report.getPriority() != null ? report.getPriority().name() : null,
                report.getDescription(),
                report.getLatitude(),
                report.getLongitude(),
                report.getAddress(),
                photos,
                report.getCreatedAt()
        );
    }

    private void requireEmailVerified(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!principal.emailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email must be verified before creating reports");
        }
    }
}
