#!/bin/bash

# SagaFlow - Service Shutdown Script

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "╔════════════════════════════════════════════════════════════╗"
echo "║             SagaFlow Microservices Shutdown                ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Stop a service by PID file
stop_service() {
    local service_name=$1
    local pid_file="$PROJECT_ROOT/logs/${service_name}.pid"
    
    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file")
        echo -n "Stopping $service_name (PID: $pid)... "
        
        if kill -0 $pid 2>/dev/null; then
            kill $pid
            
            # Wait for graceful shutdown
            local wait_count=0
            while kill -0 $pid 2>/dev/null && [ $wait_count -lt 30 ]; do
                sleep 1
                wait_count=$((wait_count + 1))
            done
            
            # Force kill if still running
            if kill -0 $pid 2>/dev/null; then
                echo -e "${YELLOW}(force killing)${NC}"
                kill -9 $pid
            else
                echo -e "${GREEN}✓${NC}"
            fi
        else
            echo -e "${YELLOW}(not running)${NC}"
        fi
        
        rm -f "$pid_file"
    else
        echo -e "$service_name: ${YELLOW}(no PID file found)${NC}"
    fi
}

# Stop microservices
echo -e "${YELLOW}Stopping microservices...${NC}"
stop_service "payment-service"
stop_service "inventory-service"
stop_service "order-service"
stop_service "orchestrator"

echo ""

# Stop infrastructure
if [ "$1" != "--keep-infra" ]; then
    echo -e "${YELLOW}Stopping infrastructure services...${NC}"
    cd "$PROJECT_ROOT/infrastructure"
    docker-compose down
    echo -e "${GREEN}✓ Infrastructure stopped${NC}"
else
    echo -e "${YELLOW}Keeping infrastructure running (--keep-infra flag)${NC}"
fi

echo ""
echo -e "${GREEN}All services stopped successfully!${NC}"
echo ""
