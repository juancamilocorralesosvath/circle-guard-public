# CircleGuard — Architecture

## High-Level Diagram

```
                          ┌──────────────────────┐
                          │   Mobile App (Expo)   │
                          │  iOS / Android / Web  │
                          └──────────┬───────────┘
                                     │ HTTPS
                          ┌──────────▼───────────┐
                          │    Gateway Service    │  :8087 / NodePort 30087
                          │  QR Validation + JWT  │
                          │  Circuit Breaker ─────┼──► Redis :6379
                          └──┬──────┬──────┬─────┘
                             │      │      │
              ┌──────────────┘      │      └─────────────────┐
              │                     │                         │
   ┌──────────▼──────┐   ┌──────────▼──────┐   ┌────────────▼────────┐
   │  Auth Service   │   │Identity Service  │   │  Promotion Service  │
   │  JWT + LDAP     │   │ Anonymization    │   │  Graph Propagation  │
   │  :8081          │   │ Vault  :8082     │   │  :8083              │
   │  PostgreSQL ────┤   │  PostgreSQL ─────┤   │  Neo4j ─────────────┤
   │  OpenLDAP  ─────┤   └──────────────────┘   │  Kafka  ────────────┤
   └─────────────────┘                           └─────────────────────┘
                             │      │
              ┌──────────────┘      └─────────────────┐
              │                                         │
   ┌──────────▼──────┐                    ┌────────────▼────────┐
   │  Form Service   │                    │Notification Service  │
   │  Health Survey  │                    │Push / Email / SMS    │
   │  :8084          │                    │:8085                 │
   │  PostgreSQL ────┤                    │  Kafka  ─────────────┤
   └─────────────────┘                   └──────────────────────┘

   ┌─────────────────┐   ┌─────────────────┐
   │Dashboard Service│   │  File Service   │
   │ Hotspot Analytics│   │ Docs / Certs    │
   │  :8086          │   │  :8088          │
   └─────────────────┘   └─────────────────┘
```

## Observability Layer (runs alongside, not in-band)

```
┌──────────────────────────────────────────────────────────────────┐
│                    Observability Stack                           │
│                                                                  │
│  Alloy ──► Loki ──► Grafana        (logs)           :3000        │
│  Prometheus ──────► Grafana        (metrics)        :9090        │
│  Services ──► Jaeger (OTLP :4317)  (traces)         :16686       │
│  Logstash ──► Elasticsearch ──► Kibana (logs/ELK)   :5601        │
│  AlertManager                      (alerts)         :9093        │
└──────────────────────────────────────────────────────────────────┘
```

## Kubernetes Namespaces

```
Docker Desktop Kubernetes
├── circleguard-dev       ← active development deploys
├── circleguard-staging   ← pre-production validation
├── circleguard-prod      ← production (manual approval gate)
└── jenkins               ← CI/CD controller + agent
```

Each namespace is provisioned by Terraform (`terraform/environments/`) with:
- `ResourceQuota` (CPU / memory / pod limits)
- `ConfigMap` with environment-specific values
- `ServiceAccount` + `Role` + `RoleBinding` per service

---

## Service Catalogue

| Service | Port | NodePort (dev) | Responsibility | Database |
|---------|------|----------------|----------------|----------|
| gateway-service | 8087 | 30087 | QR validation, JWT auth, Circuit Breaker | Redis |
| auth-service | 8081 | 30081 | LDAP + local authentication, JWT issuance | PostgreSQL, OpenLDAP |
| identity-service | 8082 | 30082 | Anonymization vault, hash mapping | PostgreSQL |
| promotion-service | 8083 | 30083 | Recursive graph-based status propagation | Neo4j, Kafka |
| form-service | 8084 | 30084 | Health questionnaire submission | PostgreSQL |
| notification-service | 8085 | 30085 | Multi-channel notifications | Kafka |
| dashboard-service | 8084 | 30084 | Geospatial hotspot analytics | PostgreSQL |
| file-service | 8085 | 30085 | Secure document storage | — (local filesystem) |

---

## Service Dependencies

```
gateway-service
  └── auth-service       (JWT validation)
  └── Redis              (QR token cache, Circuit Breaker)

auth-service
  └── PostgreSQL         (user records)
  └── OpenLDAP           (university directory)

identity-service
  └── PostgreSQL         (anonymization vault)

promotion-service
  └── Neo4j              (contact graph)
  └── Kafka              (status change events)
  └── notification-service  (via Kafka topic)

form-service
  └── PostgreSQL         (survey submissions)
  └── identity-service   (anonymous ID resolution)

notification-service
  └── Kafka              (consumes status events)
```

---

## CI/CD Flow

```
git push → Jenkins Pipeline
  │
  ├── Build & Test (Gradle, JUnit, JaCoCo ≥ 85%)
  ├── SonarQube Analysis + Quality Gate
  ├── Docker Build & Push (juanc0410/* on Docker Hub)
  ├── Container Scan (Trivy HIGH/CRITICAL)
  ├── Deploy & Smoke: Dev
  ├── Integration Tests
  ├── Performance Tests (Locust)
  ├── Security Scan (OWASP ZAP)
  ├── Deploy: Staging
  ├── [Approval Gate] → Deploy: Prod
  └── Email notification (success/failure)
```

---

## Design Patterns in Use

| Pattern | Where |
|---------|-------|
| API Gateway | gateway-service — single entry point for all client traffic |
| Circuit Breaker | gateway-service — Resilience4j wraps Redis call, fail-open fallback |
| External Configuration | Kubernetes ConfigMaps injected as env vars in all services |
| Service Registry | Kubernetes DNS — services discover each other by name |
| Sidecar | Grafana Alloy — log collection without modifying service code |
| Strangler Fig | Incremental migration: dashboard/file services not yet in main pipeline |
