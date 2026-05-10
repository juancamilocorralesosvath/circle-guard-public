#!/bin/bash
echo "Building auth-service..."
docker build -t circleguard-auth-service:latest -f services/circleguard-auth-service/Dockerfile .
echo "Building identity-service..."
docker build -t circleguard-identity-service:latest -f services/circleguard-identity-service/Dockerfile .
echo "Building form-service..."
docker build -t circleguard-form-service:latest -f services/circleguard-form-service/Dockerfile .
echo "Building promotion-service..."
docker build -t circleguard-promotion-service:latest -f services/circleguard-promotion-service/Dockerfile .
echo "Building gateway-service..."
docker build -t circleguard-gateway-service:latest -f services/circleguard-gateway-service/Dockerfile .
echo "Building notification-service..."
docker build -t circleguard-notification-service:latest -f services/circleguard-notification-service/Dockerfile .
echo "Done"
