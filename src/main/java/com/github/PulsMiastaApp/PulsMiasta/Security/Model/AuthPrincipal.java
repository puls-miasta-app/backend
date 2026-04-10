package com.github.PulsMiastaApp.PulsMiasta.Security.Model;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
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
        boolean emailVerified
) implements UserDetails {

    public static AuthPrincipal from(User user) {
        return new AuthPrincipal(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.isEmailVerified()
        );
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
