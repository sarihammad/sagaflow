package com.sagaflow.payment;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payment Service - Handles payment processing and refunds
 * 
 * Features:
 * - Idempotency for safe retries
 * - Circuit breaker for payment gateway failures
 * - Bulkhead pattern for thread isolation
 * - Compensating transactions (refunds)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    
    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentGatewayClient paymentGatewayClient;
    private final MeterRegistry meterRegistry;
    
    /**
     * Process payment with retry and circuit breaker
     * 
     * Idempotent: Multiple calls with same orderId return same paymentId
     */
    @Transactional
    @CircuitBreaker(name = "paymentService", fallbackMethod = "processPaymentFallback")
    @Bulkhead(name = "paymentService", type = Bulkhead.Type.THREADPOOL)
    @Retry(name = "paymentService", fallbackMethod = "processPaymentFallback")
    public String processPayment(String orderId, BigDecimal amount) {
        log.info("Processing payment for order {}, amount: {}", orderId, amount);
        
        // Check for existing payment (idempotency)
        Payment existingPayment = paymentRepository.findByOrderId(orderId);
        if (existingPayment != null) {
            if (existingPayment.getStatus() == PaymentStatus.COMPLETED) {
                log.info("Payment already processed for order {}: {}", 
                        orderId, existingPayment.getPaymentId());
                return existingPayment.getPaymentId();
            } else if (existingPayment.getStatus() == PaymentStatus.PENDING) {
                log.warn("Payment already in progress for order {}", orderId);
                throw new PaymentInProgressException("Payment already being processed");
            }
        }
        
        String paymentId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        
        // Create payment record
        Payment payment = Payment.builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .amount(amount)
                .status(PaymentStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        paymentRepository.save(payment);
        
        try {
            // Call external payment gateway
            PaymentGatewayResponse response = paymentGatewayClient.charge(
                    paymentId, 
                    amount,
                    "Order payment: " + orderId
            );
            
            if (!response.isSuccess()) {
                log.error("Payment gateway declined payment {}: {}", 
                        paymentId, response.getErrorMessage());
                
                payment.setStatus(PaymentStatus.FAILED);
                payment.setErrorMessage(response.getErrorMessage());
                payment.setUpdatedAt(Instant.now());
                paymentRepository.save(payment);
                
                meterRegistry.counter("payments.failed.total",
                        "reason", "gateway_declined").increment();
                
                throw new PaymentDeclinedException(response.getErrorMessage());
            }
            
            // Payment successful
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setGatewayTransactionId(response.getTransactionId());
            payment.setProcessedAt(Instant.now());
            payment.setUpdatedAt(Instant.now());
            paymentRepository.save(payment);
            
            // Publish event via outbox
            OutboxEvent event = OutboxEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .aggregateType("Payment")
                    .aggregateId(paymentId)
                    .eventType("PaymentProcessed")
                    .payload(serializePayment(payment))
                    .createdAt(Instant.now())
                    .processed(false)
                    .build();
            
            outboxEventRepository.save(event);
            
            meterRegistry.counter("payments.successful.total").increment();
            meterRegistry.summary("payments.amount").record(amount.doubleValue());
            
            log.info("Payment {} processed successfully", paymentId);
            
            return paymentId;
            
        } catch (PaymentGatewayException e) {
            log.error("Payment gateway error for payment {}", paymentId, e);
            
            payment.setStatus(PaymentStatus.FAILED);
            payment.setErrorMessage(e.getMessage());
            payment.setUpdatedAt(Instant.now());
            paymentRepository.save(payment);
            
            meterRegistry.counter("payments.failed.total",
                    "reason", "gateway_error").increment();
            
            throw new PaymentProcessingException("Payment gateway error", e);
        }
    }
    
    /**
     * Refund payment (compensating transaction)
     * 
     * Idempotent: Multiple refund calls are safe
     */
    @Transactional
    @CircuitBreaker(name = "paymentService", fallbackMethod = "refundPaymentFallback")
    @Retry(name = "paymentService")
    public void refundPayment(String paymentId) {
        log.info("Refunding payment {}", paymentId);
        
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
        
        // Check if already refunded (idempotency)
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment {} already refunded", paymentId);
            return;
        }
        
        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            log.warn("Cannot refund payment {} with status {}", paymentId, payment.getStatus());
            throw new InvalidPaymentStatusException(
                    "Can only refund completed payments. Current status: " + payment.getStatus());
        }
        
        try {
            // Call payment gateway to process refund
            PaymentGatewayResponse response = paymentGatewayClient.refund(
                    payment.getGatewayTransactionId(),
                    payment.getAmount(),
                    "Order cancellation refund"
            );
            
            if (!response.isSuccess()) {
                log.error("Payment gateway declined refund for {}: {}", 
                        paymentId, response.getErrorMessage());
                
                meterRegistry.counter("payments.refund_failed.total").increment();
                
                throw new RefundFailedException(response.getErrorMessage());
            }
            
            // Refund successful
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setRefundTransactionId(response.getTransactionId());
            payment.setRefundedAt(Instant.now());
            payment.setUpdatedAt(Instant.now());
            paymentRepository.save(payment);
            
            // Publish event via outbox
            OutboxEvent event = OutboxEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .aggregateType("Payment")
                    .aggregateId(paymentId)
                    .eventType("PaymentRefunded")
                    .payload(serializePayment(payment))
                    .createdAt(Instant.now())
                    .processed(false)
                    .build();
            
            outboxEventRepository.save(event);
            
            meterRegistry.counter("payments.refunded.total").increment();
            
            log.info("Payment {} refunded successfully", paymentId);
            
        } catch (PaymentGatewayException e) {
            log.error("Payment gateway error during refund for {}", paymentId, e);
            
            meterRegistry.counter("payments.refund_failed.total",
                    "reason", "gateway_error").increment();
            
            throw new RefundFailedException("Payment gateway error", e);
        }
    }
    
    /**
     * Get payment status
     */
    public Payment getPayment(String paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
    }
    
    /**
     * Check if payment can be refunded
     */
    public boolean canRefund(String paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElse(null);
        
        return payment != null && payment.getStatus() == PaymentStatus.COMPLETED;
    }
    
    private String serializePayment(Payment payment) {
        // Serialize to JSON
        return "{}"; // Placeholder
    }
    
    /**
     * Fallback for payment processing failures
     */
    private String processPaymentFallback(String orderId, BigDecimal amount, Exception e) {
        log.error("Payment processing failed, circuit breaker activated for order {}", orderId, e);
        meterRegistry.counter("payments.circuit_breaker.triggered").increment();
        
        // Record the failure
        Payment payment = Payment.builder()
                .paymentId(UUID.randomUUID().toString())
                .orderId(orderId)
                .amount(amount)
                .status(PaymentStatus.FAILED)
                .errorMessage("Service unavailable: " + e.getMessage())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        
        paymentRepository.save(payment);
        
        throw new ServiceUnavailableException("Payment service temporarily unavailable", e);
    }
    
    /**
     * Fallback for refund failures
     */
    private void refundPaymentFallback(String paymentId, Exception e) {
        log.error("Refund failed, circuit breaker activated for payment {}", paymentId, e);
        meterRegistry.counter("payments.refund_circuit_breaker.triggered").increment();
        throw new ServiceUnavailableException("Payment service temporarily unavailable", e);
    }
}

/**
 * Payment Gateway Client - Simulates external payment processor
 * 
 * In production, this would integrate with Stripe, PayPal, etc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PaymentGatewayClient {
    
    private final MeterRegistry meterRegistry;
    
    /**
     * Charge customer (simulated)
     */
    @CircuitBreaker(name = "paymentGateway")
    @Retry(name = "paymentGateway")
    public PaymentGatewayResponse charge(String paymentId, BigDecimal amount, String description) {
        log.info("Calling payment gateway to charge ${} for payment {}", amount, paymentId);
        
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            // Simulate external API call
            simulateNetworkLatency();
            
            // Simulate occasional failures (5% failure rate)
            if (Math.random() < 0.05) {
                log.warn("Payment gateway declined charge for payment {}", paymentId);
                return PaymentGatewayResponse.failure("Insufficient funds");
            }
            
            // Success
            String transactionId = "txn_" + UUID.randomUUID().toString();
            
            log.info("Payment gateway approved charge for payment {}, transaction: {}", 
                    paymentId, transactionId);
            
            return PaymentGatewayResponse.success(transactionId);
            
        } finally {
            sample.stop(Timer.builder("payment_gateway.request.duration")
                    .tag("operation", "charge")
                    .register(meterRegistry));
        }
    }
    
    /**
     * Refund transaction (simulated)
     */
    @CircuitBreaker(name = "paymentGateway")
    @Retry(name = "paymentGateway")
    public PaymentGatewayResponse refund(String transactionId, BigDecimal amount, String reason) {
        log.info("Calling payment gateway to refund ${} for transaction {}", amount, transactionId);
        
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            // Simulate external API call
            simulateNetworkLatency();
            
            // Refunds rarely fail (1% failure rate)
            if (Math.random() < 0.01) {
                log.warn("Payment gateway declined refund for transaction {}", transactionId);
                return PaymentGatewayResponse.failure("Refund window expired");
            }
            
            // Success
            String refundId = "rfnd_" + UUID.randomUUID().toString();
            
            log.info("Payment gateway approved refund for transaction {}, refund: {}", 
                    transactionId, refundId);
            
            return PaymentGatewayResponse.success(refundId);
            
        } finally {
            sample.stop(Timer.builder("payment_gateway.request.duration")
                    .tag("operation", "refund")
                    .register(meterRegistry));
        }
    }
    
    private void simulateNetworkLatency() {
        try {
            // Simulate 50-150ms network latency
            Thread.sleep(50 + (long) (Math.random() * 100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
