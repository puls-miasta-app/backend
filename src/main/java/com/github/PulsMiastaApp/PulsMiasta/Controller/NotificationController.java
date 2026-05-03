package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.NotificationDtos;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, NotificationDtos.DeviceResponse>>> register(
            @RequestBody NotificationDtos.RegisterDeviceRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuth(principal);
        var device = notificationService.register(principal.id(), body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(Map.of("device", device)));
    }

    @PostMapping(path = "/unregister", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, Boolean>>> unregister(
            @RequestBody NotificationDtos.UnregisterDeviceRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuth(principal);
        notificationService.unregister(principal.id(), body == null ? null : body.pushToken());
        return ResponseEntity.ok(SuccessResponse.of(Map.of("success", true)));
    }

    @GetMapping("/devices")
    public ResponseEntity<SuccessResponse<Map<String, List<NotificationDtos.DeviceResponse>>>> listDevices(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuth(principal);
        return ResponseEntity.ok(SuccessResponse.of(
                Map.of("devices", notificationService.listDevices(principal.id()))));
    }

    @GetMapping("/preferences")
    public ResponseEntity<SuccessResponse<NotificationDtos.PreferencesResponse>> getPreferences(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuth(principal);
        return ResponseEntity.ok(SuccessResponse.of(notificationService.getPreferences(principal.id())));
    }

    @PutMapping(path = "/preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<NotificationDtos.PreferencesResponse>> updatePreferences(
            @RequestBody NotificationDtos.PreferencesRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuth(principal);
        return ResponseEntity.ok(SuccessResponse.of(
                notificationService.updatePreferences(principal.id(), body)));
    }

    private static void requireAuth(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }
}
