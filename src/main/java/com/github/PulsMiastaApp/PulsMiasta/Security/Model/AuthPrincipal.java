package com.github.PulsMiastaApp.PulsMiasta.Security.Model;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Immutable principal record stored in the SecurityContext.
 * Implements UserDetails so Spring Security utilities (e.g. @AuthenticationPrincipal) work out of the box.
 * Obtain via: @AuthenticationPrincipal AuthPrincipal principal
 */
public record AuthPrincipal(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role,
        boolean emailVerified,
        String managedWojewodztwo,
        String managedPowiat,
        String managedGmina,
        String managedMiasto
) implements UserDetails {

    public static AuthPrincipal from(User user) {
        return new AuthPrincipal(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.isEmailVerified(),
                user.getManagedWojewodztwo(),
                user.getManagedPowiat(),
                user.getManagedGmina(),
                user.getManagedMiasto()
        );
    }

    public boolean isAdmin() {
        return role != null && !role.equals("USER");
    }

    public UserRole userRole() {
        try {
            return UserRole.valueOf(role);
        } catch (IllegalArgumentException | NullPointerException e) {
            return UserRole.USER;
        }
    }

    /**
     * Kolumna w tabeli pulses odpowiadająca poziomowi admina.
     * null = brak filtra (SUPER_ADMIN lub USER).
     */
    public String adminScopeColumn() {
        return switch (userRole()) {
            case ADMIN_MIASTA      -> "city";
            case ADMIN_GMINY       -> "gmina";
            case ADMIN_POWIATU     -> "powiat";
            case ADMIN_WOJEWODZTWA -> "wojewodztwo";
            default                -> null;
        };
    }

    /**
     * Wartość do filtrowania (znormalizowana — musi pasować do wartości w pulses).
     * null = brak filtra.
     */
    public String adminScopeValue() {
        return switch (userRole()) {
            case ADMIN_MIASTA      -> managedMiasto;
            case ADMIN_GMINY       -> managedGmina;
            case ADMIN_POWIATU     -> managedPowiat;
            case ADMIN_WOJEWODZTWA -> managedWojewodztwo;
            default                -> null;
        };
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    // UserDetails — password not exposed through the principal
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
