package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.WsTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "WebSocket", description = "Uwierzytelnianie połączeń WebSocket przez jednorazowy token.")
@SecurityRequirement(name = "cookieAuth")
public class WsTokenController {

    private final WsTokenService wsTokenService;

    @Operation(
            summary = "Wydaj jednorazowy token WebSocket",
            description = """
                    Generuje krótkotrwały token (TTL 30 s) do uwierzytelnienia połączenia WebSocket.
                    Token jest jednorazowy — zostaje skonsumowany przy pierwszym połączeniu.

                    **Flow:**
                    1. Wywołaj ten endpoint (wymaga aktywnej sesji cookie).
                    2. Użyj zwróconego tokenu jako parametru `?token=` przy otwieraniu WebSocket:
                       `wss://pulsmiasta.online/api/ws/chat?token={token}`

                    Wymagane ponieważ przeglądarki nie pozwalają dołączać nagłówków ani cookies
                    do żądania `new WebSocket(url)` w kontekście cross-site.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token wydany",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "token": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia")
    })
    @PostMapping("/v1/ws-token")
    public ResponseEntity<Map<String, String>> issue(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String token = wsTokenService.createToken(principal.id());
        return ResponseEntity.ok(Map.of("token", token));
    }
}
