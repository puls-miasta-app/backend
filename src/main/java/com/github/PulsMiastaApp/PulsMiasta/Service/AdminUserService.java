package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.AdminUserResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CreateAdminRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.UpdateAdminRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.UserAdminView;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.*;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.UserRole;
import com.github.PulsMiastaApp.PulsMiasta.Repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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

    @Transactional
    public User createAdmin(Long creatorId, CreateAdminRequest req) {
        User creator = userRepository.findByIdWithGeo(creatorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Creator not found"));

        UserRole creatorRole = parseRole(creator.getRole());
        UserRole targetRole = parseRole(req.role());

        if (!creatorRole.canAssignRole(targetRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Rola " + creatorRole.name() + " nie może tworzyć adminów z rolą " + targetRole.name());
        }

        GeoSets geo = resolveAndValidateGeo(targetRole, req.managedWojewodztwoIds(),
                req.managedPowiatIds(), req.managedGminaIds(), req.managedMiastoIds());

        validateCreatorScope(creatorRole, creator, targetRole, geo);

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

        applyScope(newAdmin, targetRole, geo, creatorRole, creator);

        return userRepository.save(newAdmin);
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listAdminsInScope(Long callerId) {
        User caller = userRepository.findByIdWithGeo(callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Caller not found"));
        UserRole callerRole = parseRole(caller.getRole());

        List<User> users = switch (callerRole) {
            case SUPER_ADMIN -> userRepository.findAllAdmins(ALL_ADMIN_ROLES);
            case ADMIN_WOJEWODZTWA -> {
                List<Long> wojIds = caller.getManagedWojewodztwa().stream().map(Wojewodztwo::getId).toList();
                yield merge(
                        userRepository.findAdminsByWojewodztwaIds(ALL_ADMIN_ROLES, wojIds),
                        userRepository.findCityAdminsByWojewodztwaIds(ALL_ADMIN_ROLES, wojIds));
            }
            case ADMIN_POWIATU -> {
                List<Long> powIds = caller.getManagedPowiaty().stream().map(Powiat::getId).toList();
                yield merge(
                        userRepository.findAdminsByPowiatyIds(ALL_ADMIN_ROLES, powIds),
                        userRepository.findCityAdminsByPowiatyIds(ALL_ADMIN_ROLES, powIds));
            }
            case ADMIN_GMINY -> {
                List<Long> gmIds = caller.getManagedGminy().stream().map(Gmina::getId).toList();
                yield merge(
                        userRepository.findAdminsByGminyIds(ALL_ADMIN_ROLES, gmIds),
                        userRepository.findCityAdminsByGminyIds(ALL_ADMIN_ROLES, gmIds));
            }
            default -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak uprawnień");
        };
        // Batch-load geo collections in ONE query to avoid N+1 (1 query per managed area per user)
        List<Long> ids = users.stream().map(User::getId).toList();
        List<User> withGeo = ids.isEmpty() ? List.of() : userRepository.findByIdsWithGeo(ids);
        return withGeo.stream().map(AdminUserResponse::from).toList();
    }

    @Transactional
    public User updateAdmin(Long callerId, Long targetId, UpdateAdminRequest req) {
        User caller = userRepository.findByIdWithGeo(callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Caller not found"));
        User target = userRepository.findByIdWithGeo(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin nie znaleziony"));

        UserRole callerRole = parseRole(caller.getRole());
        UserRole targetRole = parseRole(req.role());

        if (!callerRole.canAssignRole(targetRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Rola " + callerRole.name() + " nie może nadać roli " + targetRole.name());
        }

        GeoSets geo = resolveAndValidateGeo(targetRole, req.managedWojewodztwoIds(),
                req.managedPowiatIds(), req.managedGminaIds(), req.managedMiastoIds());

        validateCreatorScope(callerRole, caller, targetRole, geo);
        applyScope(target, targetRole, geo, callerRole, caller);

        return userRepository.save(target);
    }

    @Transactional
    public void revokeAdmin(Long callerId, Long targetId) {
        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Caller not found"));
        User target = userRepository.findByIdWithGeo(targetId)
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
        target.getManagedWojewodztwa().clear();
        target.getManagedPowiaty().clear();
        target.getManagedGminy().clear();
        target.getManagedMiasta().clear();
        userRepository.save(target);
    }

    // ---------- geo resolution ----------

    private record GeoSets(
            Set<Wojewodztwo> wojew,
            Set<Powiat> pow,
            Set<Gmina> gm,
            Set<Miejscowosc> miej
    ) {}

    private GeoSets resolveAndValidateGeo(UserRole targetRole,
                                           List<Long> wojIds, List<Long> powIds,
                                           List<Long> gmIds, List<Long> miejIds) {
        Set<Wojewodztwo> wojew = new HashSet<>();
        Set<Powiat> pow = new HashSet<>();
        Set<Gmina> gm = new HashSet<>();
        Set<Miejscowosc> miej = new HashSet<>();

        switch (targetRole) {
            case ADMIN_MIASTA -> {
                requireIds(miejIds, "managedMiastoIds");
                // Hierarchia (gmina → powiat → woj) jest pochodna miejscowości — nie wymaga jawnych ID
                Set<Long> distinctMiejIds = new HashSet<>(miejIds);
                List<Miejscowosc> withHierarchy = miejscowoscRepository.findAllByIdInWithHierarchy(distinctMiejIds);
                if (withHierarchy.size() != distinctMiejIds.size()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Miejscowość — podano nieistniejące ID");
                }
                miej = new HashSet<>(withHierarchy);
                for (var m : withHierarchy) {
                    gm.add(m.getGmina());
                    pow.add(m.getGmina().getPowiat());
                    wojew.add(m.getGmina().getPowiat().getWojewodztwo());
                }
            }
            case ADMIN_GMINY -> {
                requireIds(wojIds, "managedWojewodztwoIds");
                requireIds(powIds, "managedPowiatIds");
                requireIds(gmIds, "managedGminaIds");
                wojew = resolveAll(wojIds, wojRepository, "Województwo");
                pow   = resolveAll(powIds, powiatRepository, "Powiat");
                gm    = resolveAll(gmIds, gminaRepository, "Gmina");
            }
            case ADMIN_POWIATU -> {
                requireIds(wojIds, "managedWojewodztwoIds");
                requireIds(powIds, "managedPowiatIds");
                wojew = resolveAll(wojIds, wojRepository, "Województwo");
                pow   = resolveAll(powIds, powiatRepository, "Powiat");
            }
            case ADMIN_WOJEWODZTWA -> {
                requireIds(wojIds, "managedWojewodztwoIds");
                wojew = resolveAll(wojIds, wojRepository, "Województwo");
            }
            default -> { /* SUPER_ADMIN — brak pól */ }
        }

        return new GeoSets(wojew, pow, gm, miej);
    }

    private <T> Set<T> resolveAll(List<Long> ids, JpaRepository<T, Long> repo, String label) {
        Set<Long> distinct = new HashSet<>(ids);
        List<T> found = repo.findAllById(distinct);
        if (found.size() != distinct.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + " — podano nieistniejące ID");
        }
        return new HashSet<>(found);
    }

    // ---------- scope enforcement ----------

    /**
     * Aplikuje zasięg do usera. Dla ról niższych niż caller dziedziny wyższe
     * są wymuszane z konta creatora (creator nie może rozszerzyć swojego własnego zasięgu).
     */
    private void applyScope(User user, UserRole targetRole, GeoSets geo,
                             UserRole creatorRole, User creator) {
        user.setRole(targetRole.name());
        user.getManagedWojewodztwa().clear();
        user.getManagedPowiaty().clear();
        user.getManagedGminy().clear();
        user.getManagedMiasta().clear();

        switch (targetRole) {
            case ADMIN_MIASTA -> user.getManagedMiasta().addAll(geo.miej());
            case ADMIN_GMINY -> {
                user.getManagedWojewodztwa().addAll(inheritWojew(creatorRole, creator, geo));
                user.getManagedPowiaty().addAll(inheritPow(creatorRole, creator, geo));
                user.getManagedGminy().addAll(geo.gm());
            }
            case ADMIN_POWIATU -> {
                user.getManagedWojewodztwa().addAll(inheritWojew(creatorRole, creator, geo));
                user.getManagedPowiaty().addAll(geo.pow());
            }
            case ADMIN_WOJEWODZTWA -> user.getManagedWojewodztwa().addAll(geo.wojew());
            default -> { /* SUPER_ADMIN — brak zasięgu */ }
        }
    }

    private Set<Wojewodztwo> inheritWojew(UserRole cRole, User creator, GeoSets geo) {
        return cRole == UserRole.SUPER_ADMIN ? geo.wojew() : creator.getManagedWojewodztwa();
    }

    private Set<Powiat> inheritPow(UserRole cRole, User creator, GeoSets geo) {
        return (cRole == UserRole.SUPER_ADMIN || cRole == UserRole.ADMIN_WOJEWODZTWA)
                ? geo.pow() : creator.getManagedPowiaty();
    }

    private Set<Gmina> inheritGm(UserRole cRole, User creator, GeoSets geo) {
        return (cRole == UserRole.SUPER_ADMIN || cRole == UserRole.ADMIN_WOJEWODZTWA
                || cRole == UserRole.ADMIN_POWIATU)
                ? geo.gm() : creator.getManagedGminy();
    }

    // ---------- scope validation ----------

    /**
     * Nowy admin nie może dostać zasięgu wykraczającego poza zasięg creatora.
     * SUPER_ADMIN nie ma ograniczeń.
     */
    private void validateCreatorScope(UserRole creatorRole, User creator,
                                       UserRole targetRole, GeoSets geo) {
        if (creatorRole == UserRole.SUPER_ADMIN) return;

        if (creatorRole.ordinal() >= UserRole.ADMIN_WOJEWODZTWA.ordinal()) {
            assertSubsetIds(
                    creator.getManagedWojewodztwa().stream().map(Wojewodztwo::getId).collect(Collectors.toSet()),
                    geo.wojew().stream().map(Wojewodztwo::getId).collect(Collectors.toSet()),
                    "managedWojewodztwoIds");
        }
        if (creatorRole.ordinal() >= UserRole.ADMIN_POWIATU.ordinal()
                && targetRole.ordinal() <= UserRole.ADMIN_POWIATU.ordinal()) {
            assertSubsetIds(
                    creator.getManagedPowiaty().stream().map(Powiat::getId).collect(Collectors.toSet()),
                    geo.pow().stream().map(Powiat::getId).collect(Collectors.toSet()),
                    "managedPowiatIds");
        }
        if (creatorRole == UserRole.ADMIN_GMINY) {
            assertSubsetIds(
                    creator.getManagedGminy().stream().map(Gmina::getId).collect(Collectors.toSet()),
                    geo.gm().stream().map(Gmina::getId).collect(Collectors.toSet()),
                    "managedGminaIds");
        }
    }

    private static void assertSubsetIds(Set<Long> creatorScope, Set<Long> requestedScope, String field) {
        for (Long id : requestedScope) {
            if (!creatorScope.contains(id)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "ID " + id + " w polu " + field + " wykracza poza Twój zasięg");
            }
        }
    }

    // ---------- user management (block / unblock / list / get) ----------

    /**
     * Blokuje konto użytkownika.
     * SUPER_ADMIN może zablokować każdego (oprócz siebie i innych SUPER_ADMINów).
     * Pozostałe role adminów mogą blokować tylko zwykłych użytkowników (USER).
     */
    @Transactional
    public UserAdminView blockUser(Long adminId, Long targetId, String reason) {
        if (adminId.equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie możesz zablokować własnego konta");
        }
        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin nie znaleziony"));
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Użytkownik nie znaleziony"));

        UserRole adminRole = parseRole(admin.getRole());
        UserRole targetRole = parseRole(target.getRole());

        if (targetRole == UserRole.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nie można zablokować konta SUPER_ADMIN");
        }
        if (adminRole != UserRole.SUPER_ADMIN && targetRole != UserRole.USER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tylko SUPER_ADMIN może blokować konta adminów");
        }

        target.setBlocked(true);
        target.setBlockedAt(LocalDateTime.now());
        target.setBlockReason(reason != null ? reason.trim() : null);
        target.setBlockedByAdminId(adminId);
        return UserAdminView.from(userRepository.save(target));
    }

    /**
     * Odblokowuje konto użytkownika.
     * Takie same reguły hierarchii jak w blockUser.
     */
    @Transactional
    public UserAdminView unblockUser(Long adminId, Long targetId) {
        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin nie znaleziony"));
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Użytkownik nie znaleziony"));

        UserRole adminRole = parseRole(admin.getRole());
        UserRole targetRole = parseRole(target.getRole());

        if (adminRole != UserRole.SUPER_ADMIN && targetRole != UserRole.USER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tylko SUPER_ADMIN może odblokowywać konta adminów");
        }
        if (!target.isBlocked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Konto nie jest zablokowane");
        }

        target.setBlocked(false);
        target.setBlockedAt(null);
        target.setBlockReason(null);
        target.setBlockedByAdminId(null);
        return UserAdminView.from(userRepository.save(target));
    }

    /** Paginowana lista wszystkich użytkowników z opcjonalnym filtrem po emailu i statusie blokady. */
    @Transactional(readOnly = true)
    public Page<UserAdminView> listUsers(String emailFilter, Boolean blocked, int page, int size) {
        String filter = (emailFilter == null || emailFilter.isBlank()) ? null : emailFilter.trim();
        return userRepository.findAllWithFilters(filter, blocked, PageRequest.of(page, size))
                .map(UserAdminView::from);
    }

    /** Pobiera szczegóły pojedynczego użytkownika. */
    @Transactional(readOnly = true)
    public UserAdminView getUserDetails(Long targetId) {
        User user = userRepository.findById(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Użytkownik nie znaleziony"));
        return UserAdminView.from(user);
    }

    // ---------- helpers ----------

    private static UserRole parseRole(String roleName) {
        try {
            return UserRole.valueOf(roleName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieznana rola: " + roleName);
        }
    }

    private static List<User> merge(List<User> a, List<User> b) {
        LinkedHashMap<Long, User> map = new LinkedHashMap<>();
        a.forEach(u -> map.put(u.getId(), u));
        b.forEach(u -> map.putIfAbsent(u.getId(), u));
        return new ArrayList<>(map.values());
    }

    private static void requireIds(List<Long> ids, String fieldName) {
        if (ids == null || ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pole " + fieldName + " musi zawierać co najmniej jeden element dla tej roli");
        }
    }
}
