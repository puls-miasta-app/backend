package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulsePhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PulsePhotoRepository extends JpaRepository<PulsePhoto, Long> {

    List<PulsePhoto> findAllByPulseId(Long pulseId);
}
