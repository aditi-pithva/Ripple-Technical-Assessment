package com.ripple.payment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private Long id;
    private Long version;
    private String sender;
    private String receiver;
    private BigDecimal amount;
    private String sourceCurrency;
    private String destinationCurrency;
    private BigDecimal fxRate;
    private BigDecimal payoutAmount;
    private PaymentStatus status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

