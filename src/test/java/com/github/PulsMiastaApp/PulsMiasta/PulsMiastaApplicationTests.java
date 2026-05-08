package com.github.PulsMiastaApp.PulsMiasta;

import org.junit.jupiter.api.Test;
import com.github.PulsMiastaApp.PulsMiasta.Crypto.CryptoKeyProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class PulsMiastaApplicationTests {

    @MockitoBean
    private RedisConnectionFactory redisConnectionFactory;

    @MockitoBean
    private ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @MockitoBean
    private CryptoKeyProvider cryptoKeyProvider;

    @Test
    void contextLoads() {
    }
}
