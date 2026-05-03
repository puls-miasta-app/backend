package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseVote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PulseVoteRepository extends JpaRepository<PulseVote, Long> {

    Optional<PulseVote> findByPulseIdAndUserId(Long pulseId, Long userId);

    List<PulseVote> findAllByUserIdAndPulseIdIn(Long userId, java.util.Collection<Long> pulseIds);
}
