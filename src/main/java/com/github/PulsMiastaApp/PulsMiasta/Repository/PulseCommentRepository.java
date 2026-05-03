package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PulseCommentRepository extends JpaRepository<PulseComment, Long> {

    List<PulseComment> findAllByPulseIdOrderByCreatedAtAsc(Long pulseId);

    long countByPulseId(Long pulseId);

    long countByUserId(Long userId);
}
