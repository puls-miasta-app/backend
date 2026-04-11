package com.github.PulsMiastaApp.PulsMiasta.Ai;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportPriority;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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

    @PostConstruct
    void init() {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
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

        try {
            Map<String, Object> body = buildRequestBody(imageBytes, contentType);

            Map<String, Object> response = restClient.post()
                    .uri("/models/{model}:generateContent?key={key}",
                            properties.getModel(), properties.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(MAP_TYPE);

            return parseResponse(response);
        } catch (RestClientException e) {
            log.error("Gemini API call failed: {}", e.getMessage());
            return handleFailure("Gemini API call failed", e);
        } catch (ImageAnalysisException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error while analysing image with Gemini", e);
            return handleFailure("Unexpected error during image analysis", e);
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

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("category", Map.of("type", "STRING", "enum", categories));
        properties.put("priority", Map.of("type", "STRING", "enum", priorities));
        properties.put("description", Map.of("type", "STRING"));
        properties.put("confidence", Map.of("type", "NUMBER"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", properties);
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
