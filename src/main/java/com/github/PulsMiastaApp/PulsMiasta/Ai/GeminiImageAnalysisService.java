package com.github.PulsMiastaApp.PulsMiasta.Ai;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportPriority;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls Google Gemini to analyse a photo of a city infrastructure issue and extract
 * a structured {@link AiAnalysisResult} (category, priority, description).
 *
 * Uses the Generative Language REST API with Structured Output (responseSchema) so the model
 * is constrained to return valid JSON matching our domain enums.
 *
 * Note: Spring Boot 4 ships Jackson 3 under the new {@code tools.jackson.*} package
 * (the legacy {@code com.fasterxml.jackson.*} classes still exist on classpath but are
 * not wired into Spring's message converters anymore).
 */
@Slf4j
@Service
public class GeminiImageAnalysisService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final String PROMPT = """
            Jesteś systemem analizującym zgłoszenia problemów miejskich w Polsce.
            Użytkownik przesłał zdjęcie z prośbą o zgłoszenie usterki infrastruktury miejskiej
            (np. dziura w drodze, uszkodzona latarnia, graffiti, dzikie wysypisko, uszkodzony znak, podtopienie).

            Na podstawie zdjęcia zwróć:
            - category: najlepiej pasująca kategoria z listy enumów
            - priority: priorytet (LOW, MEDIUM, HIGH, CRITICAL) oceniając zagrożenie dla zdrowia,
              bezpieczeństwa ruchu i skalę problemu
            - description: krótki, rzeczowy opis problemu po polsku (maks. 2 zdania, bez emoji)
            - confidence: liczba 0..1 określająca pewność, że zdjęcie faktycznie pokazuje
              problem infrastruktury miejskiej (0 = zdjęcie nie na temat, 1 = jednoznaczne zgłoszenie)

            Jeżeli zdjęcie nie przedstawia problemu infrastruktury miejskiej, ustaw category=OTHER,
            priority=LOW, confidence bliskie 0 i w description krótko wyjaśnij, czego brakuje.
            """;

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private RestClient restClient;

    public GeminiImageAnalysisService(GeminiProperties properties) {
        this.properties = properties;
    }

    /** Max attempts per call. One attempt = 1 try, so value 3 means 1 try + 2 retries. */
    private static final int MAX_ATTEMPTS = 3;

    /** Base backoff between retries; doubled on each subsequent attempt. */
    private static final Duration RETRY_BASE_BACKOFF = Duration.ofMillis(500);

    @PostConstruct
    void init() {
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds()));

        // JDK HttpClient controls the TCP connect timeout. Read timeout is handled
        // per-request by JdkClientHttpRequestFactory.setReadTimeout.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Analyse the given image. Returns null when analysis fails and the service is
     * configured to be non-blocking (fail-open). Throws {@link ImageAnalysisException}
     * when {@code ai.gemini.fail-on-error=true}.
     */
    public AiAnalysisResult analyse(byte[] imageBytes, String contentType) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.warn("Gemini API key not configured, skipping image analysis");
            return handleFailure("Gemini API key not configured", null);
        }

        Map<String, Object> body;
        try {
            body = buildRequestBody(imageBytes, contentType);
        } catch (Exception e) {
            log.error("Failed to build Gemini request body", e);
            return handleFailure("Failed to build Gemini request body", e);
        }

        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Map<String, Object> response = restClient.post()
                        .uri("/models/{model}:generateContent?key={key}",
                                properties.getModel(), properties.getApiKey())
                        .body(body)
                        .retrieve()
                        .body(MAP_TYPE);

                return parseResponse(response);
            } catch (HttpClientErrorException e) {
                // 4xx from Gemini. Only 429 (rate limit) is worth retrying; other 4xx
                // (bad request, unauthorized, forbidden) will just fail the same way.
                lastFailure = e;
                if (e.getStatusCode().value() != 429 || attempt == MAX_ATTEMPTS) {
                    log.error("Gemini API call failed with {}: {}", e.getStatusCode(), e.getMessage());
                    return handleFailure("Gemini API call failed: " + e.getStatusCode(), e);
                }
                log.warn("Gemini rate-limited (429), attempt {}/{}", attempt, MAX_ATTEMPTS);
                sleepBackoff(attempt);
            } catch (HttpServerErrorException e) {
                // 5xx — always retryable.
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Gemini API 5xx after {} attempts: {}", MAX_ATTEMPTS, e.getMessage());
                    return handleFailure("Gemini API unavailable", e);
                }
                log.warn("Gemini 5xx ({}), attempt {}/{}", e.getStatusCode(), attempt, MAX_ATTEMPTS);
                sleepBackoff(attempt);
            } catch (RestClientException e) {
                // Network errors, timeouts, DNS failures — retryable.
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Gemini API network failure after {} attempts: {}", MAX_ATTEMPTS, e.getMessage());
                    return handleFailure("Gemini API call failed", e);
                }
                log.warn("Gemini network error ({}), attempt {}/{}", e.getClass().getSimpleName(), attempt, MAX_ATTEMPTS);
                sleepBackoff(attempt);
            } catch (ImageAnalysisException e) {
                throw e;
            } catch (Exception e) {
                log.error("Unexpected error while analysing image with Gemini", e);
                return handleFailure("Unexpected error during image analysis", e);
            }
        }
        // Unreachable in practice — the loop either returns or throws.
        return handleFailure("Gemini API call failed", lastFailure);
    }

    private void sleepBackoff(int attempt) {
        long millis = RETRY_BASE_BACKOFF.toMillis() * (1L << (attempt - 1));
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private AiAnalysisResult handleFailure(String message, Throwable cause) {
        if (properties.isFailOnError()) {
            throw new ImageAnalysisException(message, cause);
        }
        return null;
    }

    private Map<String, Object> buildRequestBody(byte[] imageBytes, String contentType) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String mimeType = contentType != null ? contentType : "image/jpeg";

        Map<String, Object> inlineData = Map.of(
                "mime_type", mimeType,
                "data", base64
        );

        Map<String, Object> imagePart = Map.of("inline_data", inlineData);
        Map<String, Object> textPart = Map.of("text", PROMPT);

        Map<String, Object> content = Map.of("parts", List.of(imagePart, textPart));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", buildResponseSchema());
        generationConfig.put("temperature", 0.2);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(content));
        body.put("generationConfig", generationConfig);
        return body;
    }

    private Map<String, Object> buildResponseSchema() {
        List<String> categories = java.util.Arrays.stream(ReportCategory.values())
                .map(Enum::name).toList();
        List<String> priorities = java.util.Arrays.stream(ReportPriority.values())
                .map(Enum::name).toList();

        // Named "schemaFields" to avoid shadowing the service's GeminiProperties field.
        Map<String, Object> schemaFields = new LinkedHashMap<>();
        schemaFields.put("category", Map.of("type", "STRING", "enum", categories));
        schemaFields.put("priority", Map.of("type", "STRING", "enum", priorities));
        schemaFields.put("description", Map.of("type", "STRING"));
        schemaFields.put("confidence", Map.of("type", "NUMBER"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", schemaFields);
        schema.put("required", List.of("category", "priority", "description", "confidence"));
        return schema;
    }

    @SuppressWarnings("unchecked")
    private AiAnalysisResult parseResponse(Map<String, Object> response) {
        if (response == null) {
            throw new ImageAnalysisException("Empty response from Gemini");
        }

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new ImageAnalysisException("No candidates in Gemini response: " + response);
        }

        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null) {
            throw new ImageAnalysisException("No content in Gemini candidate");
        }

        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new ImageAnalysisException("No parts in Gemini candidate");
        }

        Object textObj = parts.get(0).get("text");
        if (!(textObj instanceof String json) || json.isBlank()) {
            throw new ImageAnalysisException("Missing text payload in Gemini response");
        }

        try {
            JsonNode parsed = objectMapper.readTree(json);
            ReportCategory category = parseEnum(parsed.path("category").asString(null), ReportCategory.class, ReportCategory.OTHER);
            ReportPriority priority = parseEnum(parsed.path("priority").asString(null), ReportPriority.class, ReportPriority.LOW);
            String description = parsed.path("description").asString("");
            double confidence = parsed.path("confidence").asDouble(0.0);

            return new AiAnalysisResult(category, priority, description, confidence);
        } catch (Exception e) {
            throw new ImageAnalysisException("Failed to parse Gemini JSON payload: " + json, e);
        }
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> type, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Gemini returned unknown {} value: {}, falling back to {}", type.getSimpleName(), value, fallback);
            return fallback;
        }
    }
}
