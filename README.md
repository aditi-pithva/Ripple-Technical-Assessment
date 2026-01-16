# Ripple Payment Service

A cross-currency payment service built with Spring Boot that processes payments between different currencies using an external FX service.

## Features

- **Process Payments**: Accept payment requests with sender, receiver, amount, and currency pair
- **FX Rate Integration**: Automatically fetch conversion rates from external FX service
- **Circuit Breaker**: Resilience4j circuit breaker with fallback method implementation
- **Retry Logic**: Resilience4j retry with exponential backoff (max 3 attempts)
- **Timeout Protection**: Resilience4j timeout configuration (15s timeout duration)
- **Payment Retrieval**: Query payment details including status, payout amount, and error messages
- **Error Handling**: Comprehensive error handling with meaningful error messages
- **Data Persistence**: Stores all payment requests and their status in H2 database
- **Thread-Safe**: Fully thread-safe implementation with optimistic locking

## Technology Stack

- Java 11
- Spring Boot 2.7.18
- Spring Data JPA
- H2 Database (in-memory)
- Resilience4j (Circuit Breaker, Retry, Timeout)
- Spring Retry (legacy support)
- Maven

## Prerequisites

- Java 11 or higher
- Maven 3.6+

## Building the Project

```bash
mvn clean install
```

## Running the Application

```bash
mvn spring-boot:run
```

The service will start on `http://localhost:8080`

## Code Structure

The project follows a standard Spring Boot layered architecture:

```
src/main/java/com/ripple/payment/
├── config/              # Configuration classes
│   ├── FxServiceConfig.java          # FX service configuration
│   ├── Resilience4jConfig.java       # Circuit breaker, retry, timeout config
│   └── RestTemplateConfig.java       # HTTP client configuration
├── controller/          # REST API endpoints
│   └── PaymentController.java        # Payment API endpoints
├── exception/           # Exception handling
│   ├── GlobalExceptionHandler.java   # Global exception handler
│   └── PaymentException.java         # Custom payment exception
├── model/               # Data models
│   ├── Payment.java                  # Payment entity (JPA)
│   ├── PaymentRequest.java           # Payment request DTO
│   ├── PaymentResponse.java          # Payment response DTO
│   └── PaymentStatus.java            # Payment status enum
├── repository/          # Data access layer
│   └── PaymentRepository.java        # JPA repository for payments
├── service/             # Business logic
│   ├── PaymentService.java           # Payment processing service
│   └── FxServiceClient.java         # FX service client
└── PaymentServiceApplication.java    # Main application class
```

## API Endpoints

### 1. Process a Payment
**POST** `/api/payments`

Creates and processes a new payment with currency conversion.

### 2. Retrieve a Payment
**GET** `/api/payments/{id}`

Retrieves payment details by ID.

### 3. Get All Payments
**GET** `/api/payments`

Retrieves a list of all payments.

## Configuration

FX service configuration can be adjusted in `application.properties`:

### Circuit Breaker

- Implemented using Resilience4j with `@CircuitBreaker` annotation on `FxServiceClient.getFxRate()`
- Fallback method `getFxRateFallback()` handles circuit breaker OPEN state
- Configured with sliding window size of 10, minimum 5 calls, 50% failure threshold, 10s wait duration in open state

## Error Handling

The service handles various error scenarios:

- **Invalid Input**: Returns 400 with validation errors
- **FX Service Unavailable**: Payment marked as FAILED with error message
- **Invalid FX Response**: Payment marked as FAILED with diagnostic information
- **Payment Not Found**: Returns 400 with error message

## Testing

Run all tests:
```bash
mvn test
```

## Design Decisions

1. **Retry Mechanism**: Implemented Spring Retry to handle transient FX service failures
2. **Timeout Configuration**: Configurable timeouts to handle slow FX service responses
3. **Payment Status**: Three states - PENDING, SUCCEEDED, FAILED
4. **Error Storage**: Error messages stored with payment for diagnostic purposes
5. **In-Memory Database**: H2 database for simplicity

## AI Usage

This project was developed with AI assistance for:
- Test case creation
- Documentation generation
- Error handling patterns
- Best practices implementation

