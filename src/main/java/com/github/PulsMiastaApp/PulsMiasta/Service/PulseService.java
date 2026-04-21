package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Ai.PulseAiAnalysisService;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.VotePulseResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulsePhoto;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseVote;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.VoteDirection;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulsePhotoRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseVoteRepository;
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
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PulseService {

    private final PulseRepository pulseRepository;
    private final PulsePhotoRepository pulsePhotoRepository;
    private final PulseVoteRepository pulseVoteRepository;
    private final UserRepository userRepository;
    private final PhotoStorageService photoStorageService;
    private final PulseAiAnalysisService pulseAiAnalysisService;
    private final ReverseGeocodingService reverseGeocodingService;

    // ---------- CREATE (JSON body, bez pliku) ----------

    /**
     * Prosta ścieżka create używana przez mobile — body przychodzi jako JSON, fizyczne
     * zdjęcie (jeśli jest) dostarczane jest osobno przez {@code /v1/pulses/{id}/photo}.
     * Jeżeli caller dostarczył lat/lng, uruchamiamy async reverse geocoding żeby wypełnić
     * district/street.
     */
    @Transactional
    public Pulse createPulseMetadata(Long userId,
                                     PulseCategory category,
                                     String description,
                                     Double latitude,
                                     Double longitude,
                                     String address,
                                     String district,
                                     String street,
                                     String city) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Pulse pulse = new Pulse();
        pulse.setUser(user);
        pulse.setStatus(PulseStatus.NEW);
        pulse.setCategory(category);
        pulse.setPriority(PulsePriority.STANDARD); // AI może później podnieść
        pulse.setDescription(description);
        pulse.setLatitude(latitude);
        pulse.setLongitude(longitude);
        pulse.setAddress(address);
        pulse.setDistrict(district);
        pulse.setStreet(street);
        pulse.setCity(city);
        pulse.setTitle(defaultTitleFor(category));
        pulseRepository.save(pulse);

        final Long pulseId = pulse.getId();
        final boolean needsGeocoding = latitude != null && longitude != null
                && (district == null || street == null || city == null);
        if (needsGeocoding) {
            registerAfterCommit(() -> enrichLocationAsync(pulseId, latitude, longitude));
        }

        log.info("Pulse metadata created: id={}, category={}, district={}, street={}",
                pulseId, category, district, street);
        return pulse;
    }

    /**
     * Pełny create z plikiem — zachowuje poprzednią logikę z {@code ReportService}:
     * upload zdjęcia, szyfrowanie, async AI, dedup merge.
     */
    @Transactional
    public Pulse createPulseWithPhoto(Long userId,
                                      MultipartFile photo,
                                      PulseCategory category,
                                      String description,
                                      Double latitude,
                                      Double longitude,
                                      String address,
                                      String district,
                                      String street,
                                      String city) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        byte[] imageBytes = readBytes(photo);
        String contentType = photo.getContentType();
        String originalFilename = photo.getOriginalFilename();

        Pulse pulse = new Pulse();
        pulse.setUser(user);
        pulse.setStatus(PulseStatus.NEW);
        pulse.setCategory(category);
        pulse.setPriority(PulsePriority.STANDARD);
        pulse.setDescription(description);
        pulse.setLatitude(latitude);
        pulse.setLongitude(longitude);
        pulse.setAddress(address);
        pulse.setDistrict(district);
        pulse.setStreet(street);
        pulse.setCity(city);
        pulse.setTitle(defaultTitleFor(category));
        pulseRepository.save(pulse);

        PulsePhoto pulsePhoto = photoStorageService.uploadAndSavePhoto(
                imageBytes, originalFilename, contentType, user, pulse);
        pulse.getPhotos().add(pulsePhoto);

        registerRollbackCleanup(pulsePhoto.getObjectKey());

        final Long pulseId = pulse.getId();
        registerAfterCommit(() ->
                pulseAiAnalysisService.analyseAsync(pulseId, imageBytes, contentType));

        if (latitude != null && longitude != null && (district == null || street == null || city == null)) {
            registerAfterCommit(() -> enrichLocationAsync(pulseId, latitude, longitude));
        }

        log.info("Pulse created with photo: id={}, photoKey={}, lat={}, lng={}",
                pulseId, pulsePhoto.getObjectKey(), latitude, longitude);
        return pulse;
    }

    /**
     * Reverse-geocoding wywoływany poza transakcją (afterCommit). Aktualizujemy
     * pulse tylko jeżeli pola district/street są nadal puste w momencie zapisu —
     * nie nadpisujemy wartości, które mogła ustawić inna logika.
     */
    @Transactional
    public void enrichLocationAsync(Long pulseId, double latitude, double longitude) {
        try {
            ReverseGeocodingService.GeocodedAddress addr = reverseGeocodingService.reverse(latitude, longitude);
            if (!addr.hasAny()) {
                return;
            }
            Pulse pulse = pulseRepository.findById(pulseId).orElse(null);
            if (pulse == null) {
                return;
            }
            boolean changed = false;
            if ((pulse.getDistrict() == null || pulse.getDistrict().isBlank()) && addr.district() != null) {
                pulse.setDistrict(addr.district());
                changed = true;
            }
            if ((pulse.getStreet() == null || pulse.getStreet().isBlank()) && addr.street() != null) {
                pulse.setStreet(addr.street());
                changed = true;
            }
            if ((pulse.getCity() == null || pulse.getCity().isBlank()) && addr.city() != null) {
                pulse.setCity(addr.city());
                changed = true;
            }
            if ((pulse.getAddress() == null || pulse.getAddress().isBlank()) && addr.formattedAddress() != null) {
                pulse.setAddress(addr.formattedAddress());
                changed = true;
            }
            if (changed) {
                pulseRepository.save(pulse);
                log.info("Enriched pulse {} with district='{}', street='{}'",
                        pulseId, pulse.getDistrict(), pulse.getStreet());
            }
        } catch (Exception e) {
            log.warn("enrichLocationAsync failed for pulse {}: {}", pulseId, e.getMessage());
        }
    }

    // ---------- READ ----------

    @Transactional(readOnly = true)
    public List<Pulse> listFeed(String district, String street) {
        return listFeed(null, district, street);
    }

    @Transactional(readOnly = true)
    public List<Pulse> listFeed(String city, String district, String street) {
        String c = blankToNull(city);
        String d = blankToNull(district);
        String s = blankToNull(street);
        if (c != null && d != null && s != null) {
            return pulseRepository.findFeedByCityAndDistrictAndStreet(c, d, s);
        }
        if (c != null && d != null) {
            return pulseRepository.findFeedByCityAndDistrict(c, d);
        }
        if (c != null && s != null) {
            return pulseRepository.findFeedByCityAndStreet(c, s);
        }
        if (c != null) {
            return pulseRepository.findFeedByCity(c);
        }
        if (d != null && s != null) {
            return pulseRepository.findFeedByDistrictAndStreet(d, s);
        }
        if (d != null) {
            return pulseRepository.findFeedByDistrict(d);
        }
        if (s != null) {
            return pulseRepository.findFeedByStreet(s);
        }
        return pulseRepository.findFeedAll();
    }

    /** Zwraca kierunek głosu użytkownika dla pulse'a (null, jeśli nie głosował). */
    @Transactional(readOnly = true)
    public VoteDirection getUserVote(Long pulseId, Long userId) {
        if (pulseId == null || userId == null) return null;
        return pulseVoteRepository.findByPulseIdAndUserId(pulseId, userId)
                .map(PulseVote::getDirection)
                .orElse(null);
    }

    /** Bulk: mapa pulseId -> kierunek głosu dla danego usera. */
    @Transactional(readOnly = true)
    public java.util.Map<Long, VoteDirection> getUserVotes(java.util.Collection<Long> pulseIds, Long userId) {
        if (userId == null || pulseIds == null || pulseIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        return pulseVoteRepository.findAllByUserIdAndPulseIdIn(userId, pulseIds).stream()
                .collect(java.util.stream.Collectors.toMap(v -> v.getPulse().getId(), PulseVote::getDirection));
    }

    @Transactional(readOnly = true)
    public List<Pulse> listForUser(Long userId) {
        return pulseRepository.findAllVisibleToUser(userId);
    }

    @Transactional(readOnly = true)
    public Pulse getForUser(Long pulseId, Long userId) {
        Pulse pulse = pulseRepository.findWithPhotosById(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pulse not found"));
        pulse = resolveMerged(pulse);

        boolean isOwner = pulse.getUser() != null && userId.equals(pulse.getUser().getId());
        boolean hasContributed = pulse.getPhotos().stream()
                .anyMatch(p -> p.getUser() != null && userId.equals(p.getUser().getId()));
        if (!isOwner && !hasContributed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to view this pulse");
        }
        return pulse;
    }

    @Transactional(readOnly = true)
    public Pulse getAny(Long pulseId) {
        Pulse pulse = pulseRepository.findWithPhotosById(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pulse not found"));
        return resolveMerged(pulse);
    }

    public Pulse resolveMerged(Pulse pulse) {
        int hops = 0;
        while (pulse.getMergedIntoPulseId() != null && hops++ < 3) {
            Long target = pulse.getMergedIntoPulseId();
            pulse = pulseRepository.findById(target)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Merged target not found"));
        }
        return pulse;
    }

    @Transactional(readOnly = true)
    public Page<Pulse> listForAdmin(PulseStatus status, PulseCategory category, PulsePriority priority,
                                    int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return pulseRepository.findForAdmin(status, category, priority, pageable);
    }

    // ---------- VOTES ----------

    /**
     * Toggle + change głosowanie:
     * <ul>
     *   <li>Brak głosu → nowy głos w danym kierunku.</li>
     *   <li>Głos tego samego kierunku → usunięcie (toggle off, userVote=null).</li>
     *   <li>Głos przeciwnego kierunku → zmiana kierunku.</li>
     * </ul>
     */
    @Transactional
    public VotePulseResponse vote(Long userId, Long pulseId, VoteDirection direction) {
        Pulse pulse = pulseRepository.findById(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pulse not found"));

        // Jeżeli głosujemy na scalony stub, przenosimy głos na primary.
        pulse = resolveMerged(pulse);

        // Pobieramy primary z blokadą wierszową — licznik upvotes/downvotes jest
        // aktualizowany przez read-modify-write, więc bez locku tracimy inkrementy
        // przy współbieżnych głosach na tego samego pulse'a.
        pulse = pulseRepository.findByIdForUpdate(pulse.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pulse not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Long primaryPulseId = pulse.getId();
        Optional<PulseVote> existing = pulseVoteRepository.findByPulseIdAndUserId(primaryPulseId, userId);
        VoteDirection resulting;

        if (existing.isEmpty()) {
            PulseVote v = new PulseVote();
            v.setPulse(pulse);
            v.setUser(user);
            v.setDirection(direction);
            pulseVoteRepository.save(v);
            applyVoteDelta(pulse, null, direction);
            resulting = direction;
        } else {
            PulseVote existingVote = existing.get();
            if (existingVote.getDirection() == direction) {
                // toggle off
                pulseVoteRepository.delete(existingVote);
                applyVoteDelta(pulse, direction, null);
                resulting = null;
            } else {
                VoteDirection previous = existingVote.getDirection();
                existingVote.setDirection(direction);
                pulseVoteRepository.save(existingVote);
                applyVoteDelta(pulse, previous, direction);
                resulting = direction;
            }
        }

        pulseRepository.save(pulse);

        return new VotePulseResponse(
                String.valueOf(primaryPulseId),
                pulse.score(),
                resulting == null ? null : resulting.toApi()
        );
    }

    private void applyVoteDelta(Pulse pulse, VoteDirection previous, VoteDirection next) {
        if (previous == next) return;
        if (previous == VoteDirection.UP) pulse.setUpvotes(Math.max(0, pulse.getUpvotes() - 1));
        if (previous == VoteDirection.DOWN) pulse.setDownvotes(Math.max(0, pulse.getDownvotes() - 1));
        if (next == VoteDirection.UP) pulse.setUpvotes(pulse.getUpvotes() + 1);
        if (next == VoteDirection.DOWN) pulse.setDownvotes(pulse.getDownvotes() + 1);
    }

    // ---------- PHOTO DOWNLOAD ----------

    public record PhotoRef(
            Long id,
            String objectKey,
            String contentType,
            String originalFilename,
            Long fileSize
    ) {
        public String etag() {
            return "\"" + Integer.toHexString(objectKey.hashCode()) + "-" + fileSize + "\"";
        }
    }

    @Transactional(readOnly = true)
    public PhotoRef resolvePhotoForUser(Long photoId, Long userId) {
        PulsePhoto photo = loadPhoto(photoId);
        Pulse pulse = resolveMerged(photo.getPulse());

        boolean isOwner = pulse.getUser() != null && userId.equals(pulse.getUser().getId());
        boolean hasContributed = pulse.getPhotos().stream()
                .anyMatch(p -> p.getUser() != null && userId.equals(p.getUser().getId()));
        if (!isOwner && !hasContributed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to view this photo");
        }
        return toRef(photo);
    }

    @Transactional(readOnly = true)
    public PhotoRef resolvePhotoForAdmin(Long photoId) {
        return toRef(loadPhoto(photoId));
    }

    private PulsePhoto loadPhoto(Long photoId) {
        return pulsePhotoRepository.findById(photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found"));
    }

    private PhotoRef toRef(PulsePhoto photo) {
        return new PhotoRef(
                photo.getId(),
                photo.getObjectKey(),
                photo.getContentType(),
                photo.getOriginalFilename(),
                photo.getFileSize()
        );
    }

    // ---------- STATUS UPDATE (admin) ----------

    @Transactional
    public Pulse updateStatus(Long pulseId, PulseStatus newStatus) {
        Pulse pulse = pulseRepository.findById(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pulse not found"));
        pulse.setStatus(newStatus);
        return pulseRepository.save(pulse);
    }

    // ---------- HELPERS ----------

    private static String defaultTitleFor(PulseCategory category) {
        if (category == null) return "Nowe zgłoszenie";
        return switch (category) {
            case RUCH -> "Zgłoszenie: ruch";
            case BEZPIECZENSTWO -> "Zgłoszenie: bezpieczeństwo";
            case ZIELEN -> "Zgłoszenie: zieleń";
        };
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

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
