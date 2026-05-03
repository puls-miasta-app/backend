package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ErrorResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.MeResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.UserProfileResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserProfileService userProfileService;

    @GetMapping("/me")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MeSuccessResponse.class))),
            @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SuccessResponse<MeResponse>> me(
            @AuthenticationPrincipal AuthPrincipal principal) {

        return ResponseEntity.ok(SuccessResponse.of(MeResponse.from(principal)));
    }

    @GetMapping("/me/profile")
    public ResponseEntity<SuccessResponse<UserProfileResponse>> myProfile(
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return ResponseEntity.ok(SuccessResponse.of(userProfileService.getForUser(principal.id())));
    }

    @GetMapping("/{id}/profile")
    public ResponseEntity<SuccessResponse<UserProfileResponse>> profile(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        boolean ownProfile = id.equals(principal.id());
        return ResponseEntity.ok(SuccessResponse.of(
                userProfileService.getForUser(id, ownProfile)));
    }

    @Schema(name = "MeSuccessResponse")
    private static class MeSuccessResponse extends SuccessResponse<MeResponse> {
        public MeSuccessResponse() {
            super(true, null);
        }
    }
}
