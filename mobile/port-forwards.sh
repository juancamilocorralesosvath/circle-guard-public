#!/bin/bash
# Start Kubernetes port-forwards for CircleGuard services

namespace="circleguard-dev"

# Function to start a port-forward in background
start_pf() {
    local svc=$1
    local port=$2
    kubectl port-forward -n "$namespace" svc/"$svc" "$port":"$port" &
}

# Kill any existing port-forwards for these services
pkill -f "port-forward.*circleguard-(auth|form|gateway|promotion|identity)" 2>/dev/null

sleep 2

# Start all port-forwards
start_pf circleguard-auth-service 8180
start_pf circleguard-identity-service 8083
start_pf circleguard-form-service 8086
start_pf circleguard-gateway-service 8087
start_pf circleguard-promotion-service 8088

echo "Port-forwards started"
sleep 5

# Keep script running to maintain background processes
while true; do
    sleep 60
done