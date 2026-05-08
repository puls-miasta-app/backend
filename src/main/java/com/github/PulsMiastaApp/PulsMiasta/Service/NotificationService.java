package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.NotificationDtos;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.DeviceRegistration;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.NotificationPreferences;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.DeviceRegistrationRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.NotificationPreferencesRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final DeviceRegistrationRepository deviceRepo;
    private final NotificationPreferencesRepository prefsRepo;
    private final UserRepository userRepository;

    @Transactional
    public NotificationDtos.DeviceResponse register(Long userId, NotificationDtos.RegisterDeviceRequest req) {
        if (req == null || req.pushToken() == null || req.pushToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token powiadomień jest wymagany");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Użytkownik nie znaleziony"));

        DeviceRegistration device = deviceRepo.findByPushToken(req.pushToken())
                .orElseGet(DeviceRegistration::new);
        // Reject reassigning a token that already belongs to a different user —
        // otherwise user B could register with user A's token and steal delivery.
        if (device.getId() != null
                && device.getUser() != null
                && !userId.equals(device.getUser().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Token powiadomień jest już przypisany do innego użytkownika");
        }
        device.setUser(user);
        device.setPushToken(req.pushToken());
        device.setPlatform(req.platform());
        device.setDeviceName(req.deviceName());
        device.setLocale(req.locale());
        deviceRepo.save(device);
        return toResponse(device);
    }

    @Transactional
    public void unregister(Long userId, String pushToken) {
        if (pushToken == null || pushToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token powiadomień jest wymagany");
        }
        deviceRepo.findByPushToken(pushToken).ifPresent(device -> {
            if (device.getUser() == null || !userId.equals(device.getUser().getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Urządzenie należy do innego użytkownika");
            }
            deviceRepo.delete(device);
        });
    }

    @Transactional(readOnly = true)
    public List<NotificationDtos.DeviceResponse> listDevices(Long userId) {
        return deviceRepo.findAllByUserId(userId).stream()
                .map(NotificationService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public NotificationDtos.PreferencesResponse getPreferences(Long userId) {
        NotificationPreferences prefs = prefsRepo.findByUserId(userId).orElse(null);
        if (prefs == null) {
            return new NotificationDtos.PreferencesResponse(true, true, true, true, true);
        }
        return toPrefsResponse(prefs);
    }

    @Transactional
    public NotificationDtos.PreferencesResponse updatePreferences(Long userId,
                                                                  NotificationDtos.PreferencesRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Użytkownik nie znaleziony"));
        NotificationPreferences prefs = prefsRepo.findByUserId(userId).orElseGet(() -> {
            NotificationPreferences p = new NotificationPreferences();
            p.setUser(user);
            return p;
        });

        if (req != null) {
            if (req.pushEnabled() != null) prefs.setPushEnabled(req.pushEnabled());
            if (req.emailEnabled() != null) prefs.setEmailEnabled(req.emailEnabled());
            if (req.nearbyPulsesEnabled() != null) prefs.setNearbyPulsesEnabled(req.nearbyPulsesEnabled());
            if (req.statusUpdatesEnabled() != null) prefs.setStatusUpdatesEnabled(req.statusUpdatesEnabled());
            if (req.commentRepliesEnabled() != null) prefs.setCommentRepliesEnabled(req.commentRepliesEnabled());
        }
        prefsRepo.save(prefs);
        return toPrefsResponse(prefs);
    }

    private static NotificationDtos.DeviceResponse toResponse(DeviceRegistration d) {
        return new NotificationDtos.DeviceResponse(
                d.getId(),
                maskToken(d.getPushToken()),
                d.getPlatform(),
                d.getDeviceName(),
                d.getLocale(),
                d.getCreatedAt() != null ? d.getCreatedAt().toString() : null,
                d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null
        );
    }

    // Push tokens are credentials used to deliver notifications — never return the
    // raw value in API responses. Keep just enough for the user to identify a
    // specific device registration.
    private static String maskToken(String token) {
        if (token == null) return null;
        int len = token.length();
        if (len <= 4) return "****";
        return "****" + token.substring(len - 4);
    }

    private static NotificationDtos.PreferencesResponse toPrefsResponse(NotificationPreferences p) {
        return new NotificationDtos.PreferencesResponse(
                p.isPushEnabled(),
                p.isEmailEnabled(),
                p.isNearbyPulsesEnabled(),
                p.isStatusUpdatesEnabled(),
                p.isCommentRepliesEnabled()
        );
    }
}
