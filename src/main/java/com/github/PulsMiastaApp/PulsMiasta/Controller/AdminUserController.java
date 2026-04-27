package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.AdminUserResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CreateAdminRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Zarządzanie adminami. Dostępne tylko dla zalogowanych adminów (/v1/admin/**).
 *
 * <ul>
 *   <li>POST   /v1/admin/users       — dodaj nowego admina (hierarchia: kto może kogo)</li>
 *   <li>GET    /v1/admin/users       — lista adminów w zasięgu callera</li>
 *   <li>DELETE /v1/admin/users/{id}  — odbierz rolę admina (przywróć do USER)</li>
 * </ul>
 *
 * Hierarchia ról (od najniższej):
 * ADMIN_MIASTA &lt; ADMIN_GMINY &lt; ADMIN_POWIATU &lt; ADMIN_WOJEWODZTWA &lt; SUPER_ADMIN
 *
 * Każdy admin może tworzyć adminów NIŻSZEGO szczebla w swoim obszarze geograficznym.
 * Admin województwa może też tworzyć adminów gminy czy miasta (nie tylko bezpośrednio niższy szczebel).
 */
@RestController
@RequestMapping("/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<AdminUserResponse>> createAdmin(
            @Valid @RequestBody CreateAdminRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        User created = adminUserService.createAdmin(principal.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(AdminUserResponse.from(created)));
    }

    @GetMapping
    public ResponseEntity<SuccessResponse<Map<String, List<AdminUserResponse>>>> listAdmins(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        List<AdminUserResponse> admins = adminUserService.listAdminsInScope(principal.id())
                .stream()
                .map(AdminUserResponse::from)
                .toList();
        return ResponseEntity.ok(SuccessResponse.of(Map.of("admins", admins)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<SuccessResponse<Void>> revokeAdmin(
            @PathVariable("id") Long targetId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        adminUserService.revokeAdmin(principal.id(), targetId);
        return ResponseEntity.ok(SuccessResponse.of(null));
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }
}
