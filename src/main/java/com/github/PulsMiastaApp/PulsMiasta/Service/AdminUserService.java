package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CreateAdminRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.UpdateAdminRequest;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.*;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.UserRole;
import com.github.PulsMiastaApp.PulsMiasta.Repository.*;
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
    private final WojewodztwoRepository wojRepository;
    private final PowiatRepository powiatRepository;
    private final GminaRepository gminaRepository;
    private final MiejscowoscRepository miejscowoscRepository;

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

        GeoRefs refs = resolveGeoRefs(req.managedWojewodztwoId(), req.managedPowiatId(),
                req.managedGminaId(), req.managedMiastoId(),
                req.managedWojewodztwo(), req.managedPowiat(), req.managedGmina(), req.managedMiasto());

        CreateAdminRequest normalized = normalizeReqStrings(req, refs);
        validateScopeFields(targetRole, normalized);
        validateCreatorScope(creatorRole, creator, targetRole, normalized);

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
        newAdmin.setMustChangePassword(true);

        applyGeoScope(newAdmin, targetRole, refs, normalized, creatorRole, creator);

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
     * Zmienia rolę i/lub obszar zarządzania istniejącego admina.
     */
    @Transactional
    public User updateAdmin(Long callerId, Long targetId, UpdateAdminRequest req) {
        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Caller not found"));
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin nie znaleziony"));

        UserRole callerRole = parseRole(caller.getRole());
        UserRole targetRole = parseRole(req.role());

        if (!callerRole.canAssignRole(targetRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Rola " + callerRole.name() + " nie może nadać roli " + targetRole.name());
        }

        GeoRefs refs = resolveGeoRefs(req.managedWojewodztwoId(), req.managedPowiatId(),
                req.managedGminaId(), req.managedMiastoId(),
                req.managedWojewodztwo(), req.managedPowiat(), req.managedGmina(), req.managedMiasto());

        CreateAdminRequest asCreate = new CreateAdminRequest(
                target.getEmail(), target.getFirstName(), target.getLastName(), "",
                req.role(),
                req.managedWojewodztwo(), req.managedPowiat(), req.managedGmina(), req.managedMiasto(),
                req.managedWojewodztwoId(), req.managedPowiatId(), req.managedGminaId(), req.managedMiastoId());

        CreateAdminRequest normalized = normalizeReqStrings(asCreate, refs);
        validateScopeFields(targetRole, normalized);
        validateCreatorScope(callerRole, caller, targetRole, normalized);

        applyGeoScope(target, targetRole, refs, normalized, callerRole, caller);

        return userRepository.save(target);
    }

    /**
     * Odbiera prawa admina — przywraca rolę USER i zeruje pola obszaru.
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
        target.setManagedWojewodztwoRef(null);
        target.setManagedPowiatRef(null);
        target.setManagedGminaRef(null);
        target.setManagedMiastoRef(null);
        userRepository.save(target);
    }

    // ---------- geo resolution ----------

    private record GeoRefs(Wojewodztwo woj, Powiat pow, Gmina gm, Miejscowosc miej) {}

    /**
     * Gdy podano ID — pobiera encję i weryfikuje, że istnieje.
     * Gdy ID null — zostawia null (string-based fallback zostanie użyty).
     */
    private GeoRefs resolveGeoRefs(Long wojId, Long powId, Long gmId, Long miejId,
                                    String wojStr, String powStr, String gmStr, String mejStr) {
        Wojewodztwo woj = wojId != null
                ? wojRepository.findById(wojId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Województwo o id=" + wojId + " nie istnieje"))
                : null;
        Powiat pow = powId != null
                ? powiatRepository.findById(powId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Powiat o id=" + powId + " nie istnieje"))
                : null;
        Gmina gm = gmId != null
                ? gminaRepository.findById(gmId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Gmina o id=" + gmId + " nie istnieje"))
                : null;
        Miejscowosc miej = miejId != null
                ? miejscowoscRepository.findById(miejId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Miejscowość o id=" + miejId + " nie istnieje"))
                : null;
        return new GeoRefs(woj, pow, gm, miej);
    }

    /**
     * Buduje zdenormalizowane stringi z FK encji (jeśli dostępne), lub normalizuje wejściowe stringi.
     */
    private CreateAdminRequest normalizeReqStrings(CreateAdminRequest req, GeoRefs refs) {
        String woj = refs.woj() != null ? refs.woj().getName()
                : GeoNormalizer.normalizeWojewodztwo(req.managedWojewodztwo());
        String pow = refs.pow() != null ? refs.pow().getName()
                : GeoNormalizer.normalizePowiat(req.managedPowiat());
        String gm = refs.gm() != null ? refs.gm().getName()
                : GeoNormalizer.normalizeGmina(req.managedGmina());
        String mej = refs.miej() != null ? refs.miej().getName() : req.managedMiasto();
        return new CreateAdminRequest(
                req.email(), req.firstName(), req.lastName(), req.password(), req.role(),
                woj, pow, gm, mej,
                req.managedWojewodztwoId(), req.managedPowiatId(), req.managedGminaId(), req.managedMiastoId());
    }

    /** Ustawia pola scopu na userze (string + FK). Respektuje dziedziczenie od creatora. */
    private void applyGeoScope(User user, UserRole targetRole, GeoRefs refs,
                                CreateAdminRequest normalized, UserRole creatorRole, User creator) {
        String wojStr = resolveWojewodztwoStr(creatorRole, creator, normalized);
        String powStr = resolvePowiatStr(creatorRole, creator, targetRole, normalized);
        String gmStr  = resolveGminaStr(creatorRole, creator, targetRole, normalized);

        user.setRole(targetRole.name());
        user.setManagedWojewodztwo(wojStr);
        user.setManagedPowiat(powStr);
        user.setManagedGmina(gmStr);
        user.setManagedMiasto(targetRole == UserRole.ADMIN_MIASTA ? normalized.managedMiasto() : null);

        // FK refs — dziedziczone od creatora gdy creator niższego szczebla
        user.setManagedWojewodztwoRef(resolveWojRef(creatorRole, creator, refs));
        user.setManagedPowiatRef(resolvePowRef(creatorRole, creator, targetRole, refs));
        user.setManagedGminaRef(resolveGmRef(creatorRole, creator, targetRole, refs));
        user.setManagedMiastoRef(targetRole == UserRole.ADMIN_MIASTA ? refs.miej() : null);
    }

    // ---------- scope string resolution ----------

    private String resolveWojewodztwoStr(UserRole creatorRole, User creator, CreateAdminRequest req) {
        if (creatorRole == UserRole.SUPER_ADMIN) return req.managedWojewodztwo();
        return creator.getManagedWojewodztwo();
    }

    private String resolvePowiatStr(UserRole creatorRole, User creator, UserRole targetRole, CreateAdminRequest req) {
        if (targetRole == UserRole.ADMIN_WOJEWODZTWA) return null;
        if (creatorRole == UserRole.SUPER_ADMIN || creatorRole == UserRole.ADMIN_WOJEWODZTWA) {
            return req.managedPowiat();
        }
        return creator.getManagedPowiat();
    }

    private String resolveGminaStr(UserRole creatorRole, User creator, UserRole targetRole, CreateAdminRequest req) {
        if (targetRole == UserRole.ADMIN_WOJEWODZTWA || targetRole == UserRole.ADMIN_POWIATU) return null;
        if (creatorRole == UserRole.SUPER_ADMIN || creatorRole == UserRole.ADMIN_WOJEWODZTWA
                || creatorRole == UserRole.ADMIN_POWIATU) {
            return req.managedGmina();
        }
        return creator.getManagedGmina();
    }

    // ---------- scope FK ref resolution ----------

    private Wojewodztwo resolveWojRef(UserRole creatorRole, User creator, GeoRefs refs) {
        if (creatorRole == UserRole.SUPER_ADMIN) return refs.woj();
        return creator.getManagedWojewodztwoRef();
    }

    private Powiat resolvePowRef(UserRole creatorRole, User creator, UserRole targetRole, GeoRefs refs) {
        if (targetRole == UserRole.ADMIN_WOJEWODZTWA) return null;
        if (creatorRole == UserRole.SUPER_ADMIN || creatorRole == UserRole.ADMIN_WOJEWODZTWA) {
            return refs.pow();
        }
        return creator.getManagedPowiatRef();
    }

    private Gmina resolveGmRef(UserRole creatorRole, User creator, UserRole targetRole, GeoRefs refs) {
        if (targetRole == UserRole.ADMIN_WOJEWODZTWA || targetRole == UserRole.ADMIN_POWIATU) return null;
        if (creatorRole == UserRole.SUPER_ADMIN || creatorRole == UserRole.ADMIN_WOJEWODZTWA
                || creatorRole == UserRole.ADMIN_POWIATU) {
            return refs.gm();
        }
        return creator.getManagedGminaRef();
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
            default -> { /* SUPER_ADMIN — brak pól scope */ }
        }
    }

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
        String a = creatorValue == null ? null : creatorValue.trim().toLowerCase(java.util.Locale.ROOT);
        String b = requestValue == null  ? null : requestValue.trim().toLowerCase(java.util.Locale.ROOT);
        if (a == null || !a.equals(b)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Pole " + fieldName + " musi zgadzać się z obszarem zarządzanym przez Twoje konto");
        }
    }
}
