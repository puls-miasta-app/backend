package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.PulseResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.VotePulseRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.VotePulseResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.VoteDirection;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.PulseService;
import com.github.PulsMiastaApp.PulsMiasta.Storage.PhotoStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;

/**
 * Endpointy używane bezpośrednio przez aplikację mobilną:
 * <ul>
 *   <li>{@code GET /v1/pulses} — feed z filtrowaniem po district/street</li>
 *   <li>{@code POST /v1/pulses} — utworzenie pulse'a (multipart: photos wymagane + lat/lon opcjonalne)</li>
 *   <li>{@code POST /v1/pulses/vote} — głosowanie up/down</li>
 *   <li>{@code GET /v1/pulses/me}, {@code /{id}}, {@code /photos/{photoId}} — szczegóły / własne</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/pulses")
@RequiredArgsConstructor
public class PulseController {

    private final PulseService pulseService;
    private final PhotoStorageService photoStorageService;

    // ---------- FEED ----------

    @GetMapping
    public ResponseEntity<SuccessResponse<Map<String, List<PulseResponse>>>> listFeed(
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "district", required = false) String district,
            @RequestParam(value = "street", required = false) String street,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);
        List<Pulse> pulses = pulseService.listFeed(city, district, street,
                principal.isAdmin(), principal.id());
        var pulseIds = pulses.stream().map(Pulse::getId).toList();
        var votes = pulseService.getUserVotes(pulseIds, principal.id());
        List<PulseResponse> items = pulses.stream()
                .map(p -> PulseMapper.toResponse(p, votes.get(p.getId())))
                .toList();
        return ResponseEntity.ok(SuccessResponse.of(Map.of("pulses", items)));
    }

    // ---------- CREATE ----------

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, PulseResponse>>> createPulse(
            @RequestParam("photos") List<MultipartFile> photos,
            @RequestParam(value = "latitude", required = false) Double latitude,
            @RequestParam(value = "longitude", required = false) Double longitude,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireEmailVerified(principal);
        if (photos == null || photos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one photo is required");
        }
        validateOptionalCoordinates(latitude, longitude);

        Pulse pulse = pulseService.createPulse(
                principal.id(), photos, null, null, latitude, longitude, null, null, null, null);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(Map.of("pulse", PulseMapper.toResponse(pulse))));
    }

    // ---------- VOTE ----------

    @PostMapping(path = "/vote", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<VotePulseResponse>> vote(
            @RequestBody VotePulseRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);
        if (body == null || body.pulseId() == null || body.pulseId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pulseId is required");
        }
        long pulseId;
        try {
            pulseId = Long.parseLong(body.pulseId());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pulseId must be numeric");
        }
        VoteDirection direction;
        try {
            direction = VoteDirection.fromApi(body.direction());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction must be 'up' or 'down'");
        }
        if (direction == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction is required");
        }
        VotePulseResponse response = pulseService.vote(principal.id(), pulseId, direction);
        return ResponseEntity.ok(SuccessResponse.of(response));
    }

    @DeleteMapping(path = "/vote/{pulseId}")
    public ResponseEntity<SuccessResponse<VotePulseResponse>> removeVote(
            @PathVariable("pulseId") Long pulseId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);
        VotePulseResponse response = pulseService.removeVote(principal.id(), pulseId);
        return ResponseEntity.ok(SuccessResponse.of(response));
    }

    // ---------- ME / DETAILS ----------

    @GetMapping("/me")
    public ResponseEntity<SuccessResponse<List<PulseResponse>>> listMyPulses(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);
        List<Pulse> pulses = pulseService.listForUser(principal.id());
        var ids = pulses.stream().map(Pulse::getId).toList();
        var votes = pulseService.getUserVotes(ids, principal.id());
        return ResponseEntity.ok(SuccessResponse.of(
                pulses.stream().map(p -> PulseMapper.toResponse(p, votes.get(p.getId()))).toList()));
    }

    @GetMapping("/{id}/duplicates")
    public ResponseEntity<SuccessResponse<Map<String, List<PulseResponse>>>> getDuplicates(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);
        List<Pulse> duplicates = pulseService.listDuplicates(id);
        List<PulseResponse> items = duplicates.stream()
                .map(PulseMapper::toResponse)
                .toList();
        return ResponseEntity.ok(SuccessResponse.of(Map.of("duplicates", items)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SuccessResponse<PulseResponse>> getPulse(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);
        Pulse pulse = pulseService.getAny(id);
        var userVote = pulseService.getUserVote(pulse.getId(), principal.id());
        return ResponseEntity.ok(SuccessResponse.of(PulseMapper.toResponse(pulse, userVote)));
    }

    // ---------- PHOTO STREAM ----------

    @GetMapping("/photos/{photoId}")
    public ResponseEntity<StreamingResponseBody> getPhoto(
            @PathVariable("photoId") Long photoId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);
        PulseService.PhotoRef ref = pulseService.resolvePhotoForUser(photoId, principal.id());
        return buildPhotoResponse(photoStorageService, ref, ifNoneMatch);
    }

    static ResponseEntity<StreamingResponseBody> buildPhotoResponse(
            PhotoStorageService photoStorageService,
            PulseService.PhotoRef ref,
            String ifNoneMatch) {

        String etag = ref.etag();
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
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
        headers.setCacheControl("private, max-age=3600, immutable");

        StreamingResponseBody body = out -> photoStorageService.streamDecrypted(ref.objectKey(), out);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    // ---------- helpers ----------

    private static void validateOptionalCoordinates(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return;
        }
        if (latitude == null || longitude == null
                || latitude.isNaN() || longitude.isNaN()
                || latitude.isInfinite() || longitude.isInfinite()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "latitude and longitude must both be provided");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "latitude must be between -90 and 90");
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "longitude must be between -180 and 180");
        }
    }

    private void requireAuthenticated(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    private void requireEmailVerified(AuthPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.emailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email must be verified before creating pulses");
        }
    }
}
