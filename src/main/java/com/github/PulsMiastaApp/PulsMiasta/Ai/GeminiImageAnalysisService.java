package com.github.PulsMiastaApp.PulsMiasta.Ai;

import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;
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
 * Wywołuje Google Gemini żeby przeanalizować zdjęcie zgłoszenia miejskiego i wypełnić
 * pola pulse'a (kategoria, priorytet, tytuł, opis, notatka AI, hint do zdjęcia, heat).
 *
 * <p>Używa Structured Output (responseSchema), żeby model zwracał JSON dopasowany do
 * naszych enumów po stronie backendu.
 */
@Slf4j
@Service
public class GeminiImageAnalysisService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final String PROMPT = """
            Jesteś systemem analizującym zgłoszenia problemów miejskich w Polsce dla aplikacji PulsMiasta.
            Użytkownik przesłał zdjęcie z prośbą o zgłoszenie usterki lub problemu w mieście.

            Aplikacja rozróżnia trzy kategorie zgłoszeń:
              - RUCH: problemy związane z ruchem i infrastrukturą drogową (dziury w jezdni,
                uszkodzone znaki drogowe, zniszczone przejścia dla pieszych, korki, wypadki).
              - BEZPIECZENSTWO: zagrożenia dla mieszkańców (uszkodzone latarnie, graffiti,
                wandalizm, niebezpieczne miejsca, dzikie wysypiska blokujące przejście).
              - ZIELEN: problemy związane z miejską zielenią i środowiskiem (wyłamane drzewa,
                zaniedbane parki, śmieci w parkach/na zieleńcach, podtopienia, dzikie wysypiska w lasach).

            Na podstawie zdjęcia zwróć JSON o polach:
            - category: najlepiej pasująca kategoria (RUCH, BEZPIECZENSTWO, ZIELEN)
            - priority: PILNE lub STANDARD; PILNE = natychmiastowe zagrożenie dla zdrowia/bezpieczeństwa
            - title: bardzo krótki tytuł (max 8 słów) po polsku, np. "Dziura w jezdni przy skrzyżowaniu"
            - description: krótki opis problemu po polsku (max 2 zdania, bez emoji)
            - aiNote: jednozdaniowa notatka serwisu AI skierowana do odbiorcy (np. "Utrudnienie dla kierowców")
            - imageHint: krótki opis zawartości zdjęcia (max 6 słów, np. "Dziura w asfalcie, krawężnik")
            - heat: jedno z: "Wysokie", "Średnie", "Niskie" — oszacowana skala zasięgu/ważności problemu
            - confidence: liczba 0..1 określająca pewność, że zdjęcie faktycznie pokazuje problem miejski

            Jeżeli zdjęcie nie przedstawia problemu miejskiego, ustaw category=RUCH (fallback),
            priority=STANDARD, confidence bliskie 0 i w description krótko wyjaśnij, czego brakuje.
            """;

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private RestClient restClient;

    public GeminiImageAnalysisService(GeminiProperties properties) {
        this.properties = properties;
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_BASE_BACKOFF = Duration.ofMillis(500);

    @PostConstruct
    void init() {
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds()));

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
     * Analyse the given image. Always returns {@code null} on failure — this service
     * runs asynchronously on a background thread after the pulse has already been
     * persisted and the {@code 201} response sent, so there's no user request left to
     * fail. Errors are logged and the caller leaves the AI fields blank.
     */
    public AiAnalysisResult analyse(byte[] imageBytes, String contentType) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.warn("Gemini API key not configured, skipping image analysis");
            return null;
        }

        Map<String, Object> body;
        try {
            body = buildRequestBody(imageBytes, contentType);
        } catch (Exception e) {
            log.error("Failed to build Gemini request body", e);
            return null;
        }

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
                if (e.getStatusCode().value() != 429 || attempt == MAX_ATTEMPTS) {
                    log.error("Gemini API call failed with {}: {}", e.getStatusCode(), e.getMessage());
                    return null;
                }
                log.warn("Gemini rate-limited (429), attempt {}/{}", attempt, MAX_ATTEMPTS);
                sleepBackoff(attempt);
            } catch (HttpServerErrorException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Gemini API 5xx after {} attempts: {}", MAX_ATTEMPTS, e.getMessage());
                    return null;
                }
                log.warn("Gemini 5xx ({}), attempt {}/{}", e.getStatusCode(), attempt, MAX_ATTEMPTS);
                sleepBackoff(attempt);
            } catch (RestClientException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("Gemini API network failure after {} attempts: {}", MAX_ATTEMPTS, e.getMessage());
                    return null;
                }
                log.warn("Gemini network error ({}), attempt {}/{}", e.getClass().getSimpleName(), attempt, MAX_ATTEMPTS);
                sleepBackoff(attempt);
            } catch (Exception e) {
                log.error("Unexpected error while analysing image with Gemini", e);
                return null;
            }
        }
        return null;
    }

    private void sleepBackoff(int attempt) {
        long millis = RETRY_BASE_BACKOFF.toMillis() * (1L << (attempt - 1));
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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
        List<String> categories = java.util.Arrays.stream(PulseCategory.values())
                .map(Enum::name).toList();
        List<String> priorities = java.util.Arrays.stream(PulsePriority.values())
                .map(Enum::name).toList();
        List<String> heatValues = List.of("Wysokie", "Średnie", "Niskie");

        Map<String, Object> schemaFields = new LinkedHashMap<>();
        schemaFields.put("category", Map.of("type", "STRING", "enum", categories));
        schemaFields.put("priority", Map.of("type", "STRING", "enum", priorities));
        schemaFields.put("title", Map.of("type", "STRING"));
        schemaFields.put("description", Map.of("type", "STRING"));
        schemaFields.put("aiNote", Map.of("type", "STRING"));
        schemaFields.put("imageHint", Map.of("type", "STRING"));
        schemaFields.put("heat", Map.of("type", "STRING", "enum", heatValues));
        schemaFields.put("confidence", Map.of("type", "NUMBER"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", schemaFields);
        schema.put("required", List.of("category", "priority", "title", "description",
                "aiNote", "imageHint", "heat", "confidence"));
        return schema;
    }

    @SuppressWarnings("unchecked")
    private AiAnalysisResult parseResponse(Map<String, Object> response) {
        if (response == null) {
            log.warn("Empty response from Gemini");
            return null;
        }

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            log.warn("No candidates in Gemini response");
            return null;
        }

        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null) {
            log.warn("No content in Gemini candidate");
            return null;
        }

        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            log.warn("No parts in Gemini candidate");
            return null;
        }

        Object textObj = parts.get(0).get("text");
        if (!(textObj instanceof String json) || json.isBlank()) {
            log.warn("Missing text payload in Gemini response");
            return null;
        }

        try {
            JsonNode parsed = objectMapper.readTree(json);
            PulseCategory category = parseEnum(parsed.path("category").asString(null), PulseCategory.class, PulseCategory.RUCH);
            PulsePriority priority = parseEnum(parsed.path("priority").asString(null), PulsePriority.class, PulsePriority.STANDARD);
            String title = parsed.path("title").asString("");
            String description = parsed.path("description").asString("");
            String aiNote = parsed.path("aiNote").asString("");
            String imageHint = parsed.path("imageHint").asString("");
            String heat = parsed.path("heat").asString("Średnie");
            double confidence = parsed.path("confidence").asDouble(0.0);

            return new AiAnalysisResult(category, priority, title, description, aiNote, imageHint, heat, confidence);
        } catch (Exception e) {
            log.warn("Failed to parse Gemini JSON payload: {}", json, e);
            return null;
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
