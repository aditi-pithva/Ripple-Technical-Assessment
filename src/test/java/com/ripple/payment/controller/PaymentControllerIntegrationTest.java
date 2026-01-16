package com.ripple.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ripple.payment.model.PaymentRequest;
import com.ripple.payment.model.PaymentResponse;
import com.ripple.payment.model.PaymentStatus;
import com.ripple.payment.repository.PaymentRepository;
import com.ripple.payment.service.FxServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests covering all scenarios from the requirements PDF:
 * 1. Process a Payment - with valid inputs and FX rate
 * 2. FX Service Integration - slow response, error, invalid response
 * 3. Retrieve a Payment - status, payout amount, error diagnostic
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockBean
    private FxServiceClient fxServiceClient;

    private PaymentRequest validPaymentRequest;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        validPaymentRequest = new PaymentRequest();
        validPaymentRequest.setSender("Alice");
        validPaymentRequest.setReceiver("Bob");
        validPaymentRequest.setAmount(new BigDecimal("100.00"));
        validPaymentRequest.setSourceCurrency("USD");
        validPaymentRequest.setDestinationCurrency("EUR");
    }

    @Test
    void testProcessPayment_Success_ValidInputsAndFxRate() throws Exception {
        // Given - Valid inputs and FX rate can be obtained
        when(fxServiceClient.getFxRate("USD", "EUR")).thenReturn(new BigDecimal("0.85"));

        // When
        MvcResult result = mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        // Then
        String responseBody = result.getResponse().getContentAsString();
        PaymentResponse response = objectMapper.readValue(responseBody, PaymentResponse.class);
        
        assertNotNull(response);
        assertNotNull(response.getId());
        assertEquals(PaymentStatus.SUCCEEDED, response.getStatus());
        assertEquals(new BigDecimal("100.00"), response.getAmount());
        assertEquals(new BigDecimal("0.85"), response.getFxRate());
        assertEquals(new BigDecimal("85.0000"), response.getPayoutAmount());
        assertEquals("USD", response.getSourceCurrency());
        assertEquals("EUR", response.getDestinationCurrency());
        assertNull(response.getErrorMessage());
    }

    @Test
    void testProcessPayment_InvalidRequest_MissingSender() throws Exception {
        PaymentRequest request = new PaymentRequest();
        request.setReceiver("Bob");
        request.setAmount(new BigDecimal("100.00"));
        request.setSourceCurrency("USD");
        request.setDestinationCurrency("EUR");

        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testProcessPayment_InvalidRequest_InvalidCurrency() throws Exception {
        validPaymentRequest.setSourceCurrency("US"); // Invalid - should be 3 letters

        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testProcessPayment_InvalidRequest_NegativeAmount() throws Exception {
        validPaymentRequest.setAmount(new BigDecimal("-10.00"));

        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testProcessPayment_FxServiceSlow_ShouldFail() throws Exception {
        // Given - FX service responds slowly (timeout)
        when(fxServiceClient.getFxRate(anyString(), anyString()))
            .thenThrow(new com.ripple.payment.exception.PaymentException(
                "FX service is unavailable or slow: Connection timeout"));

        // When
        MvcResult result = mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(status().isCreated()) // Payment is created but with FAILED status
                .andReturn();

        // Then
        String responseBody = result.getResponse().getContentAsString();
        PaymentResponse response = objectMapper.readValue(responseBody, PaymentResponse.class);
        
        assertNotNull(response);
        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertNotNull(response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains("FX service is unavailable or slow"));
        assertNull(response.getPayoutAmount());
    }

    @Test
    void testProcessPayment_FxServiceReturnsError_ShouldFail() throws Exception {
        // Given - FX service returns error
        when(fxServiceClient.getFxRate(anyString(), anyString()))
            .thenThrow(new com.ripple.payment.exception.PaymentException(
                "Error calling FX service: 500 Internal Server Error"));

        // When
        MvcResult result = mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        // Then
        String responseBody = result.getResponse().getContentAsString();
        PaymentResponse response = objectMapper.readValue(responseBody, PaymentResponse.class);
        
        assertNotNull(response);
        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertTrue(response.getErrorMessage().contains("Error calling FX service"));
    }

    @Test
    void testProcessPayment_FxServiceInvalidResponse_ShouldFail() throws Exception {
        // Given - FX service provides invalid response
        when(fxServiceClient.getFxRate(anyString(), anyString()))
            .thenThrow(new com.ripple.payment.exception.PaymentException(
                "Invalid response from FX service: rate not found"));

        // When
        MvcResult result = mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        // Then
        String responseBody = result.getResponse().getContentAsString();
        PaymentResponse response = objectMapper.readValue(responseBody, PaymentResponse.class);
        
        assertNotNull(response);
        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertTrue(response.getErrorMessage().contains("Invalid response from FX service"));
    }

    @Test
    void testRetrievePayment_Success_WithStatusAndPayout() throws Exception {
        // Given - Create a successful payment first
        when(fxServiceClient.getFxRate("USD", "EUR")).thenReturn(new BigDecimal("0.85"));
        
        MvcResult createResult = mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        PaymentResponse createdPayment = objectMapper.readValue(
            createResult.getResponse().getContentAsString(), PaymentResponse.class);
        Long paymentId = createdPayment.getId();

        // When - Retrieve the payment
        MvcResult result = mockMvc.perform(get("/api/payments/{id}", paymentId))
                .andExpect(status().isOk())
                .andReturn();

        // Then
        String responseBody = result.getResponse().getContentAsString();
        PaymentResponse response = objectMapper.readValue(responseBody, PaymentResponse.class);
        
        assertNotNull(response);
        assertEquals(paymentId, response.getId());
        assertEquals(PaymentStatus.SUCCEEDED, response.getStatus());
        assertEquals(new BigDecimal("85.0000"), response.getPayoutAmount());
        assertNotNull(response.getFxRate());
    }

    @Test
    void testRetrievePayment_Failed_WithErrorDiagnostic() throws Exception {
        // Given - Create a failed payment
        when(fxServiceClient.getFxRate(anyString(), anyString()))
            .thenThrow(new com.ripple.payment.exception.PaymentException(
                "FX service is unavailable or slow: Connection timeout"));

        MvcResult createResult = mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        PaymentResponse createdPayment = objectMapper.readValue(
            createResult.getResponse().getContentAsString(), PaymentResponse.class);
        Long paymentId = createdPayment.getId();

        // When - Retrieve the payment
        MvcResult result = mockMvc.perform(get("/api/payments/{id}", paymentId))
                .andExpect(status().isOk())
                .andReturn();

        // Then
        String responseBody = result.getResponse().getContentAsString();
        PaymentResponse response = objectMapper.readValue(responseBody, PaymentResponse.class);
        
        assertNotNull(response);
        assertEquals(paymentId, response.getId());
        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertNotNull(response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains("FX service is unavailable"));
        assertNull(response.getPayoutAmount()); // No payout for failed payment
    }

    @Test
    void testRetrievePayment_NotFound() throws Exception {
        mockMvc.perform(get("/api/payments/999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetAllPayments_ReturnsAllPayments() throws Exception {
        // Given - Create multiple payments
        when(fxServiceClient.getFxRate("USD", "EUR")).thenReturn(new BigDecimal("0.85"));
        
        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(status().isCreated());

        PaymentRequest request2 = new PaymentRequest();
        request2.setSender("Charlie");
        request2.setReceiver("Diana");
        request2.setAmount(new BigDecimal("200.00"));
        request2.setSourceCurrency("GBP");
        request2.setDestinationCurrency("USD");
        
        when(fxServiceClient.getFxRate("GBP", "USD")).thenReturn(new BigDecimal("1.25"));
        
        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isCreated());

        // When
        MvcResult result = mockMvc.perform(get("/api/payments"))
                .andExpect(status().isOk())
                .andReturn();

        // Then
        String responseBody = result.getResponse().getContentAsString();
        PaymentResponse[] responses = objectMapper.readValue(responseBody, PaymentResponse[].class);
        
        assertNotNull(responses);
        assertTrue(responses.length >= 2);
    }

    @Test
    void testProcessPayment_SameCurrency_ShouldFail() throws Exception {
        // Given - Same source and destination currency
        validPaymentRequest.setSourceCurrency("USD");
        validPaymentRequest.setDestinationCurrency("USD");

        // When
        MvcResult result = mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validPaymentRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        // Then
        String responseBody = result.getResponse().getContentAsString();
        PaymentResponse response = objectMapper.readValue(responseBody, PaymentResponse.class);
        
        assertNotNull(response);
        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertTrue(response.getErrorMessage().contains("Source and destination currencies must be different"));
        verify(fxServiceClient, never()).getFxRate(anyString(), anyString());
    }
}
