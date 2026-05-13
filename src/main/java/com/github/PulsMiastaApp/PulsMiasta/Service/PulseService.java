package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Ai.PulseAiAnalysisService;
import com.github.PulsMiastaApp.PulsMiasta.Push.PushNotificationService;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.VotePulseResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulsePhoto;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseVote;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.VoteDirection;
import com.github.PulsMiastaApp.PulsMiasta.Repository.GminaRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PowiatRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseFeedJdbcRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulsePhotoRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseVoteRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.WojewodztwoRepository;
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
    private final PulseFeedJdbcRepository pulseFeedJdbcRepository;
    private final PulsePhotoRepository pulsePhotoRepository;
    private final PulseVoteRepository pulseVoteRepository;
    private final UserRepository userRepository;
    private final PhotoStorageService photoStorageService;
    private final PulseAiAnalysisService pulseAiAnalysisService;
    private final ReverseGeocodingService reverseGeocodingService;
    private final PushNotificationService pushNotificationService;
    private final WojewodztwoRepository wojRepository;
    private final PowiatRepository powiatRepository;
    private final GminaRepository gminaRepository;

    private Pulse buildPulse(User user,
                             PulseCategory category,
                             String description,
                             Double latitude,
                             Double longitude,
                             String address,
                             String district,
                             String street,
                             String city) {
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
        return pulse;
    }

    @Transactional
    public Pulse createPulse(Long userId,
                                       List<MultipartFile> photos,
                                       PulseCategory category,
                                       String description,
                                       Double latitude,
                                       Double longitude,
                                       String address,
                                       String district,
                                       String street,
                                       String city) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Użytkownik nie znaleziony"));

        Pulse pulse = buildPulse(user, category, description, latitude, longitude,
                address, district, street, city);
        pulseRepository.save(pulse);

        byte[] firstBytes = null;
        String firstContentType = null;

        for (MultipartFile photo : photos) {
            byte[] imageBytes = readBytes(photo);
            String contentType = photo.getContentType();
            String originalFilename = photo.getOriginalFilename();

            PulsePhoto pulsePhoto = photoStorageService.uploadAndSavePhoto(
                    imageBytes, originalFilename, contentType, user, pulse);
            pulse.getPhotos().add(pulsePhoto);
            registerRollbackCleanup(pulsePhoto.getObjectKey());

            if (firstBytes == null) {
                firstBytes = imageBytes;
                firstContentType = contentType;
            }
        }

        final Long pulseId = pulse.getId();
        final byte[] aiBytes = firstBytes;
        final String aiContentType = firstContentType;
        final Double aiLatitude  = latitude;
        final Double aiLongitude = longitude;
        registerAfterCommit(() ->
                pulseAiAnalysisService.analyseAsync(pulseId, aiBytes, aiContentType, aiLatitude, aiLongitude));

        if (latitude != null && longitude != null) {
            registerAfterCommit(() -> enrichLocationAsync(pulseId, latitude, longitude));
        }

        log.info("Pulse created with {} photo(s): id={}, lat={}, lng={}",
                photos.size(), pulseId, latitude, longitude);
        return pulse;
    }

    /**
     * Reverse-geocoding wywoływany poza transakcją (afterCommit). Aktualizujemy
     * pulse tylko jeżeli pola district/street są nadal puste w momencie zapisu —
     * nie nadpisujemy wartości, które mogła ustawić inna logika.
     * Używamy JDBC żeby ominąć bug Hibernate 7 + MySQL Connector/J na tabeli pulses.
     */
    @Transactional
    public void enrichLocationAsync(Long pulseId, double latitude, double longitude) {
        try {
            ReverseGeocodingService.GeocodedAddress addr = reverseGeocodingService.reverse(latitude, longitude);
            if (!addr.hasAny()) {
                return;
            }
            Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId).orElse(null);
            if (pulse == null) {
                return;
            }
            String district   = blank(pulse.getDistrict())   ? addr.district()         : pulse.getDistrict();
            String street     = blank(pulse.getStreet())      ? addr.street()           : pulse.getStreet();
            String city       = blank(pulse.getCity())        ? addr.city()             : pulse.getCity();
            String address    = blank(pulse.getAddress())     ? addr.formattedAddress() : pulse.getAddress();
            String gmina      = blank(pulse.getGmina())       ? addr.gmina()            : pulse.getGmina();
            String powiat     = blank(pulse.getPowiat())      ? addr.powiat()           : pulse.getPowiat();
            String woj        = blank(pulse.getWojewodztwo()) ? addr.wojewodztwo()      : pulse.getWojewodztwo();

            GeoIds geoIds = resolveGeoIds(addr);
            Long gminaId   = pulse.getGminaId()       != null ? pulse.getGminaId()       : geoIds.gminaId();
            Long powiatId  = pulse.getPowiatId()       != null ? pulse.getPowiatId()      : geoIds.powiatId();
            Long wojId     = pulse.getWojewodztwoId()  != null ? pulse.getWojewodztwoId() : geoIds.wojId();

            boolean changed = !java.util.Objects.equals(district, pulse.getDistrict())
                    || !java.util.Objects.equals(street,   pulse.getStreet())
                    || !java.util.Objects.equals(city,     pulse.getCity())
                    || !java.util.Objects.equals(address,  pulse.getAddress())
                    || !java.util.Objects.equals(gmina,    pulse.getGmina())
                    || !java.util.Objects.equals(powiat,   pulse.getPowiat())
                    || !java.util.Objects.equals(woj,      pulse.getWojewodztwo())
                    || !java.util.Objects.equals(gminaId,  pulse.getGminaId())
                    || !java.util.Objects.equals(powiatId, pulse.getPowiatId())
                    || !java.util.Objects.equals(wojId,    pulse.getWojewodztwoId());

            if (changed) {
                pulseFeedJdbcRepository.updateLocation(pulseId, district, street, city, address,
                        gmina, powiat, woj, gminaId, powiatId, wojId);
                log.info("Enriched pulse {} with city='{}', gmina='{}' (id={}), powiat='{}' (id={}), woj='{}' (id={})",
                        pulseId, city, gmina, gminaId, powiat, powiatId, woj, wojId);
            }
        } catch (Exception e) {
            log.warn("enrichLocationAsync failed for pulse {}: {}", pulseId, e.getMessage());
        }
    }

    private record GeoIds(Long gminaId, Long powiatId, Long wojId) {}

    /**
     * Rozwiązuje FK ID dla gminy/powiatu/województwa na podstawie znormalizowanych nazw
     * z reverse geocodingu. Przy kolizji nazw (dwie gminy o tej samej nazwie w powiecie)
     * zwraca null — bezpieczne false-negative zamiast false-positive.
     */
    private GeoIds resolveGeoIds(ReverseGeocodingService.GeocodedAddress addr) {
        if (addr.wojewodztwo() == null) return new GeoIds(null, null, null);

        var wojOpt = wojRepository.findByNameIgnoreCase(addr.wojewodztwo());
        if (wojOpt.isEmpty()) return new GeoIds(null, null, null);
        Long wojId = wojOpt.get().getId();

        if (addr.powiat() == null) return new GeoIds(null, null, wojId);
        var powOpt = powiatRepository.findByNameIgnoreCaseAndWojewodztwoId(addr.powiat(), wojId);
        if (powOpt.isEmpty()) return new GeoIds(null, null, wojId);
        Long powiatId = powOpt.get().getId();

        if (addr.gmina() == null) return new GeoIds(null, powiatId, wojId);
        var gminy = gminaRepository.findByNameIgnoreCaseAndPowiatId(addr.gmina(), powiatId);
        // Przy wielu wynikach (różne typy gminy) nie przypisujemy ID — false-negative zamiast false-positive
        Long gminaId = gminy.size() == 1 ? gminy.get(0).getId() : null;
        return new GeoIds(gminaId, powiatId, wojId);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ---------- READ ----------

    @Transactional(readOnly = true)
    public Page<Pulse> listFeed(String city, String district, String street,
                                String gmina, String powiat,
                                boolean isAdmin, Long userId,
                                int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return pulseFeedJdbcRepository.findFeed(
                blankToNull(city), blankToNull(district), blankToNull(street),
                blankToNull(gmina), blankToNull(powiat),
                isAdmin, userId, pageable);
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
        return pulseFeedJdbcRepository.findAllVisibleToUser(userId);
    }

    @Transactional(readOnly = true)
    public Pulse getForUser(Long pulseId, Long userId) {
        Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));
        pulse = resolveMerged(pulse);

        boolean isOwner = pulse.getUser() != null && userId.equals(pulse.getUser().getId());
        boolean hasContributed = pulse.getPhotos().stream()
                .anyMatch(p -> p.getUser() != null && userId.equals(p.getUser().getId()));
        if (!isOwner && !hasContributed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do tego zgłoszenia");
        }
        return pulse;
    }

    @Transactional(readOnly = true)
    public Pulse getAny(Long pulseId) {
        Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));
        return resolveMerged(pulse);
    }

    public Pulse resolveMerged(Pulse pulse) {
        int hops = 0;
        while (pulse.getMergedIntoPulseId() != null && hops++ < 3) {
            Long target = pulse.getMergedIntoPulseId();
            pulse = pulseFeedJdbcRepository.findByIdWithPhotos(target)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cel scalania nie znaleziony"));
        }
        return pulse;
    }

    @Transactional(readOnly = true)
    public List<Pulse> listDuplicates(Long pulseId) {
        Pulse primary = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));
        if (primary.getMergedIntoPulseId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Zgłoszenie " + pulseId + " jest duplikatem — odwołaj się do zgłoszenia głównego");
        }
        return pulseFeedJdbcRepository.findByMergedIntoPulseId(pulseId);
    }

    @Transactional(readOnly = true)
    public Page<Pulse> listForAdmin(PulseStatus status, PulseCategory category, PulsePriority priority,
                                    int page, int size,
                                    String scopeColumn, java.util.Set<String> scopeValues) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return pulseFeedJdbcRepository.findForAdmin(status, category, priority,
                scopeColumn, scopeValues, pageable);
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
        // Ładujemy przez JDBC — omija bug Hibernate 7 + MySQL Connector/J na tabeli pulses.
        Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));

        pulse = resolveMerged(pulse);

        // Blokada wierszowa (SELECT ... FOR UPDATE) przez JDBC — ta sama przyczyna.
        pulse = pulseFeedJdbcRepository.findByIdForUpdate(pulse.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Użytkownik nie znaleziony"));

        Long primaryPulseId = pulse.getId();
        // Proxy JPA — potrzebne tylko do ustawienia FK w PulseVote, nie wyzwala SELECT.
        Pulse pulseRef = pulseRepository.getReferenceById(primaryPulseId);

        Optional<PulseVote> existing = pulseVoteRepository.findByPulseIdAndUserId(primaryPulseId, userId);
        VoteDirection resulting;

        if (existing.isEmpty()) {
            PulseVote v = new PulseVote();
            v.setPulse(pulseRef);
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

        // UPDATE przez JDBC — unikamy em.merge() który wyzwoliłby Hibernate SELECT na pulses.
        pulseFeedJdbcRepository.updateVoteCounters(primaryPulseId, pulse.getUpvotes(), pulse.getDownvotes());

        return new VotePulseResponse(
                String.valueOf(primaryPulseId),
                pulse.score(),
                resulting == null ? null : resulting.toApi()
        );
    }

    /**
     * Usuwa głos użytkownika na dany pulse. Jeśli głos nie istnieje — no-op.
     * Zwraca aktualny score po operacji.
     */
    @Transactional
    public VotePulseResponse removeVote(Long userId, Long pulseId) {
        Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));
        pulse = resolveMerged(pulse);
        pulse = pulseFeedJdbcRepository.findByIdForUpdate(pulse.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));

        Long primaryPulseId = pulse.getId();
        Optional<PulseVote> existing = pulseVoteRepository.findByPulseIdAndUserId(primaryPulseId, userId);

        if (existing.isPresent()) {
            applyVoteDelta(pulse, existing.get().getDirection(), null);
            pulseVoteRepository.delete(existing.get());
            pulseFeedJdbcRepository.updateVoteCounters(primaryPulseId, pulse.getUpvotes(), pulse.getDownvotes());
        }

        return new VotePulseResponse(String.valueOf(primaryPulseId), pulse.score(), null);
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
    public PhotoRef resolvePhotoForUser(Long photoId, Long userId, boolean isAdmin) {
        PulsePhoto photo = loadPhoto(photoId);

        // Używamy pulseId (zwykły @Column) zamiast getPulse() —
        // getPulse() wyzwala Hibernate lazy load → SELECT na pulses → S1009.
        Long pulseId = photo.getPulseId();

        if (pulseId == null) {
            boolean isUploader = photo.getUser() != null && userId.equals(photo.getUser().getId());
            if (!isUploader) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do tego zdjęcia");
            }
            return toRef(photo);
        }

        Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));
        pulse = resolveMerged(pulse);

        boolean isOwner = pulse.getUser() != null && userId.equals(pulse.getUser().getId());
        boolean categoryAdminOnly = pulse.getCategory() != null && pulse.getCategory().isAdminOnly();
        boolean canSeePulse = isAdmin || isOwner || !categoryAdminOnly;
        if (!canSeePulse) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do tego zdjęcia");
        }
        return toRef(photo);
    }

    @Transactional(readOnly = true)
    public PhotoRef resolvePhotoForAdmin(Long photoId) {
        return toRef(loadPhoto(photoId));
    }

    private PulsePhoto loadPhoto(Long photoId) {
        return pulsePhotoRepository.findById(photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zdjęcie nie znalezione"));
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

    // ---------- MAP ----------

    @Transactional(readOnly = true)
    public List<Pulse> listMapPulses(double swLat, double swLng, double neLat, double neLng,
                                     PulseCategory category, PulseStatus status, int limit,
                                     boolean isAdmin) {
        return pulseFeedJdbcRepository.findInBounds(swLat, swLng, neLat, neLng,
                category, status, limit, isAdmin);
    }

    // ---------- STATUS UPDATE (admin) ----------

    @Transactional
    public Pulse updateStatus(Long pulseId, PulseStatus newStatus) {
        // findById via JPA wali S1009 (Hibernate 7 + MySQL Connector/J) — używamy JDBC
        Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));
        pulseFeedJdbcRepository.updateStatusById(pulseId, newStatus.name());
        pulse.setStatus(newStatus);
        registerAfterCommit(() -> pushNotificationService.notifyStatusChange(pulseId, newStatus));
        return pulse;
    }

    @Transactional
    public Pulse reviewPulse(Long pulseId, PulseCategory category, PulsePriority priority,
                              String title, String description) {
        Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));
        if (pulse.getStatus() != PulseStatus.PENDING_REVIEW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Zgłoszenie nie oczekuje na weryfikację (status: " + pulse.getStatus() + ")");
        }
        pulseFeedJdbcRepository.applyManualReview(pulseId,
                category.name(), priority.name(),
                title != null ? title : defaultTitleFor(category),
                description != null ? description : pulse.getDescription());
        pulse.setCategory(category);
        pulse.setPriority(priority);
        pulse.setStatus(PulseStatus.NEW);
        if (title != null) pulse.setTitle(title);
        registerAfterCommit(() -> pushNotificationService.notifyStatusChange(pulseId, PulseStatus.NEW));
        return pulse;
    }

    // ---------- HELPERS ----------

    private static String defaultTitleFor(PulseCategory category) {
        if (category == null) return "Nowe zgłoszenie";
        return switch (category) {
            case RUCH -> "Zgłoszenie: ruch";
            case BEZPIECZENSTWO -> "Zgłoszenie: bezpieczeństwo";
            case ZIELEN -> "Zgłoszenie: zieleń";
            case INCYDENTY -> "Zgłoszenie: incydent";
        };
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private byte[] readBytes(MultipartFile photo) {
        try {
            return photo.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Błąd odczytu przesłanego zdjęcia", e);
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
