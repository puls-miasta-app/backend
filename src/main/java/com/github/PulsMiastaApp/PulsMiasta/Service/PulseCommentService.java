package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CommentResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseComment;
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

import java.util.List;

@Service
@RequiredArgsConstructor
public class PulseCommentService {

    private final PulseCommentRepository commentRepository;
    private final PulseRepository pulseRepository;
    private final PulseFeedJdbcRepository pulseFeedJdbcRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<CommentResponse> list(Long pulseId) {
        ensurePulseExists(pulseId);
        return commentRepository.findAllByPulseIdOrderByCreatedAtAsc(pulseId).stream()
                .map(PulseCommentService::toResponse)
                .toList();
    }

    @Transactional
    public CommentResponse create(Long pulseId, Long userId, String body) {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment body is required");
        }
        String trimmed = body.trim();
        if (trimmed.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment too long (max 2000 chars)");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // JDBC SELECT FOR UPDATE — JPA @Lock(@PESSIMISTIC_WRITE) wali S1009 na tabeli pulses
        pulseFeedJdbcRepository.findByIdForUpdate(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pulse not found"));

        // getReferenceById tworzy proxy (bez SELECT) — bezpieczne dla ustawienia FK na komentarzu
        Pulse pulseRef = pulseRepository.getReferenceById(pulseId);

        PulseComment c = new PulseComment();
        c.setPulse(pulseRef);
        c.setUser(user);
        c.setBody(trimmed);
        commentRepository.save(c);

        int newCount = (int) commentRepository.countByPulseId(pulseId);
        pulseFeedJdbcRepository.updateCommentsCount(pulseId, newCount);

        return toResponse(c);
    }

    private void ensurePulseExists(Long pulseId) {
        if (!pulseFeedJdbcRepository.existsById(pulseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pulse not found");
        }
    }

    public static CommentResponse toResponse(PulseComment c) {
        User u = c.getUser();
        return new CommentResponse(
                String.valueOf(c.getId()),
                String.valueOf(c.getPulse().getId()),
                u != null ? u.getId() : null,
                u != null ? u.getEmail() : null,
                u != null ? u.getFirstName() : null,
                u != null ? u.getLastName() : null,
                c.getBody(),
                c.getCreatedAt() != null ? c.getCreatedAt().toString() : null
        );
    }
}
