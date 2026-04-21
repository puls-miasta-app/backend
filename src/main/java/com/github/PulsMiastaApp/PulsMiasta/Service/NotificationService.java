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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pushToken is required");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        DeviceRegistration device = deviceRepo.findByPushToken(req.pushToken())
                .orElseGet(DeviceRegistration::new);
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pushToken is required");
        }
        deviceRepo.findByPushToken(pushToken).ifPresent(device -> {
            if (device.getUser() == null || !userId.equals(device.getUser().getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Device belongs to another user");
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
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
                d.getPushToken(),
                d.getPlatform(),
                d.getDeviceName(),
                d.getLocale(),
                d.getCreatedAt() != null ? d.getCreatedAt().toString() : null,
                d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null
        );
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
