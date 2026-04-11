package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ReportResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Report;

/** Shared Report -> ReportResponse mapper used by user and admin controllers. */
final class ReportMapper {

    private ReportMapper() {}

    static ReportResponse toResponse(Report report) {
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
                report.getDuplicateCount(),
                photos,
                report.getCreatedAt()
        );
    }
}
