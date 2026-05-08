package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Wojewodztwo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WojewodztwoRepository extends JpaRepository<Wojewodztwo, Long> {

    Optional<Wojewodztwo> findByName(String name);

    Optional<Wojewodztwo> findByNameIgnoreCase(String name);

    List<Wojewodztwo> findAllByOrderByNameAsc();
}
