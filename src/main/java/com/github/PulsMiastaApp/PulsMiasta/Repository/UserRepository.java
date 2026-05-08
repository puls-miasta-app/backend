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

    /** Admini których zasięg województw PRZECINA się z podanym zbiorem (filtr po ID). */
    @Query("SELECT DISTINCT u FROM User u JOIN u.managedWojewodztwa w WHERE u.role IN :roles AND w.id IN :wojIds ORDER BY u.id")
    List<User> findAdminsByWojewodztwaIds(@Param("roles") List<String> roles, @Param("wojIds") Collection<Long> wojIds);

    /** Admini których zasięg powiatów PRZECINA się z podanym zbiorem (filtr po ID). */
    @Query("SELECT DISTINCT u FROM User u JOIN u.managedPowiaty p WHERE u.role IN :roles AND p.id IN :powIds ORDER BY u.id")
    List<User> findAdminsByPowiatyIds(@Param("roles") List<String> roles, @Param("powIds") Collection<Long> powIds);

    /** Admini których zasięg gmin PRZECINA się z podanym zbiorem (filtr po ID). */
    @Query("SELECT DISTINCT u FROM User u JOIN u.managedGminy g WHERE u.role IN :roles AND g.id IN :gmIds ORDER BY u.id")
    List<User> findAdminsByGminyIds(@Param("roles") List<String> roles, @Param("gmIds") Collection<Long> gmIds);
}
