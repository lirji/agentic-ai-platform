package com.lrj.agentic.knowledge.infrastructure.storage;

import com.lrj.agentic.knowledge.domain.SourceStore;
import java.time.Duration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** 对象存储连接、总期限与生命周期集中装配，不在每次上传创建客户端。 */
@Configuration
public class SourceConfiguration {
    /** 有界连接池和单次总期限；避免 SDK 隐式重试叠加到受理请求。 */
    @Bean(destroyMethod = "close")
    S3Client sourceClient(SourceProperties properties) {
        return S3Client.builder()
                .endpointOverride(properties.endpoint()).region(Region.of(properties.region()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .httpClientBuilder(ApacheHttpClient.builder().maxConnections(properties.maxConnections())
                        .connectionTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                        .connectionAcquisitionTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                        .socketTimeout(Duration.ofSeconds(properties.callTimeoutSeconds())))
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(Duration.ofSeconds(properties.callTimeoutSeconds()))
                        .apiCallAttemptTimeout(Duration.ofSeconds(properties.callTimeoutSeconds()))
                        .retryStrategy(strategy -> strategy.maxAttempts(1)))
                .build();
    }

    /** 仅返回安全状态，健康响应不泄露桶地址或底层异常。 */
    @Bean
    HealthIndicator sourceStoreHealthIndicator(SourceStore store) {
        return () -> store.available() ? Health.up().build() : Health.down().build();
    }
}
