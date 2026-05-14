package com.github.PulsMiastaApp.PulsMiasta.Bootstrap;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.UserRole;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultSuperAdminBootstrap {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${bootstrap.super-admin.email:}")
    private String email;

    @Value("${bootstrap.super-admin.password:}")
    private String password;

    @Value("${bootstrap.super-admin.first-name:Super}")
    private String firstName;

    @Value("${bootstrap.super-admin.last-name:Admin}")
    private String lastName;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureDefaultSuperAdmin() {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.debug("Bootstrap super-admin pominięty — brak zmiennych BOOTSTRAP_SUPER_ADMIN_EMAIL/PASSWORD");
            return;
        }

        userRepository.findByEmail(email).ifPresentOrElse(existing -> {
            boolean changed = false;
            if (!UserRole.SUPER_ADMIN.name().equals(existing.getRole())) {
                existing.setRole(UserRole.SUPER_ADMIN.name());
                changed = true;
            }
            if (!existing.isEmailVerified()) {
                existing.setEmailVerified(true);
                changed = true;
            }
            if (existing.isBlocked()) {
                existing.setBlocked(false);
                existing.setBlockedAt(null);
                existing.setBlockReason(null);
                existing.setBlockedByAdminId(null);
                changed = true;
            }
            if (changed) {
                userRepository.save(existing);
                log.info("Bootstrap: zaktualizowano istniejące konto {} do roli SUPER_ADMIN", email);
            }
        }, () -> {
            User u = new User();
            u.setEmail(email);
            u.setPasswordHash(passwordEncoder.encode(password));
            u.setFirstName(firstName);
            u.setLastName(lastName);
            u.setRole(UserRole.SUPER_ADMIN.name());
            u.setEmailVerified(true);
            u.setMustChangePassword(false);
            userRepository.save(u);
            log.info("Bootstrap: utworzono domyślne konto SUPER_ADMIN {}", email);
        });
    }
}
