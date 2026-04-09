package com.github.PulsMiastaApp.PulsMiasta.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "storage.r2")
public class StorageProperties {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private String region = "auto";

    /** Minimum file size (bytes) to trigger multipart upload. Default: 5 MB */
    private long multipartThreshold = 5 * 1024 * 1024;

    /** Part size for multipart upload. Default: 5 MB (R2 minimum) */
    private long multipartPartSize = 5 * 1024 * 1024;

    /** Maximum allowed upload size in bytes. Default: 20 MB */
    private long maxFileSize = 20 * 1024 * 1024;
}
