package com.github.PulsMiastaApp.PulsMiasta.Config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "PulsMiasta API",
                version = "1.0",
                description = """
                        REST API aplikacji PulsMiasta — platformy do zgłaszania problemów miejskich przez mieszkańców.

                        **Uwierzytelnianie:** Wszystkie chronione endpointy wymagają ważnej sesji przekazywanej
                        przez HttpOnly cookie `SESSION` lub JWT cookie `auth_token` (ustawiany automatycznie po logowaniu).
                        Swagger UI obsługuje uwierzytelnianie cookie — zaloguj się przez `/v1/auth/login`,
                        a cookie zostanie dołączone automatycznie do kolejnych żądań.
                        """,
                contact = @Contact(name = "PulsMiasta Team", email = "dawsto00@gmail.com")
        ),
        servers = @Server(url = "http://localhost:8088", description = "Lokalny serwer deweloperski")
)
@SecurityScheme(
        name = "cookieAuth",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "auth_token",
        description = "JWT token uwierzytelniający przekazywany jako HttpOnly cookie (ustawiany automatycznie po /v1/auth/login)"
)
public class OpenApiConfig {
}
