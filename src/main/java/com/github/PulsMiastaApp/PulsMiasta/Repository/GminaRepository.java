package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Gmina;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Powiat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GminaRepository extends JpaRepository<Gmina, Long> {

    List<Gmina> findByPowiatOrderByNameAscTypeAsc(Powiat powiat);

    Optional<Gmina> findByNameAndTypeAndPowiat(String name, String type, Powiat powiat);
}
