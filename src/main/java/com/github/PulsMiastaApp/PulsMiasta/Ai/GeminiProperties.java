package com.github.PulsMiastaApp.PulsMiasta.Ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.gemini")
public class GeminiProperties {

    /** Google AI Studio / Generative Language API key. */
    private String apiKey;

    /** Gemini model id, e.g. gemini-2.5-flash. */
    private String model = "gemini-2.5-flash";

    /** Base URL for the Generative Language API. */
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

    /** Request timeout in seconds. */
    private int timeoutSeconds = 30;

    /** If true, report creation fails when Gemini cannot be reached. If false, the report is saved without AI fields. */
    private boolean failOnError = false;
}
