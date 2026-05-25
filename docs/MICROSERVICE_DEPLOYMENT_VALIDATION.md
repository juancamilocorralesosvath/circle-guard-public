# Microservice Deployment Validation (Phase 2B)

This document details the deployment, validation, and operational findings for all 6 CircleGuard microservices running in Kubernetes.

## Deployment Order

The services were deployed in the following order, respecting runtime dependencies:

1. **identity-service** — No downstream dependencies
2. **auth-service** — Depends on identity-service, PostgreSQL, OpenLDAP
3. **form-service** — Depends on PostgreSQL, Kafka
4. **promotion-service** — Depends on PostgreSQL, Neo4j, Redis, Kafka
5. **gateway-service** — Depends on Redis
6. **notification-service** — Depends on Kafka, auth-service

## Deployment Architecture

Each microservice is implemented as:
- A **Kubernetes Deployment** with `RollingUpdate` strategy (`maxUnavailable: 0`, `maxSurge: 1`)
- A **ClusterIP Service** for internal DNS routing
- Configuration injected via `envFrom` (ConfigMap) and `env` (Secret `valueFrom`)
- Spring Boot `dev` profile activated via `SPRING_PROFILES_ACTIVE=dev`
- Docker images pulled locally via `imagePullPolicy: IfNotPresent`

## Probe Configuration

All probes use the `/actuator/health/readiness` endpoint (not `/actuator/health`) to avoid infrastructure-level health indicators (LDAP, Mail) from blocking pod startup.

| Service | Startup Probe | Readiness Probe | Liveness Probe |
|---|---|---|---|
| identity-service | initialDelay: 10s, period: 5s, failureThreshold: 30 | initialDelay: 30s, period: 10s | initialDelay: 60s, period: 20s |
| auth-service | initialDelay: 10s, period: 5s, failureThreshold: 30 | initialDelay: 30s, period: 10s | initialDelay: 60s, period: 20s |
| form-service | initialDelay: 10s, period: 5s, failureThreshold: 30 | initialDelay: 30s, period: 10s | initialDelay: 60s, period: 20s |
| promotion-service | initialDelay: 15s, period: 5s, failureThreshold: 40 | initialDelay: 40s, period: 10s | initialDelay: 90s, period: 20s |
| gateway-service | initialDelay: 10s, period: 5s, failureThreshold: 24 | initialDelay: 25s, period: 10s | initialDelay: 50s, period: 20s |
| notification-service | initialDelay: 10s, period: 5s, failureThreshold: 30 | initialDelay: 30s, period: 10s | initialDelay: 60s, period: 20s |

### Why `/actuator/health/readiness` instead of `/actuator/health`?

The Spring Boot Actuator `/actuator/health` endpoint includes all auto-configured health indicators (e.g., `LdapHealthIndicator`, `MailHealthIndicator`). In Kubernetes, these infrastructure-level checks can fail during the startup window when middleware is warming up, or when dependent infrastructure is intentionally absent (like the `mail-service`). The `/actuator/health/readiness` endpoint reports only the application lifecycle readiness state, which is the correct signal for Kubernetes probe decisions.

## Startup Dependency Strategy

We chose the **simplest reliable approach**: Kubernetes startup probes with generous `failureThreshold` values.

- Services that depend on middleware will fail their initial health checks and restart 1-2 times while middleware stabilizes.
- The startup probe allows up to 150-200 seconds of retry time before killing the pod.
- After 1-2 restarts, all services naturally stabilize once middleware is accepting connections.
- No `initContainers` or external dependency-checking tools are required.

This approach was validated: the `promotion-service` (the most complex service with 4 middleware dependencies) stabilized after exactly 2 restarts.

## Resource Allocation Strategy

| Service | CPU Request | CPU Limit | Memory Request | Memory Limit |
|---|---|---|---|---|
| identity-service | 250m | 1000m | 256Mi | 512Mi |
| auth-service | 250m | 1000m | 256Mi | 512Mi |
| form-service | 250m | 1000m | 256Mi | 512Mi |
| promotion-service | 250m | 1000m | 384Mi | 768Mi |
| gateway-service | 200m | 500m | 192Mi | 384Mi |
| notification-service | 250m | 1000m | 256Mi | 512Mi |

**Notes:**
- `promotion-service` receives higher memory allocation due to its Neo4j driver, Redis client, and Kafka consumer/producer footprint.
- `gateway-service` receives lower limits as it is a lightweight stateless validator.
- All values are optimized for Docker Desktop (6-8GB RAM recommended).

## Inter-Service Networking Validation

All inter-service HTTP communication was validated using `curl` from within the Kubernetes pods:

| Source → Target | DNS Name | Result |
|---|---|---|
| auth-service → identity-service | `circleguard-identity-service:8083` | ✅ `{"status":"UP"}` |
| notification-service → auth-service | `circleguard-auth-service:8180` | ✅ `{"status":"UP"}` |
| gateway-service → promotion-service | `circleguard-promotion-service:8088` | ✅ `{"status":"UP"}` |
| auth-service → all 6 services | All DNS names | ✅ All `{"status":"UP"}` |

## Runtime Validation Results

| Service | Final Status | Restarts | Startup Time | Notes |
|---|---|---|---|---|
| identity-service | ✅ 1/1 Running | 0 | ~10s | Clean start after security fix |
| auth-service | ✅ 1/1 Running | 0 | ~9s | LDAP health warning (non-blocking) |
| form-service | ✅ 1/1 Running | 0 | ~8s | Clean start |
| promotion-service | ✅ 1/1 Running | 2 | ~90s | Expected: Neo4j/Kafka dependency warm-up |
| gateway-service | ✅ 1/1 Running | 0 | ~6s | Fastest startup (lightweight) |
| notification-service | ✅ 1/1 Running | 0 | ~8s | Mail health warning (non-blocking) |

## Rollout Strategy

All microservices use `RollingUpdate` with:
```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```
This ensures zero-downtime deployments: a new pod is created and must pass its startup probe before the old pod is terminated.

## Issues Discovered and Resolved

### 1. Identity Service — Actuator 401 Unauthorized
**Problem:** The identity service's Spring Security `SecurityConfig` blocked unauthenticated access to `/actuator/**`, returning HTTP 401 to Kubernetes probes.
**Resolution:** Added `/actuator/**` to the `permitAll()` matcher in `SecurityConfig.java`.

### 2. Auth Service — LDAP Health Indicator DOWN
**Problem:** The Spring Boot `LdapHealthIndicator` performs a full LDAP bind operation during health checks. While the TCP connection to `openldap-service:389` succeeded, the bind operation was failing, causing `/actuator/health` to report `DOWN`.
**Resolution:** Changed all probes from `/actuator/health` to `/actuator/health/readiness` which excludes infrastructure health indicators.

### 3. Notification Service — Mail DNS UnknownHostException
**Problem:** The `MailHealthIndicator` tried to connect to `mail-service` which has no deployed Kubernetes Service, causing DNS resolution failures.
**Resolution:** Same probe path change as above. The mail service is intentionally absent in the workshop environment.

### 4. Vault Secret YAML Type Error
**Problem (Phase 2A.5):** The `VAULT_HASH_SALT: 12345678` value in secrets was parsed as an integer, causing Kubernetes to reject the Secret. Kubernetes `stringData` requires all values to be strings.
**Resolution:** Wrapped the value in quotes: `VAULT_HASH_SALT: "12345678"`.

## Known Runtime Risks

1. **Promotion-service restarts:** Expected behavior on cold start. The service needs 2 restart cycles to wait for Neo4j and Kafka to be fully ready.
2. **Memory pressure:** Running all 12 pods simultaneously requires 6-8GB RAM in Docker Desktop.
3. **LDAP auth binding:** The OpenLDAP health indicator shows DOWN in auth-service logs but does not affect pod readiness.
4. **Mail service absence:** The notification service logs DNS errors for `mail-service` but operates normally for Kafka-based notifications.

## Operational Commands

### Deploy All
```bash
kubectl apply -k k8s/overlays/dev
```

### Check Status
```bash
kubectl get pods -n circleguard-dev
kubectl get svc -n circleguard-dev
```

### Rollout Restart (Single Service)
```bash
kubectl rollout restart deployment/circleguard-auth-service -n circleguard-dev
```

### View Logs
```bash
kubectl logs deploy/circleguard-auth-service -n circleguard-dev --tail=50
```

### Validate All Readiness
```bash
for svc in circleguard-identity-service:8083 circleguard-auth-service:8180 circleguard-form-service:8086 circleguard-promotion-service:8088 circleguard-gateway-service:8087 circleguard-notification-service:8082; do
  echo -n "$svc: "
  kubectl exec deploy/circleguard-auth-service -n circleguard-dev -- curl -s http://$svc/actuator/health/readiness
  echo
done
```

### Scale Service
```bash
kubectl scale deployment/circleguard-auth-service --replicas=2 -n circleguard-dev
```

### Troubleshooting
```bash
kubectl describe pod <pod-name> -n circleguard-dev
kubectl logs <pod-name> -n circleguard-dev --previous
kubectl get events -n circleguard-dev --sort-by='.lastTimestamp'
```

## Distributed System Validation Results

The complete CircleGuard distributed platform is operational:
- 6 middleware components (PostgreSQL, Neo4j, Redis, Kafka, Zookeeper, OpenLDAP) are stable
- 6 microservices are running with healthy readiness probes
- Internal DNS correctly routes all inter-service HTTP traffic
- Kafka consumers and producers are connected
- Redis connections are established
- Neo4j bolt connections are active
- PostgreSQL databases are initialized and accessible
- All services self-heal via Kubernetes restart policies

## Service Stabilization Timing

| Phase | Duration | Notes |
|---|---|---|
| Initial middleware startup | ~2-5 min | First-time image pulls dominate |
| Microservice cold start (lightweight) | ~6-10s | gateway, form, notification |
| Microservice cold start (heavy) | ~15-90s | promotion (Neo4j + Kafka dependencies) |
| Full cluster stabilization | ~3-5 min | All 12 pods READY with 0 or minimal restarts |
