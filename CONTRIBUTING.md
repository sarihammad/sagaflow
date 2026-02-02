# Contributing to SagaFlow

Thank you for considering contributing to SagaFlow! This document provides guidelines for contributing to the project.

---

## Code of Conduct

Be respectful, inclusive, and professional in all interactions.

---

## How to Contribute

### Reporting Bugs

1. Check if the bug has already been reported in [Issues](https://github.com/yourusername/sagaflow/issues)
2. If not, create a new issue with:
   - Clear, descriptive title
   - Steps to reproduce
   - Expected vs actual behavior
   - Environment details (OS, Java version, etc.)
   - Logs/screenshots if applicable

### Suggesting Features

1. Check [existing feature requests](https://github.com/yourusername/sagaflow/issues?q=is%3Aissue+is%3Aopen+label%3Aenhancement)
2. Create a new issue with:
   - Clear use case
   - Proposed solution
   - Alternatives considered
   - Impact on existing functionality

### Pull Requests

1. **Fork the repository**
2. **Create a feature branch**
   ```bash
   git checkout -b feature/amazing-feature
   ```

3. **Make your changes**
   - Follow code style guidelines
   - Add tests for new functionality
   - Update documentation

4. **Run tests**
   ```bash
   mvn clean verify
   ```

5. **Commit with clear messages**
   ```bash
   git commit -m "feat: add saga timeout configuration"
   ```

6. **Push to your fork**
   ```bash
   git push origin feature/amazing-feature
   ```

7. **Open a Pull Request**
   - Link related issues
   - Describe changes in detail
   - Include test results
   - Add screenshots for UI changes

---

## Development Setup

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose
- 8GB+ RAM

### Getting Started

```bash
# Clone your fork
git clone https://github.com/YOUR_USERNAME/sagaflow.git
cd sagaflow

# Start infrastructure
docker-compose -f infrastructure/docker-compose.yml up -d

# Build project
mvn clean install

# Run tests
mvn test
```

---

## Code Style

### Java

Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html):

```java
// Good
public class OrderService {
    private static final int MAX_RETRIES = 3;
    
    public String createOrder(CreateOrderRequest request) {
        // Implementation
    }
}

// Bad
public class orderService {
    private static final int max_retries = 3;
    
    public String CreateOrder(CreateOrderRequest request) {
        // Implementation
    }
}
```

### Naming Conventions

- **Classes**: PascalCase (e.g., `SagaOrchestrator`)
- **Methods**: camelCase (e.g., `createOrder`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `MAX_RETRIES`)
- **Packages**: lowercase (e.g., `com.sagaflow.order`)

### Logging

```java
// Use SLF4J with appropriate levels
log.debug("Processing order {}", orderId);      // Detailed info
log.info("Order {} created successfully", orderId);  // Important events
log.warn("Inventory low for product {}", productId); // Warnings
log.error("Failed to process payment", exception);   // Errors
```

---

## Testing Guidelines

### Unit Tests

- Test individual components in isolation
- Mock external dependencies
- Aim for 80%+ code coverage

```java
@Test
void shouldCreateOrderSuccessfully() {
    // Given
    CreateOrderRequest request = createTestRequest();
    when(orderRepository.save(any())).thenReturn(order);
    
    // When
    String orderId = orderService.createOrder(request);
    
    // Then
    assertNotNull(orderId);
    verify(orderRepository).save(any());
}
```

### Integration Tests

- Test service interactions
- Use Testcontainers for real dependencies

```java
@SpringBootTest
@Testcontainers
class OrderServiceIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
    
    @Test
    void shouldPersistOrderToDatabase() {
        // Test with real database
    }
}
```

### Load Tests

Run load tests before submitting performance PRs:

```bash
./scripts/load-test.sh
```

---

## Documentation

### Code Comments

```java
/**
 * Executes a Saga transaction across multiple services.
 * 
 * @param request The order creation request
 * @return CompletableFuture containing the order response
 * @throws SagaException if any step fails after all retries
 */
public CompletableFuture<CreateOrderResponse> createOrder(CreateOrderRequest request) {
    // Implementation
}
```

### README Updates

Update relevant documentation when:
- Adding new features
- Changing APIs
- Modifying configuration
- Adding dependencies

---

## Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation
- `style`: Code style changes
- `refactor`: Code refactoring
- `perf`: Performance improvements
- `test`: Adding tests
- `chore`: Maintenance

**Examples:**
```
feat(saga): add timeout configuration for saga execution

fix(inventory): resolve race condition in reservation
```

---

## Pull Request Checklist

Before submitting your PR, ensure:

- [ ] Code follows style guidelines
- [ ] Tests added/updated and passing
- [ ] Documentation updated
- [ ] Commit messages follow conventions
- [ ] No merge conflicts
- [ ] CI/CD pipeline passes
- [ ] Performance impact considered
- [ ] Breaking changes documented

---

## Architecture Decisions

For significant changes, document your decision:

1. Create an ADR (Architecture Decision Record)
2. Place in `docs/architecture/adr/`
3. Use template:

```markdown
# ADR-XXX: Title

## Status
Proposed | Accepted | Deprecated

## Context
What is the issue we're seeing?

## Decision
What did we decide?

## Consequences
What becomes easier or harder?
```

---

## Performance Considerations

When making changes:
- Profile before and after
- Run load tests
- Check metrics in Grafana
- Document performance impact

Targets:
- P95 latency < 250ms
- Error rate < 1%
- Throughput > 1000 req/s

---

## Security

- Never commit secrets/credentials
- Use environment variables
- Follow OWASP guidelines
- Report vulnerabilities privately

---

## Getting Help

- **Questions**: Open a [Discussion](https://github.com/yourusername/sagaflow/discussions)
- **Bugs**: Open an [Issue](https://github.com/yourusername/sagaflow/issues)
- **Chat**: Join our Discord/Slack

---

## Recognition

Contributors will be:
- Listed in CONTRIBUTORS.md
- Mentioned in release notes
- Invited to maintainer team (active contributors)

---

Thank you for making SagaFlow better! 🎉
