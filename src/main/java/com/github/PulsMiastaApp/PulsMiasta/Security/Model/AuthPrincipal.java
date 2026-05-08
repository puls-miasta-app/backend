package com.github.PulsMiastaApp.PulsMiasta.Security.Model;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Gmina;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Miejscowosc;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Powiat;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Wojewodztwo;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable principal stored in the SecurityContext.
 * Scope fields hold sets of names (lowercase, normalized) so a single admin
 * can manage multiple województwa/powiaty/gminy/miejscowości simultaneously.
 */
public record AuthPrincipal(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role,
        boolean emailVerified,
        Set<String> managedWojewodztwa,
        Set<String> managedPowiaty,
        Set<String> managedGminy,
        Set<String> managedMiasta
) implements UserDetails {

    public static AuthPrincipal from(User user) {
        Set<String> wojew = user.getManagedWojewodztwa().stream()
                .map(Wojewodztwo::getName).collect(Collectors.toUnmodifiableSet());
        Set<String> pow = user.getManagedPowiaty().stream()
                .map(Powiat::getName).collect(Collectors.toUnmodifiableSet());
        Set<String> gm = user.getManagedGminy().stream()
                .map(Gmina::getName).collect(Collectors.toUnmodifiableSet());
        Set<String> miej = user.getManagedMiasta().stream()
                .map(Miejscowosc::getName).collect(Collectors.toUnmodifiableSet());
        return new AuthPrincipal(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.isEmailVerified(),
                wojew, pow, gm, miej
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
     * Zbiór wartości do filtrowania pulses wg zasięgu admina.
     * Pusty zbiór = brak ograniczeń (SUPER_ADMIN lub USER).
     */
    public Set<String> adminScopeValues() {
        return switch (userRole()) {
            case ADMIN_MIASTA      -> managedMiasta;
            case ADMIN_GMINY       -> managedGminy;
            case ADMIN_POWIATU     -> managedPowiaty;
            case ADMIN_WOJEWODZTWA -> managedWojewodztwa;
            default                -> Set.of();
        };
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
