package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CreateAdminRequest;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.UserRole;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final List<String> ALL_ADMIN_ROLES = Arrays.stream(UserRole.values())
            .filter(UserRole::isAdmin)
            .map(Enum::name)
            .toList();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Tworzy nowego admina. Caller musi być adminem z uprawnieniami do nadawania
     * żądanej roli, a obszar zarządzania nowego admina musi mieścić się w obszarze callera.
     */
    @Transactional
    public User createAdmin(Long creatorId, CreateAdminRequest req) {
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Creator not found"));

        UserRole creatorRole = parseRole(creator.getRole());
        UserRole targetRole = parseRole(req.role());

        if (!creatorRole.canAssignRole(targetRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Rola " + creatorRole.name() + " nie może tworzyć adminów z rolą " + targetRole.name());
        }

        validateScopeFields(targetRole, req);
        validateCreatorScope(creatorRole, creator, targetRole, req);

        if (userRepository.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email jest już używany");
        }

        User newAdmin = new User();
        newAdmin.setEmail(req.email());
        newAdmin.setFirstName(req.firstName());
        newAdmin.setLastName(req.lastName());
        newAdmin.setPasswordHash(passwordEncoder.encode(req.password()));
        newAdmin.setRole(targetRole.name());
        newAdmin.setEmailVerified(true);
        newAdmin.setEmailOtpEnabled(true);

        newAdmin.setManagedWojewodztwo(resolveWojewodztwo(creatorRole, creator, req));
        newAdmin.setManagedPowiat(resolvePowiat(creatorRole, creator, targetRole, req));
        newAdmin.setManagedGmina(resolveGmina(creatorRole, creator, targetRole, req));
        newAdmin.setManagedMiasto(targetRole == UserRole.ADMIN_MIASTA ? req.managedMiasto() : null);

        return userRepository.save(newAdmin);
    }

    /**
     * Zwraca listę adminów widocznych dla callera zgodnie z jego zasięgiem.
     */
    @Transactional(readOnly = true)
    public List<User> listAdminsInScope(Long callerId) {
        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Caller not found"));
        UserRole callerRole = parseRole(caller.getRole());

        return switch (callerRole) {
            case SUPER_ADMIN -> userRepository.findAllAdmins(ALL_ADMIN_ROLES);
            case ADMIN_WOJEWODZTWA -> userRepository.findAdminsByWojewodztwo(
                    ALL_ADMIN_ROLES, caller.getManagedWojewodztwo());
            case ADMIN_POWIATU -> userRepository.findAdminsByPowiat(
                    ALL_ADMIN_ROLES, caller.getManagedWojewodztwo(), caller.getManagedPowiat());
            case ADMIN_GMINY -> userRepository.findAdminsByGmina(
                    ALL_ADMIN_ROLES, caller.getManagedWojewodztwo(), caller.getManagedPowiat(), caller.getManagedGmina());
            default -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak uprawnień");
        };
    }

    /**
     * Odbiera prawa admina — przywraca rolę USER i zeruje pola zarządczego obszaru.
     * Caller musi mieć wyższy poziom hierarchii niż target.
     */
    @Transactional
    public void revokeAdmin(Long callerId, Long targetId) {
        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Caller not found"));
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin nie znaleziony"));

        UserRole callerRole = parseRole(caller.getRole());
        UserRole targetRole = parseRole(target.getRole());

        if (targetRole == UserRole.USER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Użytkownik już nie jest adminem");
        }
        if (!callerRole.canAssignRole(targetRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Brak uprawnień do odebrania roli " + targetRole.name());
        }

        target.setRole(UserRole.USER.name());
        target.setManagedWojewodztwo(null);
        target.setManagedPowiat(null);
        target.setManagedGmina(null);
        target.setManagedMiasto(null);
        userRepository.save(target);
    }

    // ---------- walidacja ----------

    private void validateScopeFields(UserRole targetRole, CreateAdminRequest req) {
        switch (targetRole) {
            case ADMIN_WOJEWODZTWA -> requireField(req.managedWojewodztwo(), "managedWojewodztwo");
            case ADMIN_POWIATU -> {
                requireField(req.managedWojewodztwo(), "managedWojewodztwo");
                requireField(req.managedPowiat(), "managedPowiat");
            }
            case ADMIN_GMINY -> {
                requireField(req.managedWojewodztwo(), "managedWojewodztwo");
                requireField(req.managedPowiat(), "managedPowiat");
                requireField(req.managedGmina(), "managedGmina");
            }
            case ADMIN_MIASTA -> {
                requireField(req.managedWojewodztwo(), "managedWojewodztwo");
                requireField(req.managedPowiat(), "managedPowiat");
                requireField(req.managedGmina(), "managedGmina");
                requireField(req.managedMiasto(), "managedMiasto");
            }
            default -> { /* SUPER_ADMIN — brak pól scope; tej roli nie można przypisać */ }
        }
    }

    /**
     * Sprawdza, czy obszar zarządzania nowego admina mieści się w obszarze callera.
     * SUPER_ADMIN nie ma ograniczeń.
     */
    private void validateCreatorScope(UserRole creatorRole, User creator,
                                      UserRole targetRole, CreateAdminRequest req) {
        if (creatorRole == UserRole.SUPER_ADMIN) return;

        if (creatorRole.ordinal() >= UserRole.ADMIN_WOJEWODZTWA.ordinal()) {
            assertScopeMatch(creator.getManagedWojewodztwo(), req.managedWojewodztwo(), "managedWojewodztwo");
        }
        if (creatorRole.ordinal() >= UserRole.ADMIN_POWIATU.ordinal()) {
            if (targetRole.ordinal() < UserRole.ADMIN_POWIATU.ordinal()
                    || targetRole == UserRole.ADMIN_POWIATU) {
                assertScopeMatch(creator.getManagedPowiat(), req.managedPowiat(), "managedPowiat");
            }
        }
        if (creatorRole == UserRole.ADMIN_GMINY) {
            assertScopeMatch(creator.getManagedGmina(), req.managedGmina(), "managedGmina");
        }
    }

    // ---------- scope resolution — dziedziczenie scope'u od creatora ----------

    private String resolveWojewodztwo(UserRole creatorRole, User creator, CreateAdminRequest req) {
        if (creatorRole == UserRole.SUPER_ADMIN) return req.managedWojewodztwo();
        return creator.getManagedWojewodztwo();
    }

    private String resolvePowiat(UserRole creatorRole, User creator, UserRole targetRole, CreateAdminRequest req) {
        if (targetRole == UserRole.ADMIN_WOJEWODZTWA) return null;
        if (creatorRole == UserRole.SUPER_ADMIN || creatorRole == UserRole.ADMIN_WOJEWODZTWA) {
            return req.managedPowiat();
        }
        return creator.getManagedPowiat();
    }

    private String resolveGmina(UserRole creatorRole, User creator, UserRole targetRole, CreateAdminRequest req) {
        if (targetRole == UserRole.ADMIN_WOJEWODZTWA || targetRole == UserRole.ADMIN_POWIATU) return null;
        if (creatorRole == UserRole.SUPER_ADMIN || creatorRole == UserRole.ADMIN_WOJEWODZTWA
                || creatorRole == UserRole.ADMIN_POWIATU) {
            return req.managedGmina();
        }
        return creator.getManagedGmina();
    }

    // ---------- helpers ----------

    private static UserRole parseRole(String roleName) {
        try {
            return UserRole.valueOf(roleName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieznana rola: " + roleName);
        }
    }

    private static void requireField(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pole " + fieldName + " jest wymagane dla tej roli");
        }
    }

    private static void assertScopeMatch(String creatorValue, String requestValue, String fieldName) {
        if (creatorValue == null || !creatorValue.equalsIgnoreCase(requestValue)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Pole " + fieldName + " musi zgadzać się z obszarem zarządzanym przez Twoje konto");
        }
    }
}
