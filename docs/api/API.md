# SagaFlow API Documentation

## Overview

SagaFlow provides RESTful and gRPC APIs for distributed order processing with Saga orchestration.

---

## Base URLs

- **Orchestrator**: `http://localhost:8080`
- **Order Service**: `http://localhost:8081`
- **Inventory Service**: `http://localhost:8082`
- **Payment Service**: `http://localhost:8083`

---

## Authentication

Currently, the API is open for development. In production, implement JWT-based authentication:

```bash
Authorization: Bearer <your-jwt-token>
```

---

## Orchestrator API

### Create Order (Saga)

Initiates a complete order saga across Order, Inventory, and Payment services.

**Endpoint:** `POST /api/orders`

**Request Body:**
```json
{
  "customerId": "customer-123",
  "items": [
    {
      "productId": "laptop-001",
      "quantity": 2,
      "price": 999.99
    },
    {
      "productId": "mouse-042",
      "quantity": 1,
      "price": 29.99
    }
  ],
  "shippingAddress": {
    "street": "123 Main Street",
    "city": "San Francisco",
    "state": "CA",
    "zipCode": "94105",
    "country": "USA"
  },
  "totalAmount": 2029.97
}
```

**Success Response (201):**
```json
{
  "orderId": "order-abc-123",
  "sagaId": "saga-def-456",
  "status": "COMPLETED",
  "totalAmount": 2029.97,
  "timeline": [
    {
      "step": "ORDER_CREATED",
      "status": "SUCCESS",
      "timestamp": "2024-01-15T10:30:00.123Z",
      "duration": 45
    },
    {
      "step": "INVENTORY_RESERVED",
      "status": "SUCCESS",
      "timestamp": "2024-01-15T10:30:00.234Z",
      "duration": 78
    },
    {
      "step": "PAYMENT_PROCESSED",
      "status": "SUCCESS",
      "timestamp": "2024-01-15T10:30:00.456Z",
      "duration": 132
    }
  ],
  "createdAt": "2024-01-15T10:30:00.123Z"
}
```

**Failure Response (400):**
```json
{
  "sagaId": "saga-def-456",
  "status": "COMPENSATED",
  "error": {
    "code": "INSUFFICIENT_INVENTORY",
    "message": "Product laptop-001 has only 1 unit available",
    "step": "INVENTORY_RESERVED"
  },
  "compensations": [
    {
      "step": "ORDER_CANCELLED",
      "status": "SUCCESS",
      "timestamp": "2024-01-15T10:30:00.789Z"
    }
  ]
}
```

**Example cURL:**
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "customer-123",
    "items": [
      {
        "productId": "laptop-001",
        "quantity": 1,
        "price": 999.99
      }
    ],
    "totalAmount": 999.99
  }'
```

---

### Get Saga Status

**Endpoint:** `GET /api/sagas/{sagaId}`

**Success Response (200):**
```json
{
  "sagaId": "saga-def-456",
  "status": "COMPLETED",
  "currentStep": "PAYMENT_PROCESSED",
  "orderId": "order-abc-123",
  "steps": [
    {
      "name": "ORDER_CREATED",
      "status": "SUCCESS",
      "startedAt": "2024-01-15T10:30:00.123Z",
      "completedAt": "2024-01-15T10:30:00.168Z"
    },
    {
      "name": "INVENTORY_RESERVED",
      "status": "SUCCESS",
      "startedAt": "2024-01-15T10:30:00.234Z",
      "completedAt": "2024-01-15T10:30:00.312Z"
    },
    {
      "name": "PAYMENT_PROCESSED",
      "status": "SUCCESS",
      "startedAt": "2024-01-15T10:30:00.456Z",
      "completedAt": "2024-01-15T10:30:00.588Z"
    }
  ],
  "startedAt": "2024-01-15T10:30:00.123Z",
  "completedAt": "2024-01-15T10:30:00.588Z"
}
```

---

## Order Service API

### Create Order

**Endpoint:** `POST /api/orders`

**Request Body:**
```json
{
  "customerId": "customer-123",
  "items": [
    {
      "productId": "laptop-001",
      "quantity": 1,
      "price": 999.99
    }
  ],
  "totalAmount": 999.99
}
```

**Success Response (201):**
```json
{
  "orderId": "order-abc-123",
  "status": "PENDING",
  "createdAt": "2024-01-15T10:30:00.123Z"
}
```

---

### Get Order

**Endpoint:** `GET /api/orders/{orderId}`

**Success Response (200):**
```json
{
  "orderId": "order-abc-123",
  "customerId": "customer-123",
  "items": [
    {
      "productId": "laptop-001",
      "quantity": 1,
      "price": 999.99
    }
  ],
  "status": "COMPLETED",
  "totalAmount": 999.99,
  "createdAt": "2024-01-15T10:30:00.123Z",
  "updatedAt": "2024-01-15T10:30:01.456Z"
}
```

---

### Cancel Order

**Endpoint:** `DELETE /api/orders/{orderId}`

**Success Response (200):**
```json
{
  "orderId": "order-abc-123",
  "status": "CANCELLED",
  "cancelledAt": "2024-01-15T10:35:00.123Z"
}
```

---

## Inventory Service API

### Reserve Inventory

**Endpoint:** `POST /api/reservations`

**Request Body:**
```json
{
  "orderId": "order-abc-123",
  "items": [
    {
      "productId": "laptop-001",
      "quantity": 1
    }
  ]
}
```

**Success Response (201):**
```json
{
  "reservationId": "rsv-xyz-789",
  "status": "CONFIRMED",
  "expiresAt": "2024-01-15T10:45:00.123Z"
}
```

---

### Get Available Stock

**Endpoint:** `GET /api/inventory/{productId}`

**Success Response (200):**
```json
{
  "productId": "laptop-001",
  "availableQuantity": 47,
  "reservedQuantity": 3,
  "totalQuantity": 50
}
```

---

### Release Reservation

**Endpoint:** `DELETE /api/reservations/{reservationId}`

**Success Response (200):**
```json
{
  "reservationId": "rsv-xyz-789",
  "status": "RELEASED",
  "releasedAt": "2024-01-15T10:35:00.123Z"
}
```

---

## Payment Service API

### Process Payment

**Endpoint:** `POST /api/payments`

**Request Body:**
```json
{
  "orderId": "order-abc-123",
  "amount": 999.99,
  "currency": "USD",
  "paymentMethod": {
    "type": "CREDIT_CARD",
    "lastFourDigits": "4242"
  }
}
```

**Success Response (201):**
```json
{
  "paymentId": "pay-123-abc",
  "status": "COMPLETED",
  "transactionId": "txn_9876543210",
  "processedAt": "2024-01-15T10:30:00.456Z"
}
```

---

### Refund Payment

**Endpoint:** `POST /api/payments/{paymentId}/refund`

**Request Body:**
```json
{
  "reason": "Order cancelled by customer"
}
```

**Success Response (200):**
```json
{
  "paymentId": "pay-123-abc",
  "status": "REFUNDED",
  "refundTransactionId": "rfnd_1234567890",
  "refundedAt": "2024-01-15T10:35:00.123Z"
}
```

---

## gRPC API

For high-performance inter-service communication, use the gRPC endpoints.

### Proto Definition

```protobuf
syntax = "proto3";

service SagaOrchestrator {
  rpc ExecuteSaga(ExecuteSagaRequest) returns (ExecuteSagaResponse);
  rpc GetSagaStatus(GetSagaStatusRequest) returns (GetSagaStatusResponse);
}
```

### Example Client (Java)

```java
// Create channel
ManagedChannel channel = ManagedChannelBuilder
    .forAddress("localhost", 8080)
    .usePlaintext()
    .build();

// Create stub
SagaOrchestratorGrpc.SagaOrchestratorBlockingStub stub = 
    SagaOrchestratorGrpc.newBlockingStub(channel);

// Execute saga
ExecuteSagaRequest request = ExecuteSagaRequest.newBuilder()
    .setSagaType("CREATE_ORDER")
    .setOrderRequest(/* ... */)
    .build();

ExecuteSagaResponse response = stub.executeSaga(request);
```

---

## Error Codes

| Code | Description |
|------|-------------|
| `INSUFFICIENT_INVENTORY` | Requested quantity not available |
| `PAYMENT_DECLINED` | Payment gateway declined the transaction |
| `PAYMENT_GATEWAY_ERROR` | External payment service error |
| `ORDER_NOT_FOUND` | Order ID does not exist |
| `INVALID_RESERVATION` | Reservation expired or invalid |
| `SERVICE_UNAVAILABLE` | Service temporarily down (circuit breaker) |
| `SAGA_TIMEOUT` | Saga execution exceeded time limit |
| `COMPENSTION_FAILED` | Unable to rollback transaction |

---

## Rate Limits

- **Order Service**: 500 requests/second
- **Inventory Service**: 1000 requests/second
- **Payment Service**: 200 requests/second

Exceeding rate limits returns HTTP 429:

```json
{
  "error": "RATE_LIMIT_EXCEEDED",
  "message": "Too many requests",
  "retryAfter": 5
}
```

---

## Webhooks (Future)

Subscribe to saga events:

```bash
POST /api/webhooks
{
  "url": "https://your-app.com/webhooks/sagaflow",
  "events": ["saga.completed", "saga.failed", "saga.compensated"]
}
```

---

## SDKs

**Java:**
```xml
<dependency>
    <groupId>com.sagaflow</groupId>
    <artifactId>sagaflow-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Python:**
```bash
pip install sagaflow-client
```

**Node.js:**
```bash
npm install @sagaflow/client
```

---

## Postman Collection

Import the Postman collection for easy testing:

```bash
curl -o sagaflow.postman_collection.json \
  https://raw.githubusercontent.com/yourusername/sagaflow/main/docs/api/sagaflow.postman_collection.json
```

---

## GraphQL (Future)

Coming soon: GraphQL gateway for flexible querying:

```graphql
query {
  order(id: "order-abc-123") {
    orderId
    status
    saga {
      sagaId
      status
      steps {
        name
        status
        duration
      }
    }
    items {
      productId
      quantity
      price
    }
  }
}
```
