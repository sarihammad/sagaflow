package com.sagaflow.order;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Order Service - Manages order lifecycle
 * 
 * Features:
 * - Transactional outbox pattern for reliable event publishing
 * - Redis caching for read optimization
 * - Circuit breaker for resilience
 * - Bulkhead for resource isolation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final RedisTemplate<String, Order> redisTemplate;
    private final MeterRegistry meterRegistry;
    
    private static final String CACHE_PREFIX = "order:";
    private static final long CACHE_TTL_SECONDS = 300; // 5 minutes
    
    /**
     * Create order with outbox pattern
     * 
     * Both order creation and event insertion happen in the same transaction,
     * guaranteeing at-least-once event delivery
     */
    @Transactional
    @CircuitBreaker(name = "orderService", fallbackMethod = "createOrderFallback")
    @Bulkhead(name = "orderService", type = Bulkhead.Type.THREADPOOL)
    public String createOrder(CreateOrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        
        log.info("Creating order {} for customer {}", orderId, request.getCustomerId());
        
        // Step 1: Create order entity
        Order order = Order.builder()
                .orderId(orderId)
                .customerId(request.getCustomerId())
                .items(request.getItemsList())
                .totalAmount(request.getTotalAmount())
                .status(OrderStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        
        // Step 2: Persist order to database
        orderRepository.save(order);
        
        // Step 3: Insert event into outbox table (same transaction!)
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("Order")
                .aggregateId(orderId)
                .eventType("OrderCreated")
                .payload(serializeOrder(order))
                .createdAt(now)
                .processed(false)
                .build();
        
        outboxEventRepository.save(outboxEvent);
        
        // Step 4: Cache the order for fast reads
        cacheOrder(order);
        
        // Metrics
        meterRegistry.counter("orders.created.total").increment();
        
        log.info("Order {} created successfully", orderId);
        
        return orderId;
    }
    
    /**
     * Get order with Redis cache lookup
     */
    @CircuitBreaker(name = "orderService")
    public Order getOrder(String orderId) {
        // Try cache first
        String cacheKey = CACHE_PREFIX + orderId;
        Order cachedOrder = redisTemplate.opsForValue().get(cacheKey);
        
        if (cachedOrder != null) {
            log.debug("Cache hit for order {}", orderId);
            meterRegistry.counter("orders.cache.hits").increment();
            return cachedOrder;
        }
        
        // Cache miss - fetch from database
        log.debug("Cache miss for order {}", orderId);
        meterRegistry.counter("orders.cache.misses").increment();
        
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        
        // Populate cache
        cacheOrder(order);
        
        return order;
    }
    
    /**
     * Cancel order (compensating transaction)
     */
    @Transactional
    @CircuitBreaker(name = "orderService")
    public void cancelOrder(String orderId) {
        log.info("Cancelling order {}", orderId);
        
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
        
        // Publish cancellation event via outbox
        OutboxEvent event = OutboxEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .aggregateType("Order")
                .aggregateId(orderId)
                .eventType("OrderCancelled")
                .payload(serializeOrder(order))
                .createdAt(Instant.now())
                .processed(false)
                .build();
        
        outboxEventRepository.save(event);
        
        // Invalidate cache
        redisTemplate.delete(CACHE_PREFIX + orderId);
        
        meterRegistry.counter("orders.cancelled.total").increment();
        
        log.info("Order {} cancelled", orderId);
    }
    
    /**
     * Update order status
     */
    @Transactional
    public void updateOrderStatus(String orderId, OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        
        order.setStatus(status);
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
        
        // Update cache
        cacheOrder(order);
        
        log.info("Order {} status updated to {}", orderId, status);
    }
    
    private void cacheOrder(Order order) {
        String cacheKey = CACHE_PREFIX + order.getOrderId();
        redisTemplate.opsForValue().set(cacheKey, order, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    }
    
    private String serializeOrder(Order order) {
        // Serialize order to JSON for event payload
        // Implementation depends on your JSON library (Jackson, Gson, etc.)
        return "{}"; // Placeholder
    }
    
    /**
     * Fallback method for circuit breaker
     */
    private String createOrderFallback(CreateOrderRequest request, Exception e) {
        log.error("Order creation failed, circuit breaker activated", e);
        meterRegistry.counter("orders.circuit_breaker.triggered").increment();
        throw new ServiceUnavailableException("Order service temporarily unavailable");
    }
}

/**
 * Background process to relay outbox events to Kafka
 * 
 * Runs periodically to ensure eventual consistency
 */
@Slf4j
@Service
@RequiredArgsConstructor
class OutboxRelay {
    
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    
    @Scheduled(fixedDelay = 1000) // Every second
    @Transactional
    public void relayEvents() {
        List<OutboxEvent> unprocessedEvents = outboxEventRepository
                .findByProcessedFalseOrderByCreatedAtAsc();
        
        if (unprocessedEvents.isEmpty()) {
            return;
        }
        
        log.debug("Relaying {} outbox events", unprocessedEvents.size());
        
        for (OutboxEvent event : unprocessedEvents) {
            try {
                // Publish to Kafka
                kafkaTemplate.send("order-events", event.getAggregateId(), event.getPayload())
                        .addCallback(
                                success -> markEventProcessed(event),
                                failure -> handlePublishFailure(event, failure)
                        );
                
                meterRegistry.counter("outbox.events.published.total").increment();
                
            } catch (Exception e) {
                log.error("Failed to relay event {}", event.getEventId(), e);
                meterRegistry.counter("outbox.events.failed.total").increment();
            }
        }
    }
    
    @Transactional
    private void markEventProcessed(OutboxEvent event) {
        event.setProcessed(true);
        event.setProcessedAt(Instant.now());
        outboxEventRepository.save(event);
        log.debug("Event {} processed successfully", event.getEventId());
    }
    
    private void handlePublishFailure(OutboxEvent event, Throwable failure) {
        log.error("Failed to publish event {} to Kafka", event.getEventId(), failure);
        // Event remains unprocessed and will be retried on next relay cycle
    }
}
