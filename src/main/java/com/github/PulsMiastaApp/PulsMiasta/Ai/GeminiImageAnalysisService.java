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
import java.util.ArrayList;
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
            - category: najlepiej pasująca kategoria główna (RUCH, BEZPIECZENSTWO, ZIELEN)
            - priority: PILNE, STANDARD lub NISKIE.
              PILNE (tylko kilka przypadków na tysiąc — bezpośrednie zagrożenie życia/zdrowia):
                głęboka dziura w jezdni (>15 cm), wyrwany właz kanalizacyjny, zwalone drzewo
                blokujące drogę, odsłonięte przewody elektryczne pod napięciem, wyciek gazu,
                grożąca zawaleniem konstrukcja.
              STANDARD (typowe usterki infrastrukturalne): nierówny chodnik, małe ubytki asfaltu,
                pęknięcia nawierzchni, wyboje, kałuże, graffiti, uszkodzone znaki, połamane
                gałęzie, śmieci, niedziałające latarnie, zaniedbana zieleń.
              NISKIE (usterki kosmetyczne bez wpływu na użytkowanie): plamy i zabrudzenia na
                chodniku lub jezdni, nieznaczne przebarwienia nawierzchni, lekkie zadrapania
                na ławce lub ogrodzeniu, drobne ślady farby, estetyczne zniszczenia bez
                jakiegokolwiek zagrożenia lub utrudnienia. Domyślnie użyj STANDARD — NISKIE
                tylko gdy problem jest wyłącznie wizualny i nie stanowi żadnego utrudnienia.
            - title: bardzo krótki tytuł (max 8 słów) po polsku, np. "Dziura w jezdni przy skrzyżowaniu"
            - description: krótki opis problemu po polsku (max 2 zdania, bez emoji)
            - aiNote: jednozdaniowa notatka serwisu AI skierowana do odbiorcy (np. "Utrudnienie dla kierowców")
            - imageHint: krótki opis zawartości zdjęcia (max 6 słów, np. "Dziura w asfalcie, krawężnik")
            - heat: jedno z: "Wysokie", "Średnie", "Niskie" — oszacowana skala zasięgu/ważności problemu
            - confidence: liczba 0..1 określająca pewność, że zdjęcie faktycznie pokazuje problem miejski
            - additionalThreats: tablica dodatkowych zagrożeń widocznych na TYM SAMYM zdjęciu,
              ale należących do INNEJ kategorii niż główna (category). Każdy element zawiera:
              category, priority, title, description, aiNote, imageHint, heat — analogicznie jak wyżej.
              Tablica powinna być pusta [], jeżeli wszystkie widoczne problemy należą do tej samej kategorii.
              Maksymalnie 2 elementy. NIE powtarzaj tej samej kategorii co category ani między elementami.

            Przykład: zdjęcie pokazuje jednocześnie dziurę w jezdni (RUCH) i zniszczoną latarnię
            (BEZPIECZENSTWO) → category=RUCH, additionalThreats=[{category=BEZPIECZENSTWO,...}].

            Jeżeli zdjęcie nie przedstawia problemu miejskiego, ustaw category=RUCH (fallback),
            priority=STANDARD, confidence bliskie 0, additionalThreats=[] i w description krótko wyjaśnij.
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

        Map<String, Object> threatProps = new LinkedHashMap<>();
        threatProps.put("category", Map.of("type", "STRING", "enum", categories));
        threatProps.put("priority", Map.of("type", "STRING", "enum", priorities));
        threatProps.put("title", Map.of("type", "STRING"));
        threatProps.put("description", Map.of("type", "STRING"));
        threatProps.put("aiNote", Map.of("type", "STRING"));
        threatProps.put("imageHint", Map.of("type", "STRING"));
        threatProps.put("heat", Map.of("type", "STRING", "enum", heatValues));

        Map<String, Object> threatSchema = new LinkedHashMap<>();
        threatSchema.put("type", "OBJECT");
        threatSchema.put("properties", threatProps);
        threatSchema.put("required", List.of("category", "priority", "title", "description",
                "aiNote", "imageHint", "heat"));

        Map<String, Object> schemaFields = new LinkedHashMap<>();
        schemaFields.put("category", Map.of("type", "STRING", "enum", categories));
        schemaFields.put("priority", Map.of("type", "STRING", "enum", priorities));
        schemaFields.put("title", Map.of("type", "STRING"));
        schemaFields.put("description", Map.of("type", "STRING"));
        schemaFields.put("aiNote", Map.of("type", "STRING"));
        schemaFields.put("imageHint", Map.of("type", "STRING"));
        schemaFields.put("heat", Map.of("type", "STRING", "enum", heatValues));
        schemaFields.put("confidence", Map.of("type", "NUMBER"));
        schemaFields.put("additionalThreats", Map.of("type", "ARRAY", "items", threatSchema));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", schemaFields);
        schema.put("required", List.of("category", "priority", "title", "description",
                "aiNote", "imageHint", "heat", "confidence", "additionalThreats"));
        return schema;
    }

    private AiAnalysisResult parseResponse(Map<String, Object> response) {
        String json = extractText(response);
        if (json == null) {
            log.warn("Empty or missing text payload in Gemini response");
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

            List<AiAnalysisResult.AdditionalThreat> additionalThreats = new ArrayList<>();
            JsonNode threatsNode = parsed.path("additionalThreats");
            if (threatsNode.isArray()) {
                for (JsonNode t : threatsNode) {
                    PulseCategory tCat = parseEnum(t.path("category").asString(null), PulseCategory.class, null);
                    if (tCat == null || tCat == category) continue;
                    PulsePriority tPri = parseEnum(t.path("priority").asString(null), PulsePriority.class, PulsePriority.STANDARD);
                    additionalThreats.add(new AiAnalysisResult.AdditionalThreat(
                            tCat, tPri,
                            t.path("title").asString(""),
                            t.path("description").asString(""),
                            t.path("aiNote").asString(""),
                            t.path("imageHint").asString(""),
                            t.path("heat").asString("Średnie")));
                }
            }

            return new AiAnalysisResult(category, priority, title, description, aiNote, imageHint, heat, confidence, additionalThreats);
        } catch (Exception e) {
            log.warn("Failed to parse Gemini JSON payload: {}", json, e);
            return null;
        }
    }

    /**
     * Pyta Gemini (wyłącznie tekstowo, bez zdjęcia) czy dwa opisy zgłoszeń dotyczą
     * tego samego fizycznego problemu. Używane przed merge'em deduplikacyjnym.
     *
     * <p>Zwraca {@code false} przy jakimkolwiek błędzie — bezpieczniejsze jest
     * utworzenie nowego pulsu niż błędne scalenie różnych problemów.
     */
    public boolean areSameProblem(
            String title1, String description1, String imageHint1,
            String title2, String description2, String imageHint2) {

        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return false;
        }

        String prompt = """
                Jesteś systemem weryfikującym duplikaty zgłoszeń miejskich w Polsce.
                Oceń, czy dwa poniższe zgłoszenia opisują TEN SAM fizyczny problem (ten sam obiekt lub uszkodzenie).

                Zgłoszenie A:
                - Tytuł: %s
                - Opis: %s
                - Zawartość zdjęcia: %s

                Zgłoszenie B:
                - Tytuł: %s
                - Opis: %s
                - Zawartość zdjęcia: %s

                Zwróć JSON z polem sameProblem=true TYLKO jeśli oba zgłoszenia WYRAŹNIE dotyczą
                identycznego obiektu lub tego samego uszkodzenia w tym samym miejscu.
                Przy jakichkolwiek wątpliwościach zwróć sameProblem=false.
                """.formatted(
                blankOr(title1, "brak"), blankOr(description1, "brak"), blankOr(imageHint1, "brak"),
                blankOr(title2, "brak"), blankOr(description2, "brak"), blankOr(imageHint2, "brak"));

        Map<String, Object> schema = Map.of(
                "type", "OBJECT",
                "properties", Map.of("sameProblem", Map.of("type", "BOOLEAN")),
                "required", List.of("sameProblem"));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", schema);
        generationConfig.put("temperature", 0.1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
        body.put("generationConfig", generationConfig);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Map<String, Object> response = restClient.post()
                        .uri("/models/{model}:generateContent?key={key}",
                                properties.getModel(), properties.getApiKey())
                        .body(body)
                        .retrieve()
                        .body(MAP_TYPE);

                String json = extractText(response);
                if (json == null) return false;
                JsonNode parsed = objectMapper.readTree(json);
                return parsed.path("sameProblem").asBoolean(false);

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() != 429 || attempt == MAX_ATTEMPTS) {
                    log.warn("areSameProblem: Gemini error {}", e.getStatusCode());
                    return false;
                }
                sleepBackoff(attempt);
            } catch (HttpServerErrorException e) {
                if (attempt == MAX_ATTEMPTS) { log.warn("areSameProblem: Gemini 5xx"); return false; }
                sleepBackoff(attempt);
            } catch (Exception e) {
                log.warn("areSameProblem: unexpected error: {}", e.getMessage());
                return false;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> response) {
        if (response == null) return null;
        var candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) return null;
        var content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null) return null;
        var parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) return null;
        Object text = parts.get(0).get("text");
        return text instanceof String s && !s.isBlank() ? s : null;
    }

    private static String blankOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
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
