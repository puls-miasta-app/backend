package com.github.PulsMiastaApp.PulsMiasta.Push;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.PulsMiastaApp.PulsMiasta.Repository.DeviceRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wysyła powiadomienia push przez Expo Push API v2.
 * Obsługuje batch do 100 tokenów i usuwa wygasłe tokeny po błędzie DeviceNotRegistered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpoPushService {

    private static final int BATCH_SIZE = 100;

    @Value("${push.expo.url:https://exp.host/--/api/v2/push/send}")
    private String expoUrl;

    private final DeviceRegistrationRepository deviceRepo;
    private final RestClient restClient = RestClient.create();

    public void send(List<String> tokens, String title, String body, Map<String, Object> data) {
        if (tokens == null || tokens.isEmpty()) return;

        for (int i = 0; i < tokens.size(); i += BATCH_SIZE) {
            List<String> batch = tokens.subList(i, Math.min(i + BATCH_SIZE, tokens.size()));
            sendBatch(batch, title, body, data);
        }
    }

    private void sendBatch(List<String> tokens, String title, String body, Map<String, Object> data) {
        List<PushMessage> messages = tokens.stream()
                .map(token -> new PushMessage(token, title, body, "default", data))
                .toList();
        try {
            ExpoPushResponse response = restClient.post()
                    .uri(expoUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(messages)
                    .retrieve()
                    .body(ExpoPushResponse.class);

            if (response == null || response.data() == null) return;

            List<String> staleTokens = new ArrayList<>();
            for (int i = 0; i < response.data().size() && i < tokens.size(); i++) {
                TicketResult ticket = response.data().get(i);
                if ("error".equals(ticket.status())) {
                    String error = ticket.details() != null ? (String) ticket.details().get("error") : null;
                    if ("DeviceNotRegistered".equals(error) || "InvalidCredentials".equals(error)) {
                        staleTokens.add(tokens.get(i));
                    } else {
                        log.warn("Push delivery error for token *{}: {} — {}",
                                tokens.get(i).length() > 4 ? tokens.get(i).substring(tokens.get(i).length() - 4) : "****",
                                error, ticket.message());
                    }
                }
            }
            if (!staleTokens.isEmpty()) {
                log.info("Removing {} stale push token(s)", staleTokens.size());
                staleTokens.forEach(deviceRepo::deleteByPushToken);
            }
        } catch (Exception e) {
            log.warn("Failed to send push batch ({} tokens): {}", tokens.size(), e.getMessage());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PushMessage(String to, String title, String body, String sound, Map<String, Object> data) {}

    record ExpoPushResponse(List<TicketResult> data) {}

    record TicketResult(String status, String id, String message, Map<String, Object> details) {}
}
