package com.github.PulsMiastaApp.PulsMiasta.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class UploadAsyncConfig {

    @Value("${upload.executor.core-pool-size:4}")
    private int corePoolSize;

    @Value("${upload.executor.max-pool-size:8}")
    private int maxPoolSize;

    @Value("${upload.executor.queue-capacity:50}")
    private int queueCapacity;

    @Bean("photoUploadExecutor")
    public Executor photoUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("photo-upload-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    // Dedykowany executor dla powiadomień push czatu — odizolowany od puli photo-upload,
    // żeby wysoki ruch w czacie nie blokował uploadów zdjęć (i odwrotnie).
    @Bean("chatNotificationExecutor")
    public Executor chatNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("chat-notify-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
