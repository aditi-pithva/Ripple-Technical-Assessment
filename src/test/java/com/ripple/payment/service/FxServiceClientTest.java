package com.ripple.payment.service;

import com.ripple.payment.config.FxServiceConfig;
import com.ripple.payment.exception.PaymentException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FxServiceClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private FxServiceConfig fxServiceConfig;

    @InjectMocks
    private FxServiceClient fxServiceClient;

    @BeforeEach
    void setUp() {
        when(fxServiceConfig.getBaseUrl()).thenReturn("https://test-fx-service.com");
    }

    @Test
    void testGetFxRate_Success() {
        // Given
        Map<String, Object> response = new HashMap<>();
        response.put("rate", 0.85);
        
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(response);

        // When
        BigDecimal rate = fxServiceClient.getFxRate("USD", "EUR");

        // Then
        assertNotNull(rate);
        assertEquals(new BigDecimal("0.85"), rate);
        verify(restTemplate).getForObject(anyString(), eq(Map.class));
    }

    @Test
    void testGetFxRate_InvalidResponse() {
        // Given
        Map<String, Object> response = new HashMap<>();
        response.put("error", "Invalid request");
        
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(response);

        // When & Then
        assertThrows(PaymentException.class, () -> fxServiceClient.getFxRate("USD", "EUR"));
    }

    @Test
    void testGetFxRate_Timeout() {
        // Given
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
            .thenThrow(new ResourceAccessException("Connection timeout"));

        // When & Then
        assertThrows(PaymentException.class, () -> fxServiceClient.getFxRate("USD", "EUR"));
    }

    @Test
    void testGetFxRate_SlowResponse_Timeout() {
        // Given - Simulating slow response (ResourceAccessException for timeout)
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
            .thenThrow(new ResourceAccessException("Read timed out"));

        // When & Then
        PaymentException exception = assertThrows(PaymentException.class, 
            () -> fxServiceClient.getFxRate("USD", "EUR"));
        assertTrue(exception.getMessage().contains("FX service is unavailable or slow"));
    }

    @Test
    void testGetFxRate_ServiceUnavailable_ReturnsError() {
        // Given - Service returns HTTP error
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
            .thenThrow(new RestClientException("500 Internal Server Error"));

        // When & Then
        PaymentException exception = assertThrows(PaymentException.class, 
            () -> fxServiceClient.getFxRate("USD", "EUR"));
        assertTrue(exception.getMessage().contains("Error calling FX service"));
    }

    @Test
    void testGetFxRate_InvalidResponse_NullResponse() {
        // Given - Service returns null
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(null);

        // When & Then
        PaymentException exception = assertThrows(PaymentException.class, 
            () -> fxServiceClient.getFxRate("USD", "EUR"));
        assertTrue(exception.getMessage().contains("Invalid response from FX service"));
    }

    @Test
    void testGetFxRate_InvalidResponse_MissingRateField() {
        // Given - Response doesn't contain "rate" field
        Map<String, Object> response = new HashMap<>();
        response.put("error", "Service error");
        response.put("message", "Invalid currency pair");
        
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(response);

        // When & Then
        PaymentException exception = assertThrows(PaymentException.class, 
            () -> fxServiceClient.getFxRate("USD", "EUR"));
        assertTrue(exception.getMessage().contains("Invalid response from FX service"));
    }

    @Test
    void testGetFxRate_InvalidResponse_InvalidRateFormat() {
        // Given - Rate is not a number or string
        Map<String, Object> response = new HashMap<>();
        response.put("rate", new Object()); // Invalid format
        
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(response);

        // When & Then
        PaymentException exception = assertThrows(PaymentException.class, 
            () -> fxServiceClient.getFxRate("USD", "EUR"));
        assertTrue(exception.getMessage().contains("Invalid rate format"));
    }

    @Test
    void testGetFxRate_InvalidResponse_ZeroRate() {
        // Given - Rate is zero or negative
        Map<String, Object> response = new HashMap<>();
        response.put("rate", 0.0);
        
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(response);

        // When & Then
        PaymentException exception = assertThrows(PaymentException.class, 
            () -> fxServiceClient.getFxRate("USD", "EUR"));
        assertTrue(exception.getMessage().contains("FX rate must be greater than zero"));
    }

    @Test
    void testGetFxRate_InvalidResponse_NegativeRate() {
        // Given - Rate is negative
        Map<String, Object> response = new HashMap<>();
        response.put("rate", -0.5);
        
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(response);

        // When & Then
        PaymentException exception = assertThrows(PaymentException.class, 
            () -> fxServiceClient.getFxRate("USD", "EUR"));
        assertTrue(exception.getMessage().contains("FX rate must be greater than zero"));
    }

    @Test
    void testGetFxRate_Success_StringRate() {
        // Given - Rate as string (valid format)
        Map<String, Object> response = new HashMap<>();
        response.put("rate", "0.85");
        
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(response);

        // When
        BigDecimal rate = fxServiceClient.getFxRate("USD", "EUR");

        // Then
        assertNotNull(rate);
        assertEquals(new BigDecimal("0.85"), rate);
    }

    @Test
    void testGetFxRate_Success_DoubleRate() {
        // Given - Rate as double
        Map<String, Object> response = new HashMap<>();
        response.put("rate", 0.85);
        
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(response);

        // When
        BigDecimal rate = fxServiceClient.getFxRate("USD", "EUR");

        // Then
        assertNotNull(rate);
        assertEquals(new BigDecimal("0.85"), rate);
    }

    @Test
    void testGetFxRate_Success_IntegerRate() {
        // Given - Rate as integer
        Map<String, Object> response = new HashMap<>();
        response.put("rate", 1);
        
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(response);

        // When
        BigDecimal rate = fxServiceClient.getFxRate("USD", "USD");

        // Then
        assertNotNull(rate);
        assertEquals(BigDecimal.ONE, rate);
    }
}

