#!/bin/bash

# SagaFlow - Service Startup Script
# Starts all microservices in the correct order

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "╔════════════════════════════════════════════════════════════╗"
echo "║              SagaFlow Microservices Startup                ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Check prerequisites
check_prerequisites() {
    echo -e "${BLUE}Checking prerequisites...${NC}"
    
    if ! command -v java &> /dev/null; then
        echo -e "${RED}❌ Java not found. Please install Java 17+${NC}"
        exit 1
    fi
    
    if ! command -v mvn &> /dev/null; then
        echo -e "${RED}❌ Maven not found. Please install Maven 3.8+${NC}"
        exit 1
    fi
    
    if ! command -v docker &> /dev/null; then
        echo -e "${RED}❌ Docker not found. Please install Docker${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ All prerequisites met${NC}"
    echo ""
}

# Start infrastructure services
start_infrastructure() {
    echo -e "${BLUE}Starting infrastructure services...${NC}"
    cd "$PROJECT_ROOT/infrastructure"
    
    docker-compose up -d
    
    echo -e "${YELLOW}⏳ Waiting for services to be ready...${NC}"
    
    # Wait for PostgreSQL
    echo -n "  PostgreSQL: "
    until docker exec sagaflow-postgres-order pg_isready -U order_user &> /dev/null; do
        echo -n "."
        sleep 1
    done
    echo -e " ${GREEN}✓${NC}"
    
    # Wait for Redis
    echo -n "  Redis: "
    until docker exec sagaflow-redis redis-cli ping &> /dev/null; do
        echo -n "."
        sleep 1
    done
    echo -e " ${GREEN}✓${NC}"
    
    # Wait for Kafka
    echo -n "  Kafka: "
    sleep 10  # Kafka takes longer to start
    echo -e " ${GREEN}✓${NC}"
    
    echo ""
}

# Build all services
build_services() {
    echo -e "${BLUE}Building all services...${NC}"
    cd "$PROJECT_ROOT"
    
    mvn clean install -DskipTests
    
    echo -e "${GREEN}✓ Build completed${NC}"
    echo ""
}

# Start a microservice
start_service() {
    local service_name=$1
    local service_port=$2
    local service_dir=$3
    
    echo -e "${BLUE}Starting $service_name on port $service_port...${NC}"
    
    cd "$PROJECT_ROOT/$service_dir"
    
    # Start service in background
    nohup mvn spring-boot:run \
        -Dspring-boot.run.arguments="--server.port=$service_port" \
        > "$PROJECT_ROOT/logs/${service_name}.log" 2>&1 &
    
    local pid=$!
    echo $pid > "$PROJECT_ROOT/logs/${service_name}.pid"
    
    # Wait for service to be ready
    echo -n "  Waiting for health check: "
    local max_wait=60
    local wait_count=0
    
    while [ $wait_count -lt $max_wait ]; do
        if curl -s "http://localhost:$service_port/actuator/health" > /dev/null 2>&1; then
            echo -e " ${GREEN}✓${NC}"
            return 0
        fi
        echo -n "."
        sleep 2
        wait_count=$((wait_count + 2))
    done
    
    echo -e " ${RED}✗ (timeout)${NC}"
    return 1
}

# Create logs directory
mkdir -p "$PROJECT_ROOT/logs"

# Main execution
check_prerequisites

# Start infrastructure
if [ "$1" != "--skip-infra" ]; then
    start_infrastructure
fi

# Build services
if [ "$1" != "--skip-build" ]; then
    build_services
fi

# Start microservices in order
start_service "orchestrator" 8080 "services/orchestrator"
start_service "order-service" 8081 "services/order-service"
start_service "inventory-service" 8082 "services/inventory-service"
start_service "payment-service" 8083 "services/payment-service"

echo ""
echo "╔════════════════════════════════════════════════════════════╗"
echo "║            All Services Started Successfully!              ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
echo -e "${GREEN}Service Endpoints:${NC}"
echo "  • Orchestrator:        http://localhost:8080"
echo "  • Order Service:       http://localhost:8081"
echo "  • Inventory Service:   http://localhost:8082"
echo "  • Payment Service:     http://localhost:8083"
echo ""
echo -e "${GREEN}Infrastructure:${NC}"
echo "  • Prometheus:          http://localhost:9090"
echo "  • Grafana:             http://localhost:3000 (admin/admin)"
echo "  • Jaeger:              http://localhost:16686"
echo "  • Kafka UI:            http://localhost:8081"
echo ""
echo -e "${YELLOW}Logs:${NC}"
echo "  • tail -f $PROJECT_ROOT/logs/*.log"
echo ""
echo -e "${YELLOW}To stop all services:${NC}"
echo "  • ./scripts/stop-services.sh"
echo ""
