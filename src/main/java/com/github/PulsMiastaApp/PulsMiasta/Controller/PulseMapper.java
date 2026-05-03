package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.PulseResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.VoteDirection;

import java.time.Duration;
import java.time.LocalDateTime;

/** Konwersja Pulse -> PulseResponse zgodnie z kontraktem mobile. */
public final class PulseMapper {

    private PulseMapper() {}

    public static PulseResponse toResponse(Pulse pulse) {
        return toResponse(pulse, null);
    }

    public static PulseResponse toResponse(Pulse pulse, VoteDirection userVote) {
        var photos = pulse.getPhotos().stream()
                .map(p -> new PulseResponse.PhotoInfo(
                        p.getId(),
                        p.getOriginalFilename(),
                        p.getContentType(),
                        p.getFileSize()
                ))
                .toList();

        String email = pulse.getUser() != null ? pulse.getUser().getEmail() : null;

        return new PulseResponse(
                String.valueOf(pulse.getId()),
                nullToEmpty(pulse.getTitle()),
                nullToEmpty(pulse.getDescription()),
                pulse.getCategory() != null ? pulse.getCategory().label() : "",
                nullToEmpty(pulse.getDistrict()),
                nullToEmpty(pulse.getStreet()),
                nullToEmpty(pulse.getCity()),
                nullToEmpty(pulse.getAddress()),
                formatRelativeTime(pulse.getCreatedAt()),
                pulse.getCommentsCount() == null ? 0 : pulse.getCommentsCount(),
                pulse.score(),
                nullToEmpty(pulse.getHeat()),
                nullToEmpty(pulse.getAiNote()),
                nullToEmpty(pulse.getImageHint()),
                pulse.getPriority() != null ? pulse.getPriority().label() : "Standard",
                pulse.getStatus() != null ? pulse.getStatus().label() : "Nowe",
                pulse.getLatitude(),
                pulse.getLongitude(),
                email,
                userVote == null ? null : userVote.toApi(),
                photos
        );
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String formatRelativeTime(LocalDateTime createdAt) {
        if (createdAt == null) {
            return "";
        }
        Duration d = Duration.between(createdAt, LocalDateTime.now());
        long seconds = d.getSeconds();
        if (seconds < 60) return "przed chwilą";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min temu";
        long hours = minutes / 60;
        if (hours < 24) return hours + " godz temu";
        long days = hours / 24;
        if (days < 7) return days + " dni temu";
        return createdAt.toLocalDate().toString();
    }
}
