package com.ripple.payment.service;

import com.ripple.payment.config.FxServiceConfig;
import com.ripple.payment.exception.PaymentException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Client for interacting with the external FX service.
 * 
 * <p>This client implements:
 * <ul>
 *   <li><b>Circuit Breaker</b>: Prevents cascading failures when FX service is down</li>
 *   <li><b>Retry</b>: Automatically retries failed requests with exponential backoff</li>
 *   <li><b>Timeout</b>: Ensures requests don't hang indefinitely</li>
 * </ul>
 * 
 * <p>Circuit Breaker States:
 * <ul>
 *   <li><b>CLOSED</b>: Normal operation, requests pass through</li>
 *   <li><b>OPEN</b>: Service is failing, requests fail fast without calling service</li>
 *   <li><b>HALF_OPEN</b>: Testing if service has recovered, allows limited requests</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FxServiceClient {

    private final RestTemplate restTemplate;
    private final FxServiceConfig fxServiceConfig;

    /**
     * Retrieves FX rate from external service with circuit breaker and retry protection.
     * 
     * @param sourceCurrency source currency code (e.g., "USD")
     * @param destinationCurrency destination currency code (e.g., "EUR")
     * @return FX conversion rate
     * @throws PaymentException if service is unavailable, returns invalid data, or circuit is open
     */
    @CircuitBreaker(name = "fxService", fallbackMethod = "getFxRateFallback")
    @Retry(name = "fxService")
    public BigDecimal getFxRate(String sourceCurrency, String destinationCurrency) {
        return getFxRateInternal(sourceCurrency, destinationCurrency);
    }

    /**
     * Internal method that performs the actual FX rate retrieval.
     * This method is wrapped by circuit breaker and retry annotations.
     */
    private BigDecimal getFxRateInternal(String sourceCurrency, String destinationCurrency) {
        try {
            String url = String.format("%s/fx-rate?from=%s&to=%s",
                fxServiceConfig.getBaseUrl(),
                sourceCurrency,
                destinationCurrency);

            log.info("Fetching FX rate from {} to {} from URL: {}", sourceCurrency, destinationCurrency, url);

            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response == null || !response.containsKey("rate")) {
                log.error("Invalid response from FX service: {}", response);
                throw new PaymentException("Invalid response from FX service: rate not found");
            }

            Object rateObj = response.get("rate");
            BigDecimal rate;

            if (rateObj instanceof Number) {
                rate = BigDecimal.valueOf(((Number) rateObj).doubleValue());
            } else if (rateObj instanceof String) {
                rate = new BigDecimal((String) rateObj);
            } else {
                log.error("Invalid rate format in response: {}", rateObj);
                throw new PaymentException("Invalid rate format in FX service response");
            }

            if (rate.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("Invalid FX rate: {}", rate);
                throw new PaymentException("FX rate must be greater than zero");
            }

            log.info("Successfully retrieved FX rate: {} for {}/{}", rate, sourceCurrency, destinationCurrency);
            return rate;

        } catch (ResourceAccessException e) {
            log.error("Timeout or connection error accessing FX service: {}", e.getMessage());
            throw new PaymentException("FX service is unavailable or slow: " + e.getMessage(), e);
        } catch (RestClientException e) {
            log.error("Error calling FX service: {}", e.getMessage());
            throw new PaymentException("Error calling FX service: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error getting FX rate: {}", e.getMessage(), e);
            throw new PaymentException("Unexpected error getting FX rate: " + e.getMessage(), e);
        }
    }

    /**
     * Fallback method called when circuit breaker is OPEN or service fails.
     * This prevents cascading failures and provides graceful degradation.
     * 
     * @param sourceCurrency source currency code
     * @param destinationCurrency destination currency code
     * @param throwable the exception that triggered the fallback
     * @return never returns normally, always throws PaymentException
     * @throws PaymentException indicating circuit breaker is open or service unavailable
     */
    private BigDecimal getFxRateFallback(String sourceCurrency, String destinationCurrency, Throwable throwable) {
        log.warn("Circuit breaker fallback triggered for {}/{}: {}", 
            sourceCurrency, destinationCurrency, throwable.getMessage());
        throw new PaymentException(
            String.format("FX service circuit breaker is OPEN. Service unavailable for %s/%s. " +
                "Please try again later. Original error: %s", 
                sourceCurrency, destinationCurrency, throwable.getMessage()), 
            throwable);
    }
}

