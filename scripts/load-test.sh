#!/bin/bash

# SagaFlow - Load Testing Script
# Tests system performance under high load

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "╔════════════════════════════════════════════════════════════╗"
echo "║                SagaFlow Load Testing                       ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Configuration
ORCHESTRATOR_URL="http://localhost:8080"
USERS=${USERS:-1000}
DURATION=${DURATION:-600}  # 10 minutes
RAMP_UP=${RAMP_UP:-60}     # 1 minute ramp-up

echo -e "${BLUE}Test Configuration:${NC}"
echo "  • Concurrent Users: $USERS"
echo "  • Duration: ${DURATION}s ($(($DURATION / 60)) minutes)"
echo "  • Ramp-up: ${RAMP_UP}s"
echo "  • Target: $ORCHESTRATOR_URL"
echo ""

# Check if orchestrator is running
echo -n "Checking orchestrator health... "
if ! curl -sf "$ORCHESTRATOR_URL/actuator/health" > /dev/null; then
    echo -e "${RED}✗${NC}"
    echo "Orchestrator is not running. Please start services first."
    exit 1
fi
echo -e "${GREEN}✓${NC}"
echo ""

# Create JMeter test plan
cat > /tmp/sagaflow-load-test.jmx << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.5">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="SagaFlow Load Test">
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments">
        <collectionProp name="Arguments.arguments"/>
      </elementProp>
    </TestPlan>
    <hashTree>
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="Order Creation Load">
        <intProp name="ThreadGroup.num_threads">${__P(users,1000)}</intProp>
        <intProp name="ThreadGroup.ramp_time">${__P(rampup,60)}</intProp>
        <intProp name="ThreadGroup.duration">${__P(duration,600)}</intProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
      </ThreadGroup>
      <hashTree>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="Create Order">
          <stringProp name="HTTPSampler.domain">localhost</stringProp>
          <stringProp name="HTTPSampler.port">8080</stringProp>
          <stringProp name="HTTPSampler.path">/api/orders</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments">
            <collectionProp name="Arguments.arguments">
              <elementProp name="" elementType="HTTPArgument">
                <stringProp name="Argument.value">{
  "customerId": "customer-${__UUID()}",
  "items": [
    {
      "productId": "prod-${__Random(1,100)}",
      "quantity": ${__Random(1,5)},
      "price": ${__Random(10,1000)}.99
    }
  ],
  "totalAmount": ${__Random(10,1000)}.99
}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
          <stringProp name="HTTPSampler.contentEncoding">UTF-8</stringProp>
          <stringProp name="HTTPSampler.implementation">HttpClient4</stringProp>
        </HTTPSamplerProxy>
        <hashTree>
          <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="HTTP Headers">
            <collectionProp name="HeaderManager.headers">
              <elementProp name="" elementType="Header">
                <stringProp name="Header.name">Content-Type</stringProp>
                <stringProp name="Header.value">application/json</stringProp>
              </elementProp>
            </collectionProp>
          </HeaderManager>
          <hashTree/>
        </hashTree>
      </hashTree>
      <ResultCollector guiclass="SummaryReport" testclass="ResultCollector" testname="Summary Report"/>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
EOF

# Simple HTTP load test using curl (if JMeter not available)
run_simple_load_test() {
    echo -e "${YELLOW}Running simple load test with curl...${NC}"
    echo ""
    
    local start_time=$(date +%s)
    local end_time=$(($start_time + $DURATION))
    local request_count=0
    local success_count=0
    local error_count=0
    
    # Array to store response times
    declare -a response_times
    
    echo -e "${BLUE}Sending requests...${NC}"
    
    while [ $(date +%s) -lt $end_time ]; do
        for i in $(seq 1 10); do
            # Generate random order
            local customer_id="customer-$(uuidgen 2>/dev/null || echo $RANDOM)"
            local product_id=$((RANDOM % 100 + 1))
            local quantity=$((RANDOM % 5 + 1))
            local price=$((RANDOM % 1000 + 10))
            
            local payload="{
                \"customerId\": \"$customer_id\",
                \"items\": [{
                    \"productId\": \"prod-$product_id\",
                    \"quantity\": $quantity,
                    \"price\": $price.99
                }],
                \"totalAmount\": $price.99
            }"
            
            # Send request and capture response time
            local start=$(date +%s%N)
            local response=$(curl -s -w "\n%{http_code}" \
                -X POST "$ORCHESTRATOR_URL/api/orders" \
                -H "Content-Type: application/json" \
                -d "$payload" 2>/dev/null)
            local end=$(date +%s%N)
            
            local http_code=$(echo "$response" | tail -n1)
            local duration=$(( ($end - $start) / 1000000 ))  # Convert to ms
            
            request_count=$((request_count + 1))
            response_times+=($duration)
            
            if [ "$http_code" = "200" ] || [ "$http_code" = "201" ]; then
                success_count=$((success_count + 1))
            else
                error_count=$((error_count + 1))
            fi
            
            # Progress indicator
            if [ $((request_count % 100)) -eq 0 ]; then
                echo -n "."
            fi
        done &
        
        sleep 0.1
    done
    
    # Wait for all background jobs
    wait
    
    echo ""
    echo ""
    
    # Calculate statistics
    local total_time=$(($end_time - $start_time))
    local throughput=$(echo "scale=2; $request_count / $total_time" | bc)
    local error_rate=$(echo "scale=2; ($error_count * 100) / $request_count" | bc)
    
    # Calculate percentiles
    IFS=$'\n' sorted=($(sort -n <<<"${response_times[*]}"))
    unset IFS
    
    local count=${#sorted[@]}
    local p50_idx=$(($count * 50 / 100))
    local p95_idx=$(($count * 95 / 100))
    local p99_idx=$(($count * 99 / 100))
    
    local p50=${sorted[$p50_idx]}
    local p95=${sorted[$p95_idx]}
    local p99=${sorted[$p99_idx]}
    
    # Calculate average
    local sum=0
    for time in "${response_times[@]}"; do
        sum=$(($sum + $time))
    done
    local avg=$(($sum / $count))
    
    # Display results
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo -e "${GREEN}Load Test Results${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    printf "%-25s %s\n" "Metric" "Value"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    printf "%-25s %'d\n" "Total Requests" "$request_count"
    printf "%-25s %'d\n" "Successful" "$success_count"
    printf "%-25s %'d\n" "Failed" "$error_count"
    printf "%-25s %.2f req/s\n" "Throughput" "$throughput"
    printf "%-25s %.2f%%\n" "Error Rate" "$error_rate"
    printf "%-25s %d ms\n" "Avg Latency" "$avg"
    printf "%-25s %d ms\n" "P50 Latency" "$p50"
    printf "%-25s %d ms\n" "P95 Latency" "$p95"
    printf "%-25s %d ms\n" "P99 Latency" "$p99"
    printf "%-25s %d seconds\n" "Duration" "$total_time"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    
    # Check if we met performance targets
    if [ $p95 -le 250 ]; then
        echo -e "${GREEN}✓ P95 latency target met (≤250ms)${NC}"
    else
        echo -e "${YELLOW}⚠ P95 latency above target: ${p95}ms > 250ms${NC}"
    fi
    
    if (( $(echo "$error_rate < 1.0" | bc -l) )); then
        echo -e "${GREEN}✓ Error rate target met (<1%)${NC}"
    else
        echo -e "${YELLOW}⚠ Error rate above target: ${error_rate}% > 1%${NC}"
    fi
}

# Run the load test
run_simple_load_test

echo ""
echo -e "${BLUE}View real-time metrics:${NC}"
echo "  • Grafana: http://localhost:3000"
echo "  • Prometheus: http://localhost:9090"
echo "  • Jaeger: http://localhost:16686"
echo ""
