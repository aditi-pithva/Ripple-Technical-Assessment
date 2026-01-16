package com.ripple.payment.config;

import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Resilience4j components (Circuit Breaker, Retry, Timeout).
 * 
 * <p>Resilience4j provides:
 * <ul>
 *   <li><b>Circuit Breaker</b>: Prevents cascading failures by opening circuit when failure threshold is reached</li>
 *   <li><b>Retry</b>: Automatically retries failed requests with exponential backoff</li>
 *   <li><b>Timeout</b>: Ensures requests complete within specified time limit</li>
 * </ul>
 * 
 * <p>Configuration is done via application.properties. Resilience4j auto-configuration
 * handles bean creation when the dependency is present.
 */
@Configuration
public class Resilience4jConfig {
    // Resilience4j auto-configuration handles bean creation
    // This class serves as documentation and can be extended if needed
}

