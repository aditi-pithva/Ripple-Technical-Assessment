package com.ripple.payment.service;

import com.ripple.payment.exception.PaymentException;
import com.ripple.payment.model.*;
import com.ripple.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private FxServiceClient fxServiceClient;

    @InjectMocks
    private PaymentService paymentService;

    private PaymentRequest paymentRequest;

    @BeforeEach
    void setUp() {
        paymentRequest = new PaymentRequest();
        paymentRequest.setSender("Alice");
        paymentRequest.setReceiver("Bob");
        paymentRequest.setAmount(new BigDecimal("100.00"));
        paymentRequest.setSourceCurrency("USD");
        paymentRequest.setDestinationCurrency("EUR");
    }

    @Test
    void testProcessPayment_Success() {
        // Given
        BigDecimal fxRate = new BigDecimal("0.85");
        Payment savedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .status(PaymentStatus.PENDING)
            .build();

        Payment completedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .fxRate(fxRate)
            .payoutAmount(new BigDecimal("85.0000"))
            .status(PaymentStatus.SUCCEEDED)
            .build();

        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment, completedPayment);
        when(fxServiceClient.getFxRate("USD", "EUR")).thenReturn(fxRate);

        // When
        PaymentResponse response = paymentService.processPayment(paymentRequest);

        // Then
        assertNotNull(response);
        assertEquals(PaymentStatus.SUCCEEDED, response.getStatus());
        assertEquals(fxRate, response.getFxRate());
        assertEquals(new BigDecimal("85.0000"), response.getPayoutAmount());
        assertNull(response.getErrorMessage());
        verify(fxServiceClient).getFxRate("USD", "EUR");
        verify(paymentRepository, times(2)).save(any(Payment.class));
    }

    @Test
    void testProcessPayment_FxServiceFailure() {
        // Given
        Payment savedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .status(PaymentStatus.PENDING)
            .build();

        Payment failedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .status(PaymentStatus.FAILED)
            .errorMessage("FX service is unavailable")
            .build();

        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment, failedPayment);
        when(fxServiceClient.getFxRate("USD", "EUR"))
            .thenThrow(new PaymentException("FX service is unavailable"));

        // When
        PaymentResponse response = paymentService.processPayment(paymentRequest);

        // Then
        assertNotNull(response);
        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertNotNull(response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains("FX service is unavailable"));
    }

    @Test
    void testGetPayment_Success() {
        // Given
        Long paymentId = 1L;
        Payment payment = Payment.builder()
            .id(paymentId)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .fxRate(new BigDecimal("0.85"))
            .payoutAmount(new BigDecimal("85.0000"))
            .status(PaymentStatus.SUCCEEDED)
            .build();

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        // When
        PaymentResponse response = paymentService.getPayment(paymentId);

        // Then
        assertNotNull(response);
        assertEquals(paymentId, response.getId());
        assertEquals(PaymentStatus.SUCCEEDED, response.getStatus());
    }

    @Test
    void testGetPayment_NotFound() {
        // Given
        Long paymentId = 999L;
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(PaymentException.class, () -> paymentService.getPayment(paymentId));
    }

    @Test
    void testProcessPayment_SameCurrency_ShouldFail() {
        // Given - Same source and destination currency
        paymentRequest.setSourceCurrency("USD");
        paymentRequest.setDestinationCurrency("USD");

        Payment savedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("USD")
            .status(PaymentStatus.PENDING)
            .build();

        Payment failedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("USD")
            .status(PaymentStatus.FAILED)
            .errorMessage("Source and destination currencies must be different")
            .build();

        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment, failedPayment);

        // When
        PaymentResponse response = paymentService.processPayment(paymentRequest);

        // Then
        assertNotNull(response);
        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertTrue(response.getErrorMessage().contains("Source and destination currencies must be different"));
        verify(fxServiceClient, never()).getFxRate(anyString(), anyString());
    }

    @Test
    void testProcessPayment_FxServiceSlow_ShouldFail() {
        // Given - Simulating slow response (timeout)
        Payment savedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .status(PaymentStatus.PENDING)
            .build();

        Payment failedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .status(PaymentStatus.FAILED)
            .errorMessage("FX service is unavailable or slow")
            .build();

        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment, failedPayment);
        when(fxServiceClient.getFxRate("USD", "EUR"))
            .thenThrow(new PaymentException("FX service is unavailable or slow: Connection timeout"));

        // When
        PaymentResponse response = paymentService.processPayment(paymentRequest);

        // Then
        assertNotNull(response);
        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertNotNull(response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains("FX service is unavailable or slow"));
    }

    @Test
    void testProcessPayment_FxServiceReturnsError_ShouldFail() {
        // Given - FX service returns error
        Payment savedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .status(PaymentStatus.PENDING)
            .build();

        Payment failedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .status(PaymentStatus.FAILED)
            .errorMessage("Error calling FX service")
            .build();

        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment, failedPayment);
        when(fxServiceClient.getFxRate("USD", "EUR"))
            .thenThrow(new PaymentException("Error calling FX service: 500 Internal Server Error"));

        // When
        PaymentResponse response = paymentService.processPayment(paymentRequest);

        // Then
        assertNotNull(response);
        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertTrue(response.getErrorMessage().contains("Error calling FX service"));
    }

    @Test
    void testProcessPayment_ValidInputs_ComputesPayoutCorrectly() {
        // Given - Valid inputs, FX rate obtained
        BigDecimal fxRate = new BigDecimal("0.85");
        BigDecimal amount = new BigDecimal("100.00");
        BigDecimal expectedPayout = new BigDecimal("85.0000"); // 100 * 0.85

        Payment savedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(amount)
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .status(PaymentStatus.PENDING)
            .build();

        Payment completedPayment = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(amount)
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .fxRate(fxRate)
            .payoutAmount(expectedPayout)
            .status(PaymentStatus.SUCCEEDED)
            .build();

        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment, completedPayment);
        when(fxServiceClient.getFxRate("USD", "EUR")).thenReturn(fxRate);

        // When
        PaymentResponse response = paymentService.processPayment(paymentRequest);

        // Then
        assertNotNull(response);
        assertEquals(PaymentStatus.SUCCEEDED, response.getStatus());
        assertEquals(fxRate, response.getFxRate());
        assertEquals(expectedPayout, response.getPayoutAmount());
        assertEquals(amount, response.getAmount());
    }

    @Test
    void testGetPayment_RetrievesPaymentDetails_WithStatus() {
        // Given
        Long paymentId = 1L;
        Payment payment = Payment.builder()
            .id(paymentId)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .fxRate(new BigDecimal("0.85"))
            .payoutAmount(new BigDecimal("85.0000"))
            .status(PaymentStatus.SUCCEEDED)
            .build();

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        // When
        PaymentResponse response = paymentService.getPayment(paymentId);

        // Then
        assertNotNull(response);
        assertEquals(paymentId, response.getId());
        assertEquals(PaymentStatus.SUCCEEDED, response.getStatus());
        assertEquals(new BigDecimal("85.0000"), response.getPayoutAmount());
        assertNull(response.getErrorMessage());
    }

    @Test
    void testGetPayment_RetrievesPaymentDetails_WithErrorDiagnostic() {
        // Given - Payment with error diagnostic information
        Long paymentId = 1L;
        Payment payment = Payment.builder()
            .id(paymentId)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .status(PaymentStatus.FAILED)
            .errorMessage("FX service is unavailable or slow: Connection timeout")
            .build();

        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        // When
        PaymentResponse response = paymentService.getPayment(paymentId);

        // Then
        assertNotNull(response);
        assertEquals(paymentId, response.getId());
        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertNotNull(response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains("FX service is unavailable"));
        assertNull(response.getPayoutAmount()); // No payout for failed payment
    }

    @Test
    void testGetAllPayments_ReturnsAllPayments() {
        // Given
        Payment payment1 = Payment.builder()
            .id(1L)
            .sender("Alice")
            .receiver("Bob")
            .amount(new BigDecimal("100.00"))
            .sourceCurrency("USD")
            .destinationCurrency("EUR")
            .status(PaymentStatus.SUCCEEDED)
            .build();

        Payment payment2 = Payment.builder()
            .id(2L)
            .sender("Charlie")
            .receiver("Diana")
            .amount(new BigDecimal("200.00"))
            .sourceCurrency("GBP")
            .destinationCurrency("USD")
            .status(PaymentStatus.FAILED)
            .errorMessage("FX service error")
            .build();

        when(paymentRepository.findAll()).thenReturn(List.of(payment1, payment2));

        // When
        List<PaymentResponse> responses = paymentService.getAllPayments();

        // Then
        assertNotNull(responses);
        assertEquals(2, responses.size());
        assertEquals(1L, responses.get(0).getId());
        assertEquals(2L, responses.get(1).getId());
    }
}

