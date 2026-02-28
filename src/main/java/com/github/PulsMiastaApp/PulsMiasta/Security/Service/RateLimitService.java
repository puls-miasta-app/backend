package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RateLimitService {

    private final Cache<String, RateLimitEntry> cache;
    private final int defaultLimit;
    private final Duration defaultWindow;

    public RateLimitService(
            @Value("${auth.rate-limit.default-limit:10}") int defaultLimit,
            @Value("${auth.rate-limit.default-window-seconds:60}") long defaultWindowSeconds) {
        this.defaultLimit = defaultLimit;
        this.defaultWindow = Duration.ofSeconds(defaultWindowSeconds);

        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfter(new ExpireAfterWindowExpiry())
                .build();
    }

    /**
     * Checks if a request should be rate limited based on client's IP address.
     *
     * @param request HTTP request
     * @param limit   maximum number of requests allowed in window
     * @param window  time window for rate limiting
     * @throws ResponseStatusException 429 (Too Many Requests) if rate limit is exceeded
     */
    public void checkRateLimit(HttpServletRequest request, int limit, Duration window) {
        String key = getRateLimitKey(request);

        RateLimitEntry entry = cache.get(key, k -> new RateLimitEntry(window));

        if (entry == null) {
            entry = new RateLimitEntry(window);
            cache.put(key, entry);
        }

        if (entry.getCount() >= limit) {
            long remainingSeconds = window.getSeconds() - entry.getAgeSeconds();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    String.format("Rate limit exceeded. Please try again in %d seconds.", remainingSeconds));
        }

        entry.increment();
    }

    /**
     * Checks if a request should be rate limited using default settings.
     *
     * @param request HTTP request
     */
    public void checkRateLimit(HttpServletRequest request) {
        checkRateLimit(request, defaultLimit, defaultWindow);
    }

    private String getRateLimitKey(HttpServletRequest request) {
        String ip = getClientIp(request);
        String uri = request.getRequestURI();
        return ip + ":" + uri;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private static class RateLimitEntry {
        private final long createdAt;
        private final Duration window;
        private int count;

        RateLimitEntry(Duration window) {
            this.createdAt = System.currentTimeMillis();
            this.window = window;
            this.count = 0;
        }

        synchronized void increment() {
            count++;
        }

        int getCount() {
            return count;
        }

        long getAgeSeconds() {
            return (System.currentTimeMillis() - createdAt) / 1000;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > window.toMillis();
        }
    }

    private static class ExpireAfterWindowExpiry implements Expiry<String, RateLimitEntry> {
        @Override
        public long expireAfterCreate(String key, RateLimitEntry value, long currentTime) {
            return TimeUnit.MILLISECONDS.toNanos(value.window.toMillis());
        }

        @Override
        public long expireAfterUpdate(String key, RateLimitEntry value, long currentTime, long currentDuration) {
            return currentDuration;
        }

        @Override
        public long expireAfterRead(String key, RateLimitEntry value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
