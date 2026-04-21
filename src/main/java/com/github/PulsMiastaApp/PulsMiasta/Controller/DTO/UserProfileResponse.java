package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import java.util.List;

public record UserProfileResponse(
        Long userId,
        String email,
        String firstName,
        String lastName,
        Stats stats,
        List<Badge> badges,
        Rank rank,
        List<PulseResponse> recentPulses
) {
    public record Stats(
            long pulsesSubmitted,
            long commentsPosted,
            long totalUpvotesReceived,
            long totalDownvotesReceived,
            long resolvedPulses
    ) {}

    public record Badge(
            String id,
            String name,
            String description,
            boolean earned
    ) {}

    public record Rank(
            int level,
            String name,
            long points,
            long pointsToNextLevel,
            int progressPercent
    ) {}
}
