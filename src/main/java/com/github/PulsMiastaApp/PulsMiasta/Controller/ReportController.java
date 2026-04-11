package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ReportResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Report;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.ReportService;
import com.github.PulsMiastaApp.PulsMiasta.Storage.PhotoStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * User-facing report endpoints. A regular user can:
 * - POST a new report (may be merged into an existing one if close by),
 * - list their own reports (including ones they contributed a photo to via merge),
 * - fetch a single report they are involved in.
 *
 * Admin-only listing and status updates live in {@link AdminReportController}.
 */
@RestController
@RequestMapping("/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final PhotoStorageService photoStorageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SuccessResponse<ReportResponse>> createReport(
            @RequestParam("file") MultipartFile file,
            @RequestParam("latitude") Double latitude,
            @RequestParam("longitude") Double longitude,
            @RequestParam(value = "address", required = false) String address,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireEmailVerified(principal);
        validateCoordinates(latitude, longitude);

        Report report = reportService.createReport(principal.id(), file, latitude, longitude, address);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(ReportMapper.toResponse(report)));
    }

    /**
     * Guard against out-of-range WGS84 coordinates. An out-of-bounds latitude would
     * poison the dedup bounding box via {@code cos(lat)} and {@code NaN} deltas, so
     * we reject the whole request with 400 before touching the DB.
     */
    private static void validateCoordinates(Double latitude, Double longitude) {
        if (latitude == null || longitude == null
                || latitude.isNaN() || longitude.isNaN()
                || latitude.isInfinite() || longitude.isInfinite()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "latitude and longitude are required");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "latitude must be between -90 and 90");
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "longitude must be between -180 and 180");
        }
    }

    @GetMapping("/me")
    public ResponseEntity<SuccessResponse<List<ReportResponse>>> listMyReports(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);
        List<Report> reports = reportService.listForUser(principal.id());
        return ResponseEntity.ok(SuccessResponse.of(
                reports.stream().map(ReportMapper::toResponse).toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SuccessResponse<ReportResponse>> getMyReport(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);
        Report report = reportService.getForUser(id, principal.id());
        return ResponseEntity.ok(SuccessResponse.of(ReportMapper.toResponse(report)));
    }

    /**
     * Stream a decrypted photo back to the user. Access is allowed only if the user
     * is the report owner or has contributed a photo to it. Honours
     * {@code If-None-Match} so repeat loads return {@code 304 Not Modified}.
     */
    @GetMapping("/photos/{photoId}")
    public ResponseEntity<StreamingResponseBody> getPhoto(
            @PathVariable("photoId") Long photoId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);
        ReportService.PhotoRef ref = reportService.resolvePhotoForUser(photoId, principal.id());
        return buildPhotoResponse(photoStorageService, ref, ifNoneMatch);
    }

    static ResponseEntity<StreamingResponseBody> buildPhotoResponse(
            PhotoStorageService photoStorageService,
            ReportService.PhotoRef ref,
            String ifNoneMatch) {

        String etag = ref.etag();
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            // Client already has a fresh copy — don't touch R2 at all.
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        MediaType mediaType = ref.contentType() != null
                ? MediaType.parseMediaType(ref.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        if (ref.fileSize() != null && ref.fileSize() > 0) {
            headers.setContentLength(ref.fileSize());
        }
        if (ref.originalFilename() != null && !ref.originalFilename().isBlank()) {
            headers.setContentDisposition(ContentDisposition.inline()
                    .filename(ref.originalFilename())
                    .build());
        }
        headers.setETag(etag);
        // Photos are immutable once uploaded — safe to cache in the browser.
        headers.setCacheControl("private, max-age=3600, immutable");

        StreamingResponseBody body = out -> photoStorageService.streamDecrypted(ref.objectKey(), out);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private void requireAuthenticated(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    private void requireEmailVerified(AuthPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.emailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email must be verified before creating reports");
        }
    }
}
