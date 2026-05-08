package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Gmina;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Miejscowosc;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MiejscowoscRepository extends JpaRepository<Miejscowosc, Long> {

    List<Miejscowosc> findByGminaOrderByName(Gmina gmina);

    List<Miejscowosc> findByGminaIdOrderByName(Long gminaId);

    /**
     * Wyszukiwanie z priorytetowaniem popularnych miejscowości:
     * 1. Dokładne dopasowanie nazwy przed częściowym
     * 2. Gmina "miejska" > "miejsko-wiejska" > "wiejska" (stolice i duże miasta są zawsze w "miejskiej")
     * 3. Alfabetycznie
     *
     * Przyjmuje pattern (np. "%warszawa%") i exact (np. "warszawa") — oba lowercase.
     */
    @Query("""
            SELECT m FROM Miejscowosc m
            JOIN FETCH m.gmina g
            JOIN FETCH g.powiat p
            JOIN FETCH p.wojewodztwo
            WHERE LOWER(m.name) LIKE :pattern ESCAPE '\\'
            ORDER BY
                CASE WHEN LOWER(m.name) = :exact THEN 0 ELSE 1 END,
                CASE g.type WHEN 'miejska' THEN 0 WHEN 'miejsko-wiejska' THEN 1 ELSE 2 END,
                m.name
            """)
    List<Miejscowosc> searchByNameWithHierarchy(
            @Param("pattern") String pattern,
            @Param("exact") String exact,
            Pageable pageable);

    @Query("""
            SELECT m FROM Miejscowosc m
            JOIN FETCH m.gmina g
            JOIN FETCH g.powiat p
            JOIN FETCH p.wojewodztwo
            WHERE m.id IN :ids
            """)
    List<Miejscowosc> findAllByIdInWithHierarchy(@Param("ids") Collection<Long> ids);
}
