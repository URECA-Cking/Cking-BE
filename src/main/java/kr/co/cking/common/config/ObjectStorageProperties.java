package kr.co.cking.common.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cking.storage")
public record ObjectStorageProperties(String type, String bucket, String region, URI endpoint) {
}
