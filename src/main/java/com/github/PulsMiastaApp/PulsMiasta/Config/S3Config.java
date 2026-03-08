package com.github.PulsMiastaApp.PulsMiasta.Config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
public class S3Config {

    @Value("${r2.account-id}")
    private String accountId;

    @Value("${r2.access-key-id}")
    private String accessKeyId;

    @Value("${r2.secret-access-key}")
    private String secretAccessKey;

    /** Fail fast at startup rather than on the first upload request. */
    @PostConstruct
    public void validate() {
        if (!StringUtils.hasText(accountId)) {
            throw new IllegalStateException("R2 configuration error: r2.account-id (R2_ACCOUNT_ID) must be set");
        }
        if (!StringUtils.hasText(accessKeyId)) {
            throw new IllegalStateException("R2 configuration error: r2.access-key-id (R2_ACCESS_KEY_ID) must be set");
        }
        if (!StringUtils.hasText(secretAccessKey)) {
            throw new IllegalStateException("R2 configuration error: r2.secret-access-key (R2_SECRET_ACCESS_KEY) must be set");
        }
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create("https://" + accountId + ".r2.cloudflarestorage.com"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                // R2 is region-agnostic; "auto" routes to the nearest data centre
                .region(Region.of("auto"))
                .build();
    }
}
