package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Powiat;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Wojewodztwo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PowiatRepository extends JpaRepository<Powiat, Long> {

    List<Powiat> findByWojewodztwoOrderByName(Wojewodztwo wojewodztwo);

    List<Powiat> findByWojewodztwoIdOrderByName(Long wojewodztwoId);

    Optional<Powiat> findByNameAndWojewodztwo(String name, Wojewodztwo wojewodztwo);
}
