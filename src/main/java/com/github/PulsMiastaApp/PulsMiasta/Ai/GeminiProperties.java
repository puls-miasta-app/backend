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

    /**
     * Minimum confidence (0..1) required to accept Gemini's analysis. Below this
     * threshold the report is saved WITHOUT AI-assigned category/priority/description
     * (the admin reviews it manually), and dedup is skipped. Default 0.3.
     */
    private double minConfidence = 0.3;
}
