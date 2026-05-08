package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    Optional<User> findByWebauthnUserHandle(byte[] webauthnUserHandle);

    @Query("""
            SELECT u FROM User u
            LEFT JOIN FETCH u.managedWojewodztwa
            LEFT JOIN FETCH u.managedPowiaty
            LEFT JOIN FETCH u.managedGminy
            LEFT JOIN FETCH u.managedMiasta
            WHERE u.id = :id
            """)
    Optional<User> findByIdWithGeo(@Param("id") Long id);

    @Query("SELECT DISTINCT u FROM User u WHERE u.role IN :roles ORDER BY u.id")
    List<User> findAllAdmins(@Param("roles") List<String> roles);

    /** Admini których zasięg województw PRZECINA się z podanym zbiorem. */
    @Query("SELECT DISTINCT u FROM User u JOIN u.managedWojewodztwa w WHERE u.role IN :roles AND w.name IN :wojNames ORDER BY u.id")
    List<User> findAdminsByWojewodztwa(@Param("roles") List<String> roles, @Param("wojNames") Collection<String> wojNames);

    /** Admini których zasięg powiatów PRZECINA się z podanym zbiorem. */
    @Query("SELECT DISTINCT u FROM User u JOIN u.managedPowiaty p WHERE u.role IN :roles AND p.name IN :powNames ORDER BY u.id")
    List<User> findAdminsByPowiaty(@Param("roles") List<String> roles, @Param("powNames") Collection<String> powNames);

    /** Admini których zasięg gmin PRZECINA się z podanym zbiorem. */
    @Query("SELECT DISTINCT u FROM User u JOIN u.managedGminy g WHERE u.role IN :roles AND g.name IN :gmNames ORDER BY u.id")
    List<User> findAdminsByGminy(@Param("roles") List<String> roles, @Param("gmNames") Collection<String> gmNames);
}
