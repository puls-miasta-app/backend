package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Gmina;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Miejscowosc;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MiejscowoscRepository extends JpaRepository<Miejscowosc, Long> {

    List<Miejscowosc> findByGminaOrderByName(Gmina gmina);

    /** Fetch-join całej hierarchii, żeby uniknąć N+1 przy budowie odpowiedzi search. */
    @Query("""
            SELECT m FROM Miejscowosc m
            JOIN FETCH m.gmina g
            JOIN FETCH g.powiat p
            JOIN FETCH p.wojewodztwo
            WHERE LOWER(m.name) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY m.name
            """)
    List<Miejscowosc> searchByNameWithHierarchy(@Param("q") String query, Pageable pageable);
}
