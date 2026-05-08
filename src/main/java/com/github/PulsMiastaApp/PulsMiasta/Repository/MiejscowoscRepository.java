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

    List<Miejscowosc> findByGminaIdOrderByName(Long gminaId);

    /**
     * Fetch-join całej hierarchii. Przyjmuje gotowy pattern (np. "%warszawa%")
     * z już escaped wildcards (\%, \_) przy użyciu ESCAPE '\'.
     */
    @Query("""
            SELECT m FROM Miejscowosc m
            JOIN FETCH m.gmina g
            JOIN FETCH g.powiat p
            JOIN FETCH p.wojewodztwo
            WHERE LOWER(m.name) LIKE :pattern ESCAPE '\\'
            ORDER BY m.name
            """)
    List<Miejscowosc> searchByNameWithHierarchy(@Param("pattern") String pattern, Pageable pageable);
}
