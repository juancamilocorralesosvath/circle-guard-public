# CircleGuard — Design Patterns

## Patterns in Use

| Pattern | Where | Purpose |
|---------|-------|---------|
| API Gateway | `circleguard-gateway-service` | Single entry point for physical gate access |
| Circuit Breaker | `circleguard-gateway-service` | Protects gate from Redis outages |
| External Configuration | All 6 services | Environment-specific config without image rebuilds |
| Service Registry | Kubernetes DNS | Service discovery via stable DNS names |
| Sidecar | Grafana Alloy | Log collection without touching service code |
| Strangler Fig | Incremental microservice extraction | Gradual migration from monolith |

---

## 1. API Gateway

**Purpose:** Route and authenticate all requests through a single entry point, hiding internal service topology from clients.

```
Mobile App / External Client
          │
          ▼
┌─────────────────────┐
│  gateway-service    │  :8087
│  POST /api/v1/gate/ │
│       validate      │
└────────┬────────────┘
         │  queries
         ▼
      Redis (health status store)
```

**How it is used here:**
Physical access control goes through `GateController` on `circleguard-gateway-service`. No external client ever queries Redis or internal services directly. The gateway validates the QR JWT, looks up health status in Redis, and returns a RED/GREEN decision.

**Benefits:**
- Clients need only one address
- Auth logic (JWT validation) is centralized
- Internal topology changes don't affect clients

---

## 2. Circuit Breaker

**Purpose:** Prevent cascading failures by stopping calls to an unavailable dependency, returning a safe fallback instead.

```
validateToken(token)
      │
      ▼
┌─────────────────────────────────────────────────┐
│  Circuit Breaker: "redis"                        │
│                                                  │
│  CLOSED ──5/10 failures──► OPEN                 │
│    │                         │                  │
│    │ (normal)       10s wait │                  │
│    │                         ▼                  │
│    └──────── HALF-OPEN ◄─────┘                 │
│               (3 test calls)                     │
└──────────────────────┬──────────────────────────┘
                       │
           ┌───────────┴──────────┐
           │ Redis OK             │ Redis down
           ▼                     ▼
    real status lookup    fallbackValidateToken()
                          returns GREEN / UNKNOWN
```

**How it is used here:**
`QrValidationService.validateToken()` is annotated with `@CircuitBreaker(name = "redis", fallbackMethod = "fallbackValidateToken")`. If Redis becomes unavailable, the fallback grants access (fail-open) so people are not physically trapped at gates during infrastructure outages.

**Configuration** (`application.yml`):
- Window: 10 calls
- Opens at: 50% failure rate (5 out of 10 calls fail)
- Recovers after: 10 seconds in HALF-OPEN with 3 test calls

**Observing state:**
```bash
curl http://localhost:8087/actuator/circuitbreakers
```

**Benefits:**
- Gate continues to operate during Redis downtime
- Failures are isolated — Redis issues do not crash the service
- State is observable via actuator endpoint

---

## 3. External Configuration

**Purpose:** Inject environment-specific values at runtime so the same Docker image runs in dev, staging, and prod without modification.

```
Docker Image (immutable)
        │
        │ env vars injected at runtime by Kubernetes
        ▼
┌──────────────────────────────────────┐
│  application.yml                     │
│  host: ${REDIS_HOST:localhost}       │◄── Kubernetes ConfigMap
│  port: ${REDIS_PORT:6379}           │    k8s/base/configmaps/
│  expiration: ${JWT_EXPIRATION:...}  │    circleguard-config.yaml
└──────────────────────────────────────┘    (managed by Terraform)
```

**How it is used here:**
Every service reads configuration via `${ENV_VAR:default}` placeholders in `application.yml`. Kubernetes injects these from the `circleguard-config` ConfigMap, which is provisioned and versioned by Terraform across three environments. Secrets (`JWT_SECRET`, `QR_SECRET`, database passwords) come from Kubernetes `Secret` objects — never from ConfigMaps.

**Key env vars managed by ConfigMap:**

| Variable | ConfigMap key | dev value | prod value |
|----------|--------------|-----------|------------|
| `REDIS_HOST` | `REDIS_HOST` | `redis-service` | `redis-service` |
| `REDIS_PORT` | `REDIS_PORT` | `6379` | `6379` |
| `JWT_EXPIRATION` | `JWT_EXPIRATION` | `3600000` | `3600000` |
| `QR_EXPIRATION` | `QR_EXPIRATION` | `300000` | `300000` |
| `DB_HOST` | `DB_HOST` | `postgres-service` | `postgres-service` |
| `KAFKA_BOOTSTRAP_SERVERS` | `KAFKA_BOOTSTRAP_SERVERS` | `kafka-service:9092` | `kafka-service:9092` |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | same | `10` | `50` |

**Benefits:**
- One image per service — no environment-specific builds
- Config changes (e.g. pool size, timeouts) don't require image rebuilds
- Terraform manages ConfigMap values per environment (`dev` / `staging` / `prod`)

---

## 4. Service Registry (Kubernetes DNS)

**Purpose:** Allow services to discover each other by stable name rather than IP address.

```
auth-service  ──► postgres-service:5432   (resolved by kube-dns)
form-service  ──► kafka-service:9092      (resolved by kube-dns)
gateway       ──► redis-service:6379      (resolved by kube-dns)
```

**How it is used here:**
Kubernetes assigns a DNS name to every Service object. `postgres-service`, `redis-service`, `kafka-service` resolve automatically within the cluster. Services reference these names in their ConfigMap values — no hardcoded IPs, no service discovery library needed.

---

## 5. Sidecar (Grafana Alloy)

**Purpose:** Add cross-cutting concerns (log collection) to services without modifying service code.

```
┌─────────────────────────────────┐
│  Kubernetes Pod                 │
│  ┌─────────────┐ ┌───────────┐ │
│  │ auth-service│ │   Alloy   │ │
│  │  (main)     │ │ (sidecar) │ │
│  └──────┬──────┘ └─────┬─────┘ │
│         │  stdout logs  │       │
│         └──────────────►│       │
└─────────────────────────┼───────┘
                          │ forwards
                          ▼
                        Loki
```

**How it is used here:**
Grafana Alloy runs alongside each service and scrapes container logs from stdout, forwarding them to Loki. No service imports any logging SDK — Alloy handles collection transparently.

---

## 6. Strangler Fig

**Purpose:** Incrementally extract functionality from a legacy system into microservices without a big-bang rewrite.

**How it is used here:**
CircleGuard was built service by service — `auth`, `identity`, `promotion`, `form`, `notification`, `gateway` — each extracted as an independent deployable unit. At no point was the entire system replaced at once. New capabilities were added as new services rather than growing a monolith.
