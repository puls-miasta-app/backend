package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RateLimitService {

    private static final String RATE_LIMIT_PREFIX = "rate_limit:";

    /**
     * Atomically increments the counter and sets TTL only on the first increment.
     * Prevents the race condition where the key could be left without an expiry
     * if the process crashes between INCR and EXPIRE.
     */
    private static final RedisScript<Long> INCR_WITH_EXPIRE = RedisScript.of(
            "local count = redis.call('INCR', KEYS[1])\n" +
                    "if count == 1 then\n" +
                    "  redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
                    "end\n" +
                    "return count",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final int defaultLimit;
    private final Duration defaultWindow;
    private final List<String> trustedProxies;

    public RateLimitService(
            StringRedisTemplate redisTemplate,
            @Value("${auth.rate-limit.default-limit:10}") int defaultLimit,
            @Value("${auth.rate-limit.default-window-seconds:60}") long defaultWindowSeconds,
            @Value("${auth.rate-limit.trusted-proxies:}") String trustedProxiesCsv) {
        if (defaultLimit <= 0) {
            throw new IllegalArgumentException("auth.rate-limit.default-limit must be greater than 0");
        }
        if (defaultWindowSeconds <= 0) {
            throw new IllegalArgumentException("auth.rate-limit.default-window-seconds must be greater than 0");
        }

        this.redisTemplate = redisTemplate;
        this.defaultLimit = defaultLimit;
        this.defaultWindow = Duration.ofSeconds(defaultWindowSeconds);
        this.trustedProxies = parseTrustedProxies(trustedProxiesCsv);
    }

    /**
     * Checks if a request should be rate limited based on client's IP address.
     * <p>
     * Uses an atomic Lua script to increment the counter and set TTL in a single
     * Redis round-trip, preventing the race condition that could leave keys without expiry.
     * The remaining time shown in the error is read from the actual Redis TTL instead
     * of approximating the full window duration.
     *
     * @param request HTTP request
     * @param limit   maximum number of requests allowed in window
     * @param window  time window for rate limiting
     * @throws ResponseStatusException 429 (Too Many Requests) if rate limit is exceeded
     */
    public void checkRateLimit(HttpServletRequest request, int limit, Duration window) {
        String ip = getTrustedClientIp(request);
        String normalizedUri = normalizeUri(request.getRequestURI());
        String key = RATE_LIMIT_PREFIX + ip + ":" + normalizedUri;

        Long count = redisTemplate.execute(INCR_WITH_EXPIRE, List.of(key), String.valueOf(window.getSeconds()));

        if (count != null && count > limit) {
            long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            long remainingMinutes = Math.max(1, (long) Math.ceil(ttlSeconds / 60.0));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    String.format("Przekroczono limit żądań. Spróbuj ponownie za %d minut.", remainingMinutes));
        }
    }

    /**
     * Checks if a request should be rate limited using default settings.
     *
     * @param request HTTP request
     */
    public void checkRateLimit(HttpServletRequest request) {
        checkRateLimit(request, defaultLimit, defaultWindow);
    }

    /**
     * Returns the trusted client IP address for the given request.
     * Exposed so that other services (e.g. LoginAttemptService via AuthService)
     * can scope their per-resource operations to the same resolved IP.
     *
     * @param request HTTP request
     * @return resolved client IP
     */
    public String getClientIp(HttpServletRequest request) {
        return getTrustedClientIp(request);
    }

    /**
     * Extracts the client IP address, validating proxy headers against trusted proxies.
     * Only trusts X-Forwarded-For and X-Real-IP from configured trusted proxies.
     * Prevents IP spoofing attacks where attackers set these headers to lock out victims.
     *
     * @param request HTTP request
     * @return validated client IP address
     */
    private String getTrustedClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        if (remoteAddr == null || remoteAddr.isEmpty()) {
            return "unknown";
        }

        for (String trustedProxy : trustedProxies) {
            if (remoteAddr.equals(trustedProxy)) {
                String forwardedFor = request.getHeader("X-Forwarded-For");
                if (forwardedFor != null && !forwardedFor.isEmpty()) {
                    if (forwardedFor.contains(",")) {
                        return forwardedFor.split(",")[0].trim();
                    }
                    return forwardedFor.trim();
                }

                String realIp = request.getHeader("X-Real-IP");
                if (realIp != null && !realIp.isEmpty()) {
                    if (realIp.contains(",")) {
                        return realIp.split(",")[0].trim();
                    }
                    return realIp.trim();
                }
            }
        }

        if (remoteAddr.contains(",")) {
            return remoteAddr.split(",")[0].trim();
        }
        return remoteAddr;
    }

    /**
     * Normalizes the URI to prevent rate limit bypass through case variations or trailing slashes.
     * Converts to lowercase and removes trailing slashes.
     *
     * @param uri the request URI
     * @return normalized URI
     */
    private String normalizeUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        return uri.toLowerCase().replaceAll("/+$", "");
    }

    private List<String> parseTrustedProxies(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return List.of();
        }
        return List.of(csv.split(","))
                .stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
