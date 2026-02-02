package com.sagaflow.orchestrator;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Saga Orchestrator - Coordinates distributed transactions across microservices
 * 
 * Implements the Saga pattern with:
 * - Forward recovery (retry transient failures)
 * - Backward recovery (compensating transactions)
 * - State persistence for crash recovery
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaOrchestrator {
    
    private final OrderServiceClient orderServiceClient;
    private final InventoryServiceClient inventoryServiceClient;
    private final PaymentServiceClient paymentServiceClient;
    private final SagaStateRepository sagaStateRepository;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    
    // Metrics
    private final Counter sagaAttempts;
    private final Counter sagaSuccesses;
    private final Counter sagaFailures;
    private final Timer sagaDuration;
    
    public SagaOrchestrator(
            OrderServiceClient orderServiceClient,
            InventoryServiceClient inventoryServiceClient,
            PaymentServiceClient paymentServiceClient,
            SagaStateRepository sagaStateRepository,
            MeterRegistry meterRegistry,
            Tracer tracer) {
        
        this.orderServiceClient = orderServiceClient;
        this.inventoryServiceClient = inventoryServiceClient;
        this.paymentServiceClient = paymentServiceClient;
        this.sagaStateRepository = sagaStateRepository;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        
        // Initialize metrics
        this.sagaAttempts = Counter.builder("saga.attempts.total")
                .description("Total saga attempts")
                .register(meterRegistry);
        
        this.sagaSuccesses = Counter.builder("saga.successes.total")
                .description("Successful saga completions")
                .register(meterRegistry);
        
        this.sagaFailures = Counter.builder("saga.failures.total")
                .description("Failed saga executions")
                .register(meterRegistry);
        
        this.sagaDuration = Timer.builder("saga.duration")
                .description("Saga execution duration")
                .register(meterRegistry);
    }
    
    /**
     * Execute order creation saga
     * 
     * Steps:
     * 1. Create order
     * 2. Reserve inventory
     * 3. Process payment
     * 
     * If any step fails, compensating transactions are executed in reverse order
     */
    public CompletableFuture<CreateOrderResponse> createOrder(CreateOrderRequest request) {
        Span span = tracer.spanBuilder("saga.createOrder").startSpan();
        String sagaId = UUID.randomUUID().toString();
        
        log.info("Starting saga {} for customer {}", sagaId, request.getCustomerId());
        sagaAttempts.increment();
        
        return Timer.resource(meterRegistry, "saga.duration")
                .wrap(() -> executeSaga(sagaId, request, span))
                .exceptionally(error -> {
                    log.error("Saga {} failed: {}", sagaId, error.getMessage());
                    sagaFailures.increment();
                    span.recordException(error);
                    span.end();
                    throw new SagaException("Order creation failed", error);
                })
                .thenApply(response -> {
                    sagaSuccesses.increment();
                    span.end();
                    return response;
                });
    }
    
    private CompletableFuture<CreateOrderResponse> executeSaga(
            String sagaId, 
            CreateOrderRequest request,
            Span span) {
        
        SagaState sagaState = new SagaState(sagaId, SagaStatus.STARTED);
        sagaStateRepository.save(sagaState);
        
        // Step 1: Create Order
        return createOrderStep(sagaId, request, span)
                .thenCompose(orderId -> {
                    sagaState.setOrderId(orderId);
                    sagaState.setCurrentStep(SagaStep.ORDER_CREATED);
                    sagaStateRepository.save(sagaState);
                    
                    // Step 2: Reserve Inventory
                    return reserveInventoryStep(sagaId, orderId, request, span);
                })
                .thenCompose(reservationId -> {
                    sagaState.setReservationId(reservationId);
                    sagaState.setCurrentStep(SagaStep.INVENTORY_RESERVED);
                    sagaStateRepository.save(sagaState);
                    
                    // Step 3: Process Payment
                    return processPaymentStep(sagaId, sagaState.getOrderId(), request, span);
                })
                .thenApply(paymentId -> {
                    sagaState.setPaymentId(paymentId);
                    sagaState.setCurrentStep(SagaStep.PAYMENT_PROCESSED);
                    sagaState.setStatus(SagaStatus.COMPLETED);
                    sagaStateRepository.save(sagaState);
                    
                    log.info("Saga {} completed successfully", sagaId);
                    
                    return CreateOrderResponse.newBuilder()
                            .setOrderId(sagaState.getOrderId())
                            .setSagaId(sagaId)
                            .setStatus("COMPLETED")
                            .build();
                })
                .exceptionally(error -> {
                    log.error("Saga {} failed at step {}: {}", 
                            sagaId, sagaState.getCurrentStep(), error.getMessage());
                    
                    // Execute compensating transactions
                    compensate(sagaState).join();
                    
                    throw new SagaException("Saga failed and compensated", error);
                });
    }
    
    private CompletableFuture<String> createOrderStep(
            String sagaId, 
            CreateOrderRequest request,
            Span span) {
        
        Span stepSpan = tracer.spanBuilder("saga.step.createOrder")
                .setParent(io.opentelemetry.context.Context.current().with(span))
                .startSpan();
        
        log.info("Saga {}: Creating order", sagaId);
        
        return orderServiceClient.createOrder(request)
                .thenApply(orderId -> {
                    log.info("Saga {}: Order {} created", sagaId, orderId);
                    stepSpan.setAttribute("order.id", orderId);
                    stepSpan.end();
                    return orderId;
                })
                .exceptionally(error -> {
                    stepSpan.recordException(error);
                    stepSpan.end();
                    throw new SagaStepException("Failed to create order", error);
                });
    }
    
    private CompletableFuture<String> reserveInventoryStep(
            String sagaId,
            String orderId,
            CreateOrderRequest request,
            Span span) {
        
        Span stepSpan = tracer.spanBuilder("saga.step.reserveInventory")
                .setParent(io.opentelemetry.context.Context.current().with(span))
                .startSpan();
        
        log.info("Saga {}: Reserving inventory for order {}", sagaId, orderId);
        
        return inventoryServiceClient.reserveInventory(orderId, request.getItemsList())
                .thenApply(reservationId -> {
                    log.info("Saga {}: Inventory reserved with ID {}", sagaId, reservationId);
                    stepSpan.setAttribute("reservation.id", reservationId);
                    stepSpan.end();
                    return reservationId;
                })
                .exceptionally(error -> {
                    stepSpan.recordException(error);
                    stepSpan.end();
                    throw new SagaStepException("Failed to reserve inventory", error);
                });
    }
    
    private CompletableFuture<String> processPaymentStep(
            String sagaId,
            String orderId,
            CreateOrderRequest request,
            Span span) {
        
        Span stepSpan = tracer.spanBuilder("saga.step.processPayment")
                .setParent(io.opentelemetry.context.Context.current().with(span))
                .startSpan();
        
        log.info("Saga {}: Processing payment for order {}", sagaId, orderId);
        
        return paymentServiceClient.processPayment(orderId, request.getTotalAmount())
                .thenApply(paymentId -> {
                    log.info("Saga {}: Payment processed with ID {}", sagaId, paymentId);
                    stepSpan.setAttribute("payment.id", paymentId);
                    stepSpan.end();
                    return paymentId;
                })
                .exceptionally(error -> {
                    stepSpan.recordException(error);
                    stepSpan.end();
                    throw new SagaStepException("Failed to process payment", error);
                });
    }
    
    /**
     * Execute compensating transactions in reverse order
     */
    private CompletableFuture<Void> compensate(SagaState sagaState) {
        log.warn("Saga {}: Starting compensation from step {}", 
                sagaState.getSagaId(), sagaState.getCurrentStep());
        
        sagaState.setStatus(SagaStatus.COMPENSATING);
        sagaStateRepository.save(sagaState);
        
        CompletableFuture<Void> compensation = CompletableFuture.completedFuture(null);
        
        // Compensate in reverse order
        if (sagaState.getCurrentStep().ordinal() >= SagaStep.PAYMENT_PROCESSED.ordinal()) {
            compensation = compensation.thenCompose(v -> refundPayment(sagaState));
        }
        
        if (sagaState.getCurrentStep().ordinal() >= SagaStep.INVENTORY_RESERVED.ordinal()) {
            compensation = compensation.thenCompose(v -> releaseInventory(sagaState));
        }
        
        if (sagaState.getCurrentStep().ordinal() >= SagaStep.ORDER_CREATED.ordinal()) {
            compensation = compensation.thenCompose(v -> cancelOrder(sagaState));
        }
        
        return compensation.thenRun(() -> {
            sagaState.setStatus(SagaStatus.COMPENSATED);
            sagaStateRepository.save(sagaState);
            log.info("Saga {}: Compensation completed", sagaState.getSagaId());
        });
    }
    
    private CompletableFuture<Void> refundPayment(SagaState sagaState) {
        log.info("Saga {}: Refunding payment {}", 
                sagaState.getSagaId(), sagaState.getPaymentId());
        
        return paymentServiceClient.refundPayment(sagaState.getPaymentId())
                .thenRun(() -> log.info("Saga {}: Payment refunded", sagaState.getSagaId()));
    }
    
    private CompletableFuture<Void> releaseInventory(SagaState sagaState) {
        log.info("Saga {}: Releasing inventory reservation {}", 
                sagaState.getSagaId(), sagaState.getReservationId());
        
        return inventoryServiceClient.releaseReservation(sagaState.getReservationId())
                .thenRun(() -> log.info("Saga {}: Inventory released", sagaState.getSagaId()));
    }
    
    private CompletableFuture<Void> cancelOrder(SagaState sagaState) {
        log.info("Saga {}: Cancelling order {}", 
                sagaState.getSagaId(), sagaState.getOrderId());
        
        return orderServiceClient.cancelOrder(sagaState.getOrderId())
                .thenRun(() -> log.info("Saga {}: Order cancelled", sagaState.getSagaId()));
    }
}
