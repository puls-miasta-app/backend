package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.PulseResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.UserProfileResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.PulseMapper;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseCommentRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseFeedJdbcRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final PulseRepository pulseRepository;
    private final PulseFeedJdbcRepository pulseFeedJdbcRepository;
    private final PulseCommentRepository commentRepository;

    private static final int RECENT_PULSES_LIMIT = 10;

    // Proste progi rang: każdy level kosztuje coraz więcej punktów.
    private static final int[] LEVEL_THRESHOLDS = {0, 10, 30, 75, 150, 300, 600, 1200, 2500, 5000};
    private static final String[] LEVEL_NAMES = {
            "Obserwator", "Mieszkaniec", "Aktywista", "Lokalny bohater",
            "Strażnik dzielnicy", "Ekspert miasta", "Mentor", "Legenda",
            "Wizjoner", "Puls Miasta"
    };

    @Transactional(readOnly = true)
    public UserProfileResponse getForUser(Long userId) {
        return getForUser(userId, true);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getForUser(Long userId, boolean includePii) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        var statsRow = pulseRepository.aggregateStatsForUser(userId);
        long pulsesSubmitted = statsRow == null ? 0 : statsRow.getPulsesSubmitted();
        long totalUp = statsRow == null ? 0 : statsRow.getTotalUpvotes();
        long totalDown = statsRow == null ? 0 : statsRow.getTotalDownvotes();
        long resolved = statsRow == null ? 0 : statsRow.getResolvedPulses();
        long commentsPosted = commentRepository.countByUserId(userId);

        var stats = new UserProfileResponse.Stats(
                pulsesSubmitted, commentsPosted, totalUp, totalDown, resolved);

        long points = computePoints(stats);
        var rank = computeRank(points);
        var badges = computeBadges(stats);

        List<PulseResponse> recent = pulseFeedJdbcRepository
                .findRecentByUser(userId, RECENT_PULSES_LIMIT)
                .stream()
                .map(p -> PulseMapper.toResponse(p, null))
                .toList();

        return new UserProfileResponse(
                user.getId(),
                includePii ? user.getEmail() : null,
                user.getFirstName(),
                user.getLastName(),
                stats,
                badges,
                rank,
                recent
        );
    }

    private long computePoints(UserProfileResponse.Stats s) {
        return s.pulsesSubmitted() * 5
                + s.commentsPosted() * 1
                + s.totalUpvotesReceived() * 2
                + s.resolvedPulses() * 10;
    }

    private UserProfileResponse.Rank computeRank(long points) {
        int level = 0;
        for (int i = 0; i < LEVEL_THRESHOLDS.length; i++) {
            if (points >= LEVEL_THRESHOLDS[i]) level = i;
        }
        int nextIdx = Math.min(level + 1, LEVEL_THRESHOLDS.length - 1);
        long currentThreshold = LEVEL_THRESHOLDS[level];
        long nextThreshold = LEVEL_THRESHOLDS[nextIdx];
        long toNext = Math.max(0, nextThreshold - points);
        int progress;
        if (nextThreshold == currentThreshold) {
            progress = 100;
        } else {
            progress = (int) Math.min(100,
                    ((points - currentThreshold) * 100) / (nextThreshold - currentThreshold));
        }
        return new UserProfileResponse.Rank(
                level + 1,
                LEVEL_NAMES[Math.min(level, LEVEL_NAMES.length - 1)],
                points,
                toNext,
                progress
        );
    }

    private List<UserProfileResponse.Badge> computeBadges(UserProfileResponse.Stats s) {
        List<UserProfileResponse.Badge> list = new ArrayList<>();
        list.add(new UserProfileResponse.Badge(
                "first_pulse", "Pierwsze zgłoszenie",
                "Dodaj swoje pierwsze zgłoszenie", s.pulsesSubmitted() >= 1));
        list.add(new UserProfileResponse.Badge(
                "active_reporter", "Aktywny reporter",
                "Dodaj 10 zgłoszeń", s.pulsesSubmitted() >= 10));
        list.add(new UserProfileResponse.Badge(
                "city_guardian", "Strażnik miasta",
                "Dodaj 50 zgłoszeń", s.pulsesSubmitted() >= 50));
        list.add(new UserProfileResponse.Badge(
                "commenter", "Komentator",
                "Napisz 25 komentarzy", s.commentsPosted() >= 25));
        list.add(new UserProfileResponse.Badge(
                "helpful", "Pomocny",
                "Zdobądź 50 głosów za", s.totalUpvotesReceived() >= 50));
        list.add(new UserProfileResponse.Badge(
                "resolver", "Skuteczny",
                "Miej 5 rozwiązanych zgłoszeń", s.resolvedPulses() >= 5));
        return list;
    }
}
