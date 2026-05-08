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
 * Scope fields hold entity IDs (not names) to avoid name-collision bugs
 * (e.g. multiple gminas named "Lublin" in different powiats).
 * ADMIN_MIASTA is the exception — it still compares against the free-text city column
 * because Miejscowosc IDs are not stored on Pulse.
 */
public record AuthPrincipal(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role,
        boolean emailVerified,
        Set<Long> managedWojewodztwaIds,
        Set<Long> managedPowiatyIds,
        Set<Long> managedGminyIds,
        Set<String> managedMiasta
) implements UserDetails {

    public static AuthPrincipal from(User user) {
        boolean admin = !UserRole.USER.name().equals(user.getRole());
        Set<Long> wojew = admin
                ? user.getManagedWojewodztwa().stream().map(Wojewodztwo::getId).collect(Collectors.toUnmodifiableSet())
                : Set.of();
        Set<Long> pow = admin
                ? user.getManagedPowiaty().stream().map(Powiat::getId).collect(Collectors.toUnmodifiableSet())
                : Set.of();
        Set<Long> gm = admin
                ? user.getManagedGminy().stream().map(Gmina::getId).collect(Collectors.toUnmodifiableSet())
                : Set.of();
        Set<String> miej = admin
                ? user.getManagedMiasta().stream().map(Miejscowosc::getName).collect(Collectors.toUnmodifiableSet())
                : Set.of();
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
     * Kolumna w tabeli pulses do filtrowania:
     * gmina_id/powiat_id/wojewodztwo_id — porównanie po ID (bezpieczne).
     * city — porównanie tekstowe (ADMIN_MIASTA).
     * null — brak filtra (SUPER_ADMIN lub USER).
     */
    public String adminScopeColumn() {
        return switch (userRole()) {
            case ADMIN_MIASTA      -> "city";
            case ADMIN_GMINY       -> "gmina_id";
            case ADMIN_POWIATU     -> "powiat_id";
            case ADMIN_WOJEWODZTWA -> "wojewodztwo_id";
            default                -> null;
        };
    }

    /**
     * Wartości do filtrowania:
     * Dla ról ID-based (gminy/powiaty/woj) — string-reprezentacja ID, np. "123".
     * Dla ADMIN_MIASTA — nazwy miejscowości.
     * Pusty zbiór = brak ograniczeń (SUPER_ADMIN lub USER).
     */
    public Set<String> adminScopeValues() {
        return switch (userRole()) {
            case ADMIN_MIASTA      -> managedMiasta;
            case ADMIN_GMINY       -> toStringSet(managedGminyIds);
            case ADMIN_POWIATU     -> toStringSet(managedPowiatyIds);
            case ADMIN_WOJEWODZTWA -> toStringSet(managedWojewodztwaIds);
            default                -> Set.of();
        };
    }

    private static Set<String> toStringSet(Set<Long> ids) {
        return ids.stream().map(Object::toString).collect(Collectors.toUnmodifiableSet());
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
