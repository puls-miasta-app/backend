package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    Optional<User> findByWebauthnUserHandle(byte[] webauthnUserHandle);

    @Query("SELECT u FROM User u WHERE u.role IN :roles ORDER BY u.id")
    List<User> findAllAdmins(@Param("roles") List<String> roles);

    @Query("SELECT u FROM User u WHERE u.role IN :roles AND u.managedWojewodztwo = :woj ORDER BY u.id")
    List<User> findAdminsByWojewodztwo(@Param("roles") List<String> roles, @Param("woj") String wojewodztwo);

    @Query("SELECT u FROM User u WHERE u.role IN :roles AND u.managedWojewodztwo = :woj AND u.managedPowiat = :pow ORDER BY u.id")
    List<User> findAdminsByPowiat(@Param("roles") List<String> roles, @Param("woj") String wojewodztwo, @Param("pow") String powiat);

    @Query("SELECT u FROM User u WHERE u.role IN :roles AND u.managedWojewodztwo = :woj AND u.managedPowiat = :pow AND u.managedGmina = :gm ORDER BY u.id")
    List<User> findAdminsByGmina(@Param("roles") List<String> roles, @Param("woj") String wojewodztwo, @Param("pow") String powiat, @Param("gm") String gmina);
}
