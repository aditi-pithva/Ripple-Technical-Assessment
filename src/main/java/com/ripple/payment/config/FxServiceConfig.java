package com.ripple.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

@Data
@Configuration
public class FxServiceConfig {
    @Value("${fx.service.base-url}")
    private String baseUrl;

    @Value("${fx.service.timeout.connect:5000}")
    private int connectTimeout;

    @Value("${fx.service.timeout.read:10000}")
    private int readTimeout;

    @Value("${fx.service.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${fx.service.retry.delay:1000}")
    private long retryDelay;
}

