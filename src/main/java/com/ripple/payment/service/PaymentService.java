package com.ripple.payment.service;

import com.ripple.payment.exception.PaymentException;
import com.ripple.payment.model.*;
import com.ripple.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for processing cross-currency payments.
 * 
 * <p>Thread-Safety: This service is thread-safe for concurrent payment processing:
 * <ul>
 *   <li>No shared mutable state - all dependencies are final and thread-safe</li>
 *   <li>Each payment is created fresh per request - no shared payment instances</li>
 *   <li>Database transactions provide isolation between concurrent operations</li>
 *   <li>Optimistic locking (@Version) prevents lost updates on concurrent modifications</li>
 *   <li>Immutable types used (BigDecimal, String, LocalDateTime, PaymentStatus enum)</li>
 *   <li>Thread-safe dependencies: RestTemplate, JpaRepository are stateless</li>
 * </ul>
 * 
 * <p>Multiple threads can safely process different payments concurrently.
 * If the same payment is updated concurrently, optimistic locking will detect and handle it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final FxServiceClient fxServiceClient;

    /**
     * Processes a payment request with currency conversion.
     * This method is thread-safe and can handle concurrent requests.
     * 
     * @param request the payment request
     * @return payment response with status and payout amount
     */
    @Transactional(noRollbackFor = PaymentException.class)
    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("Processing payment request: {} {} {} -> {} {}",
            request.getAmount(), request.getSourceCurrency(),
            request.getSender(), request.getDestinationCurrency(), request.getReceiver());

        // Create payment entity
        Payment payment = Payment.builder()
            .sender(request.getSender())
            .receiver(request.getReceiver())
            .amount(request.getAmount())
            .sourceCurrency(request.getSourceCurrency().toUpperCase())
            .destinationCurrency(request.getDestinationCurrency().toUpperCase())
            .status(PaymentStatus.PENDING)
            .build();

        try {
            // Save payment initially with PENDING status
            payment = paymentRepository.save(payment);
            log.debug("Payment {} created with PENDING status", payment.getId());

            // Validate currencies are different
            if (payment.getSourceCurrency().equals(payment.getDestinationCurrency())) {
                throw new PaymentException("Source and destination currencies must be different");
            }

            // Get FX rate (this may throw PaymentException if FX service fails)
            BigDecimal fxRate = fxServiceClient.getFxRate(
                payment.getSourceCurrency(),
                payment.getDestinationCurrency()
            );

            // Calculate payout
            BigDecimal payoutAmount = payment.getAmount()
                .multiply(fxRate)
                .setScale(4, RoundingMode.HALF_UP);

            // Update payment with success
            payment.setFxRate(fxRate);
            payment.setPayoutAmount(payoutAmount);
            payment.setStatus(PaymentStatus.SUCCEEDED);
            payment.setErrorMessage(null);

            // Save successful payment
            payment = paymentRepository.save(payment);
            log.info("Payment {} succeeded. Payout: {} {}", payment.getId(), payoutAmount, payment.getDestinationCurrency());

        } catch (PaymentException e) {
            // Business logic failure - update payment status but don't rollback transaction
            log.error("Payment processing failed: {}", e.getMessage());
            try {
                String errorMessage = truncateErrorMessage(e.getMessage());
                if (payment.getId() != null) {
                    payment.setStatus(PaymentStatus.FAILED);
                    payment.setErrorMessage(errorMessage);
                    payment = paymentRepository.save(payment);
                    log.debug("Payment {} updated with FAILED status", payment.getId());
                } else {
                    // Payment wasn't saved yet, save it with FAILED status
                    payment.setStatus(PaymentStatus.FAILED);
                    payment.setErrorMessage(errorMessage);
                    payment = paymentRepository.save(payment);
                    log.debug("Payment {} created with FAILED status", payment.getId());
                }
            } catch (ObjectOptimisticLockingFailureException ex) {
                log.error("Optimistic locking failure while saving payment {} with FAILED status. Retrying...", payment.getId());
                // Retry by reloading the payment and updating
                Payment reloadedPayment = paymentRepository.findById(payment.getId())
                    .orElseThrow(() -> new PaymentException("Payment not found after optimistic lock failure"));
                reloadedPayment.setStatus(PaymentStatus.FAILED);
                reloadedPayment.setErrorMessage(truncateErrorMessage(e.getMessage()));
                payment = paymentRepository.save(reloadedPayment);
                log.debug("Payment {} updated with FAILED status after retry", payment.getId());
            } catch (Exception dbException) {
                log.error("Failed to save payment with FAILED status: {}", dbException.getMessage(), dbException);
                throw new PaymentException("Failed to record payment failure: " + dbException.getMessage(), dbException);
            }
        } catch (Exception e) {
            // Unexpected error - try to save failure status, but allow rollback if that fails
            log.error("Unexpected error processing payment {}: {}", payment.getId(), e.getMessage(), e);
            try {
                if (payment.getId() != null) {
                    payment.setStatus(PaymentStatus.FAILED);
                    payment.setErrorMessage(truncateErrorMessage("Unexpected error: " + e.getMessage()));
                    payment = paymentRepository.save(payment);
                    log.debug("Payment {} updated with FAILED status after unexpected error", payment.getId());
                } else {
                    // Payment wasn't saved yet, rethrow to allow rollback
                    throw new PaymentException("Failed to create payment: " + e.getMessage(), e);
                }
            } catch (ObjectOptimisticLockingFailureException optLockEx) {
                log.error("Optimistic locking failure while saving payment {} after unexpected error. Retrying...", payment.getId());
                // Retry by reloading the payment and updating
                try {
                    Payment reloadedPayment = paymentRepository.findById(payment.getId())
                        .orElseThrow(() -> new PaymentException("Payment not found after optimistic lock failure"));
                    reloadedPayment.setStatus(PaymentStatus.FAILED);
                    reloadedPayment.setErrorMessage(truncateErrorMessage("Unexpected error: " + e.getMessage()));
                    payment = paymentRepository.save(reloadedPayment);
                    log.debug("Payment {} updated with FAILED status after retry", payment.getId());
                } catch (Exception retryException) {
                    log.error("Failed to retry saving payment {}: {}", payment.getId(), retryException.getMessage(), retryException);
                    throw new PaymentException("Failed to process payment and record failure: " + e.getMessage(), e);
                }
            } catch (Exception dbException) {
                log.error("Failed to save payment {} after unexpected error: {}", payment.getId(), dbException.getMessage(), dbException);
                // If we can't save the failure, rethrow original exception to rollback
                throw new PaymentException("Failed to process payment and record failure: " + e.getMessage(), e);
            }
        }

        return mapToResponse(payment);
    }

    public PaymentResponse getPayment(Long id) {
        Payment payment = paymentRepository.findById(id)
            .orElseThrow(() -> new PaymentException("Payment not found with id: " + id));
        return mapToResponse(payment);
    }

    public List<PaymentResponse> getAllPayments() {
        return paymentRepository.findAll().stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }

    private PaymentResponse mapToResponse(Payment payment) {
        return PaymentResponse.builder()
            .id(payment.getId())
            .version(payment.getVersion())
            .sender(payment.getSender())
            .receiver(payment.getReceiver())
            .amount(payment.getAmount())
            .sourceCurrency(payment.getSourceCurrency())
            .destinationCurrency(payment.getDestinationCurrency())
            .fxRate(payment.getFxRate())
            .payoutAmount(payment.getPayoutAmount())
            .status(payment.getStatus())
            .errorMessage(payment.getErrorMessage())
            .createdAt(payment.getCreatedAt())
            .updatedAt(payment.getUpdatedAt())
            .build();
    }

    /**
     * Truncates error message to prevent database column overflow.
     * Keeps first 4000 characters and appends truncation indicator if needed.
     * 
     * @param errorMessage the original error message
     * @return truncated error message
     */
    private String truncateErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        final int MAX_LENGTH = 4000;
        if (errorMessage.length() <= MAX_LENGTH) {
            return errorMessage;
        }
        return errorMessage.substring(0, MAX_LENGTH - 50) + "... [Error message truncated due to length]";
    }
}

