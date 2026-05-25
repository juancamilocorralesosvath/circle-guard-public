# DEVOPS_ARCHITECTURE.md — CircleGuard DevOps Blueprint

> **Purpose:** Complete implementation blueprint for the CircleGuard DevOps/Testing workshop.
> **Scope:** 6 selected services — auth, identity, form, promotion, gateway, notification.
> **Platform:** Kubernetes-native. Docker Compose is reference-only.
> **Status:** Architecture phase — no implementation yet.

---

## Table of Contents

1. [Environment Strategy](#1-environment-strategy)
2. [Docker Strategy](#2-docker-strategy)
3. [Kubernetes Strategy](#3-kubernetes-strategy)
4. [Jenkins / CI-CD Strategy](#4-jenkins--ci-cd-strategy)
5. [Testing Strategy](#5-testing-strategy)
6. [Release Management Strategy](#6-release-management-strategy)
7. [Implementation Roadmap](#7-implementation-roadmap)
8. [Risk Mitigation](#8-risk-mitigation)
9. [Operational Guidance](#9-operational-guidance)

---

## 1. Environment Strategy

### 1.1 Environment Topology

Three Kubernetes-native environments, each isolated via namespaces, corresponding to Git branches.

```
Git Branch         Kubernetes Namespace         Purpose
─────────────────────────────────────────────────────────
feature/*  ──►  (no deployment; CI only)         Unit tests + build validation
develop    ──►  circleguard-dev                  Integration + smoke tests
staging    ──►  circleguard-staging              E2E + performance + UAT
main       ──►  circleguard-prod                 Production / final release
```

### 1.2 Dev Environment (`circleguard-dev`)

**Purpose:** Validate that every merged commit builds, passes unit tests, and deploys without breaking integration contracts.

**Infrastructure:**
- Single-replica Kubernetes Deployments for all 6 services
- Shared middleware StatefulSets: PostgreSQL, Kafka+Zookeeper, Redis, Neo4j (single-node)
- OpenLDAP deployment (for auth-service LDAP chain)
- All within namespace `circleguard-dev`

**Deployment rules:**
- Triggered automatically on every merge to `develop`
- Rolling update strategy; no manual approval
- Images tagged with `dev-<short-sha>` (e.g., `dev-a1b2c3d`)

**Testing scope:**
- Unit tests (all 6 services) must pass before deployment
- Post-deployment smoke tests via `curl` / health probe checks
- Integration tests run as a Kubernetes Job after deployment

**Rollback strategy:**
- Automatic: if health probes fail after rolling update, Kubernetes rolls back automatically
- Manual: `kubectl rollout undo deployment/<service> -n circleguard-dev`

**Promotion strategy:**
- Promotion to staging is a manual Jenkins action triggered when dev pipeline is fully green

---

### 1.3 Staging Environment (`circleguard-staging`)

**Purpose:** Full system validation — E2E flows, performance tests, integration contract verification. Mirrors production as closely as possible.

**Infrastructure:**
- 2-replica Deployments for stateless services (auth, identity, form, gateway, notification)
- 1-replica for promotion-service (Neo4j dependency; scale up carefully)
- PostgreSQL StatefulSet with PVC (same schema as prod)
- Kafka StatefulSet (3-broker for realistic messaging)
- Redis StatefulSet with PVC
- Neo4j StatefulSet with PVC
- Ingress controller exposing services via hostname-based routing

**Deployment rules:**
- Triggered manually via Jenkins "Deploy to Staging" action after dev is green
- Images tagged with `staging-<semver>-rc<n>` (e.g., `staging-1.2.0-rc1`)
- Zero-downtime rolling updates
- Requires 2/2 replicas healthy before marking deployment complete

**Testing scope:**
- Integration tests (cross-service HTTP + Kafka flows)
- E2E tests (full health fencing cascade)
- Locust performance tests (30-user warm-up, then 200-user stress)
- Contract tests (OpenAPI response shape validation)

**Rollback strategy:**
- Automatic on health probe failure
- Manual `kubectl rollout undo` available
- Previous staging image tag recorded in Jenkins artifact for quick re-deploy

**Promotion strategy:**
- A tech lead manually approves promotion to production via Jenkins input step
- Promotion is blocked if any E2E or performance test fails

---

### 1.4 Production Environment (`circleguard-prod`)

**Purpose:** Live system serving real users. Strict change management, release notes required.

**Infrastructure:**
- 3-replica Deployments for auth, gateway, form (high-traffic)
- 2-replica for identity, notification
- 1-replica promotion-service (Neo4j-bound; horizontal scale is complex)
- PostgreSQL StatefulSet with PVC + automated backup CronJob
- Redis StatefulSet with persistence
- Neo4j StatefulSet with PVC
- Kafka StatefulSet (3-broker)
- Ingress with TLS termination

**Deployment rules:**
- Requires explicit Jenkins "Approve Production Deployment" input
- Images tagged with `v<semver>` (e.g., `v1.2.0`) — immutable tags
- Blue/green or canary rollout via Kubernetes Deployment strategy
- Git tag created automatically upon successful production deploy

**Testing scope:**
- Post-deployment smoke tests only (no load tests in prod)
- Health probe polling for 5 minutes post-rollout
- Synthetic transaction: login → QR validate → confirm healthy

**Rollback strategy:**
- Immediate rollback: `kubectl rollout undo` using previous `v<semver>` image
- SLA: < 3 minutes to rollback

**Promotion strategy:**
- Manual approval only
- Auto-generated release notes must be reviewed
- Deployment window: business hours only (enforced via Jenkins time-of-day check)

---

### 1.5 Namespace Strategy

```yaml
Namespaces:
  circleguard-dev:
    resourceQuota: cpu=4, memory=8Gi
    purpose: continuous integration deployments
  circleguard-staging:
    resourceQuota: cpu=8, memory=16Gi
    purpose: pre-production validation
  circleguard-prod:
    resourceQuota: cpu=16, memory=32Gi
    purpose: production workloads
  circleguard-infra:
    purpose: shared middleware (Kafka, if cluster-wide)
    note: per-namespace middleware preferred for isolation
```

### 1.6 Branch Strategy

```
main ──────────────────────────────────────► production releases (v tags)
  │
  └─ staging ────────────────────────────► staging RC builds
       │
       └─ develop ─────────────────────► dev auto-deployments
            │
            └─ feature/<name> ─────────► CI only (no deploy)
```

---

## 2. Docker Strategy

### 2.1 Dockerfile Pattern — Multi-Stage Build

All 6 services use the same two-stage pattern. The build stage compiles with full JDK; the runtime stage uses a minimal JRE image.

```dockerfile
# Stage 1: Build (Gradle)
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./
COPY services/<service-name>/ services/<service-name>/
RUN ./gradlew :services:<service-name>:bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S circleguard && adduser -S circleguard -G circleguard
USER circleguard
WORKDIR /app
COPY --from=builder /workspace/services/<service-name>/build/libs/*.jar app.jar
EXPOSE <PORT>
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

**Why multi-stage:** Build tools (~300MB) are excluded from the runtime image. Final images are ~180–220MB vs ~600MB single-stage.

**Why eclipse-temurin:21-jre-alpine:** Minimal attack surface, well-maintained, Java 21 LTS support, matches the Gradle toolchain declaration.

### 2.2 Per-Service Docker Details

| Service | Port | Base Image (runtime) | Special considerations |
|---|---|---|---|
| auth-service | 8180 | eclipse-temurin:21-jre-alpine | LDAP env vars needed; H2 test profile |
| identity-service | 8083 | eclipse-temurin:21-jre-alpine | AES vault secret must come from K8s Secret |
| form-service | 8086 | eclipse-temurin:21-jre-alpine | `/tmp/circleguard-uploads` needs emptyDir volume |
| promotion-service | 8088 | eclipse-temurin:21-jre-alpine | Most resource-intensive; needs Neo4j + PG + Redis + Kafka |
| gateway-service | 8087 | eclipse-temurin:21-jre-alpine | Simplest image; only Redis + JWT secret needed |
| notification-service | 8082 | eclipse-temurin:21-jre-alpine | MOCK_TOKEN/MOCK_SID env vars for test environments |

### 2.3 Image Naming Convention

```
<registry>/<org>/<service>:<tag>

Examples:
  docker.io/circleguard/auth-service:dev-a1b2c3d
  docker.io/circleguard/auth-service:staging-1.2.0-rc1
  docker.io/circleguard/auth-service:v1.2.0

Registry options:
  - Local registry in Kubernetes: registry.circleguard.internal:5000  (workshop)
  - Docker Hub: docker.io/circleguard/*  (public demo)
```

### 2.4 Tagging Strategy

| Environment | Tag Pattern | Example | Immutable? |
|---|---|---|---|
| dev | `dev-<7-char-sha>` | `dev-a1b2c3d` | No (overwritten on same commit) |
| staging | `staging-<semver>-rc<n>` | `staging-1.2.0-rc3` | Yes per RC |
| production | `v<semver>` | `v1.2.0` | Yes — never overwrite |
| latest | `latest` (dev only) | `latest` | No — only for local dev |

### 2.5 Layer Caching Strategy

- Gradle wrapper and `settings.gradle.kts` copied before source code — these change rarely
- Dependencies resolved before application code copy — maximizes Docker cache hits
- In Jenkins, `--cache-from` flag used to pull previous layer from registry before build
- Gradle Daemon disabled in Docker build (`--no-daemon`) to avoid state corruption

### 2.6 Security Considerations

- Run as non-root user (`circleguard`) in all images
- No `COPY . .` patterns — only targeted service directory copied
- Secrets injected via Kubernetes Secrets / environment variables — never baked into image
- No `latest` tag in staging or production
- Image scanning: integrate Trivy as a Jenkins pipeline stage before push

### 2.7 Startup Ordering

Services depend on middleware. In Kubernetes this is handled via:
- **initContainers** that check middleware readiness (e.g., `pg_isready`, Kafka topic existence)
- **Spring Boot** retries: `spring.kafka.consumer.auto-offset-reset=earliest` handles Kafka unavailability at start
- **Deployment ordering** in Jenkins pipeline (middleware Helm chart applied first, then services in order):

```
Deploy order:
  1. postgres StatefulSet
  2. neo4j StatefulSet  
  3. redis StatefulSet
  4. kafka + zookeeper StatefulSets
  5. openldap Deployment
  6. identity-service  (no upstream service deps)
  7. auth-service      (calls identity-service)
  8. form-service      (writes to Kafka)
  9. promotion-service (reads Kafka, writes Redis)
  10. gateway-service  (reads Redis)
  11. notification-service (reads Kafka)
```

---

## 3. Kubernetes Strategy

### 3.1 Namespace Layout

```
k8s/
├── namespaces/
│   ├── dev.yaml
│   ├── staging.yaml
│   └── prod.yaml
├── infra/
│   ├── postgres/
│   ├── neo4j/
│   ├── redis/
│   ├── kafka/
│   └── openldap/
└── services/
    ├── auth-service/
    ├── identity-service/
    ├── form-service/
    ├── promotion-service/
    ├── gateway-service/
    └── notification-service/
```

Each service directory contains:
```
<service>/
├── deployment.yaml
├── service.yaml
├── configmap.yaml
├── secret.yaml        (values replaced by CI/CD — template only)
├── hpa.yaml           (where applicable)
└── ingress.yaml       (where applicable)
```

### 3.2 Deployment Specs Per Service

#### auth-service
```yaml
kind: Deployment
replicas: dev=1, staging=2, prod=3
strategy: RollingUpdate (maxSurge=1, maxUnavailable=0)
resources:
  requests: cpu=250m, memory=512Mi
  limits:   cpu=500m, memory=1Gi
probes:
  liveness:  GET /actuator/health/liveness  initialDelay=45s period=15s
  readiness: GET /actuator/health/readiness initialDelay=30s period=10s
initContainers:
  - wait-for-postgres: pg_isready -h postgres -U admin
  - wait-for-identity: wget -q --spider http://identity-service:8083/actuator/health
envFrom: ConfigMap/auth-config, Secret/auth-secret
```

#### identity-service
```yaml
kind: Deployment
replicas: dev=1, staging=2, prod=2
strategy: RollingUpdate (maxSurge=1, maxUnavailable=0)
resources:
  requests: cpu=250m, memory=512Mi
  limits:   cpu=500m, memory=1Gi
probes:
  liveness:  GET /actuator/health/liveness  initialDelay=30s
  readiness: GET /actuator/health/readiness initialDelay=20s
initContainers:
  - wait-for-postgres: pg_isready -h postgres -U admin
envFrom: ConfigMap/identity-config, Secret/identity-secret
note: Earliest to deploy; no upstream service deps
```

#### form-service
```yaml
kind: Deployment
replicas: dev=1, staging=2, prod=3
strategy: RollingUpdate
resources:
  requests: cpu=250m, memory=512Mi
  limits:   cpu=500m, memory=1Gi
probes:
  liveness:  GET /actuator/health/liveness  initialDelay=40s
  readiness: GET /actuator/health/readiness initialDelay=25s
volumes:
  - name: upload-storage
    emptyDir: {}                  # ephemeral; replace with PVC in prod
volumeMounts:
  - mountPath: /tmp/circleguard-uploads
    name: upload-storage
initContainers:
  - wait-for-postgres
  - wait-for-kafka: kafka-topics.sh --bootstrap-server kafka:9092 --list
```

#### promotion-service
```yaml
kind: Deployment
replicas: dev=1, staging=1, prod=1   # Neo4j-bound; no easy horizontal scale
strategy: RollingUpdate (maxSurge=0, maxUnavailable=1)  # Must drain Neo4j connection
resources:
  requests: cpu=500m, memory=1Gi
  limits:   cpu=1000m, memory=2Gi
probes:
  liveness:  GET /actuator/health/liveness  initialDelay=90s period=30s
  readiness: GET /actuator/health/readiness initialDelay=60s period=15s
initContainers:
  - wait-for-postgres
  - wait-for-neo4j: cypher-shell -u neo4j -p password "RETURN 1"
  - wait-for-redis: redis-cli -h redis ping
  - wait-for-kafka
envFrom: ConfigMap/promotion-config, Secret/promotion-secret
note: Longest startup time (~60-90s). All CI/CD pipelines must account for this.
```

#### gateway-service
```yaml
kind: Deployment
replicas: dev=1, staging=2, prod=3
strategy: RollingUpdate (maxSurge=2, maxUnavailable=0)  # stateless, scale fast
resources:
  requests: cpu=100m, memory=256Mi
  limits:   cpu=300m, memory=512Mi
probes:
  liveness:  GET /actuator/health/liveness  initialDelay=20s period=10s
  readiness: GET /actuator/health/readiness initialDelay=15s period=5s
initContainers:
  - wait-for-redis
note: Fastest startup. Ideal canary deployment candidate.
```

#### notification-service
```yaml
kind: Deployment
replicas: dev=1, staging=2, prod=2
strategy: RollingUpdate
resources:
  requests: cpu=250m, memory=512Mi
  limits:   cpu=500m, memory=1Gi
probes:
  liveness:  GET /actuator/health/liveness  initialDelay=40s
  readiness: GET /actuator/health/readiness initialDelay=30s
initContainers:
  - wait-for-kafka
envVars:
  TWILIO_AUTH_TOKEN: MOCK_TOKEN        # dev + staging: mock mode
  PUSH_GOTIFY_TOKEN: MOCK_TOKEN        # dev + staging: mock mode
note: Stateless consumer. No PVC needed. Easiest K8s deployment.
```

### 3.3 Middleware StatefulSets

#### PostgreSQL
```yaml
kind: StatefulSet
replicas: 1 (dev/staging), 1 with streaming replica (prod)
image: postgres:16
storage: PVC 10Gi (dev), 50Gi (staging), 200Gi (prod)
initContainers: run init-db.sql to create all 5 databases
ConfigMap: postgresql.conf tuning
Secret: POSTGRES_PASSWORD
Service: ClusterIP on port 5432
```

#### Neo4j
```yaml
kind: StatefulSet
replicas: 1 (all environments; community edition)
image: neo4j:5.26
storage: PVC 5Gi (dev), 20Gi (staging/prod)
env: NEO4J_AUTH=neo4j/password (from Secret), NEO4J_PLUGINS=apoc
ports: 7474 (HTTP browser), 7687 (Bolt)
Service: ClusterIP exposing both ports
note: apoc plugin required for some Cypher queries
```

#### Redis
```yaml
kind: StatefulSet
replicas: 1 (all envs)
image: redis:7.2
storage: PVC 2Gi with appendonly persistence
Service: ClusterIP on port 6379
ConfigMap: redis.conf (maxmemory-policy allkeys-lru)
```

#### Kafka + Zookeeper
```yaml
Zookeeper:
  kind: StatefulSet, replicas: 1 (dev), 3 (staging/prod)
  image: confluentinc/cp-zookeeper:7.6.0
  storage: PVC 2Gi

Kafka:
  kind: StatefulSet, replicas: 1 (dev), 3 (staging/prod)
  image: confluentinc/cp-kafka:7.6.0
  storage: PVC 10Gi per broker
  env: KAFKA_ADVERTISED_LISTENERS configured per namespace hostname
  Topics to pre-create (via init Job):
    - survey.submitted
    - promotion.status.changed
    - circle.fenced
    - alert.priority
    - notification.audit
    - audit.identity.accessed
```

#### OpenLDAP
```yaml
kind: Deployment (stateless for workshop)
replicas: 1
image: osixia/openldap:1.5.0
env: LDAP_DOMAIN=circleguard.edu, LDAP_ADMIN_PASSWORD (from Secret)
Service: ClusterIP on port 389
note: auth-service falls back to local DB if LDAP unavailable; LDAP is non-critical
```

### 3.4 ConfigMaps Design

Each service has one ConfigMap with non-secret configuration. Example for auth-service:
```yaml
kind: ConfigMap
metadata: name: auth-config
data:
  SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres:5432/circleguard_auth"
  SPRING_JPA_HIBERNATE_DDL_AUTO: "update"
  SPRING_LDAP_URLS: "ldap://openldap:389"
  SPRING_LDAP_BASE: "dc=circleguard,dc=edu"
  SERVER_PORT: "8180"
  IDENTITY_SERVICE_URL: "http://identity-service:8083"
```

### 3.5 Secrets Design

Secrets contain sensitive values, managed per environment:
```yaml
kind: Secret
type: Opaque
data:
  JWT_SECRET: <base64>
  QR_SECRET: <base64>
  SPRING_DATASOURCE_PASSWORD: <base64>
  VAULT_SECRET: <base64>
  VAULT_SALT: <base64>
```

In Jenkins pipelines, secrets are injected from Jenkins Credentials store (never stored in Git).

### 3.6 Services (K8s Service resources)

| App Service | K8s Service Type | Port | Notes |
|---|---|---|---|
| auth-service | ClusterIP | 8180 | Internal + Ingress exposed |
| identity-service | ClusterIP | 8083 | Internal only |
| form-service | ClusterIP | 8086 | Internal + Ingress exposed |
| promotion-service | ClusterIP | 8088 | Internal only |
| gateway-service | ClusterIP | 8087 | Internal + Ingress exposed |
| notification-service | ClusterIP | 8082 | Internal only (no HTTP) |

### 3.7 Ingress

```yaml
kind: Ingress
ingressClassName: nginx
rules:
  - host: auth.circleguard.dev
    paths: /api/v1/auth → auth-service:8180
  - host: form.circleguard.dev
    paths: /api/v1/surveys → form-service:8086
  - host: gate.circleguard.dev
    paths: /api/v1/gate → gateway-service:8087
```

TLS: cert-manager with self-signed cert for dev/staging; Let's Encrypt for prod.

### 3.8 Horizontal Pod Autoscaler

```yaml
Services with HPA:
  gateway-service:
    minReplicas: 1 (dev), 2 (staging), 3 (prod)
    maxReplicas: 5
    metric: cpu > 70%
  auth-service:
    minReplicas: 1 (dev), 2 (staging), 3 (prod)
    maxReplicas: 4
    metric: cpu > 75%
  form-service:
    minReplicas: 1 (dev), 2 (staging), 3 (prod)
    maxReplicas: 4
    metric: cpu > 75%
```

### 3.9 Spring Actuator Requirement

All 6 services must add to `build.gradle.kts` before Kubernetes deployment:
```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
```

And expose in application.yml:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
```

---

## 4. Jenkins / CI-CD Strategy

### 4.1 Jenkins Topology

```
Jenkins Controller (master)
  └── Agent Pool:
       ├── agent-build-1   (Java/Gradle builds, Docker builds)
       ├── agent-build-2   (parallel service builds)
       └── agent-test-1    (integration/E2E test runner)

Plugins required:
  - Kubernetes plugin (deploy to K8s)
  - Docker Pipeline plugin
  - JUnit plugin (test result publishing)
  - Git plugin
  - Credentials Binding plugin
  - Blue Ocean (optional, UI)
  - Slack Notification (optional)
  - Pipeline: Stage View
```

### 4.2 Pipeline Organization

```
jenkins/
├── Jenkinsfile                         # Root pipeline (dispatches per branch)
├── pipelines/
│   ├── dev-pipeline.groovy            # Dev environment pipeline
│   ├── staging-pipeline.groovy        # Staging pipeline
│   └── prod-pipeline.groovy           # Production pipeline
└── shared/
    ├── vars/
    │   ├── buildService.groovy        # Reusable build stage
    │   ├── dockerBuild.groovy         # Reusable Docker build+push
    │   ├── k8sDeploy.groovy           # Reusable K8s deployment
    │   ├── runTests.groovy            # Reusable test runner
    │   └── generateReleaseNotes.groovy
    └── resources/
        └── locust/                    # Locust test scripts
```

### 4.3 Shared Library Stages

#### `buildService.groovy`
```groovy
// Executes: ./gradlew :services:<name>:bootJar -x test
// Publishes: JAR artifact
// Caches: Gradle cache volume on agent
```

#### `dockerBuild.groovy`
```groovy
// Builds multi-stage Dockerfile
// Tags image per environment convention
// Pushes to registry
// Runs Trivy image scan
// Fails pipeline on CRITICAL vulnerabilities
```

#### `k8sDeploy.groovy`
```groovy
// Applies ConfigMap + Secret + Deployment + Service
// Waits for rollout: kubectl rollout status deployment/<name> --timeout=180s
// Checks health endpoint after rollout
// On failure: kubectl rollout undo
```

### 4.4 DEV Pipeline (develop branch)

**Trigger:** Push to `develop` branch

```
Stage 1: Checkout
  └── git checkout develop

Stage 2: Build All (parallel)
  ├── Build auth-service      → bootJar
  ├── Build identity-service  → bootJar
  ├── Build form-service      → bootJar
  ├── Build promotion-service → bootJar
  ├── Build gateway-service   → bootJar
  └── Build notification-service → bootJar

Stage 3: Unit Tests (parallel)
  ├── auth-service unit tests       → JUnit XML
  ├── identity-service unit tests   → JUnit XML
  ├── form-service unit tests       → JUnit XML
  ├── promotion-service unit tests  → JUnit XML
  ├── gateway-service unit tests    → JUnit XML
  └── notification-service unit tests → JUnit XML
  └── Publish JUnit reports
  └── FAIL pipeline if any test fails

Stage 4: Docker Build & Push (parallel, only if tests pass)
  ├── docker build + tag dev-<sha> + push: auth-service
  ├── docker build + tag dev-<sha> + push: identity-service
  ├── docker build + tag dev-<sha> + push: form-service
  ├── docker build + tag dev-<sha> + push: promotion-service
  ├── docker build + tag dev-<sha> + push: gateway-service
  └── docker build + tag dev-<sha> + push: notification-service

Stage 5: Deploy to Dev (sequential — respects startup order)
  └── kubectl apply -n circleguard-dev (middleware first, then services in order)
  └── Wait for all rollouts to complete
  └── Health check: poll /actuator/health on each service

Stage 6: Smoke Tests
  └── curl http://auth.circleguard.dev/actuator/health → expect {"status":"UP"}
  └── curl http://form.circleguard.dev/api/v1/questionnaires/active → expect 200 or 404
  └── curl http://gate.circleguard.dev/api/v1/gate/validate → expect 200 (with dummy token)

Stage 7: Integration Tests (Kubernetes Job)
  └── kubectl apply -f k8s/jobs/integration-test-job.yaml -n circleguard-dev
  └── Wait for Job completion (timeout 10 minutes)
  └── Collect and publish test results

Stage 8: Notify
  └── Slack/email notification with build status, test summary, deployment URL
```

**Total estimated time:** 12–18 minutes

---

### 4.5 STAGING Pipeline (staging branch)

**Trigger:** Manual "Deploy to Staging" Jenkins action (or merge to `staging`)

```
Stage 1: Checkout + Version
  └── git checkout staging
  └── Compute semver from git tags: <major>.<minor>.<patch>-rc<n>

Stage 2: Pull Dev Images
  └── Re-tag dev-<sha> images as staging-<semver>-rc<n>
  └── Push to registry

Stage 3: Deploy to Staging (sequential)
  └── kubectl apply -n circleguard-staging (all manifests)
  └── kubectl rollout status for each service (timeout 300s)
  └── Health probe polling (60s window)

Stage 4: Integration Tests
  └── Run all 5 integration test scenarios against staging endpoints
  └── Publish JUnit XML results
  └── FAIL pipeline if any integration test fails

Stage 5: E2E Tests
  └── Run all 5 E2E scenarios (full health fencing cascade)
  └── Tests execute as REST API calls against staging Ingress endpoints
  └── Publish results
  └── FAIL pipeline if any E2E test fails

Stage 6: Performance Tests (Locust)
  └── Deploy Locust as a Kubernetes Job in circleguard-staging
  └── Run: 10 users (warm-up, 2 min) → 100 users (5 min) → 200 users (5 min)
  └── Collect: p50, p95, p99 response times + error rates
  └── Assert NFR thresholds:
      - gateway /validate: p95 < 100ms
      - auth /login: p95 < 500ms
      - form /surveys: p95 < 500ms
      - promotion /health/confirmed: p95 < 1000ms
  └── Publish Locust HTML report as Jenkins artifact
  └── WARN (not fail) if thresholds exceeded (configurable)

Stage 7: Staging Validation Gate
  └── Summary report: all test counts, pass/fail
  └── Require manual approval before any further promotion

Stage 8: Notify
  └── Report with: test results, performance summary, staging URLs, RC version
```

**Total estimated time:** 25–40 minutes

---

### 4.6 PRODUCTION Pipeline (main branch)

**Trigger:** Manual approval in Jenkins after staging is green

```
Stage 1: Approval Gate
  └── Jenkins input step: "Approve deployment of v<semver> to production?"
  └── Approver must be in Jenkins 'release-managers' role
  └── Time window check: reject if outside business hours (7am-6pm)

Stage 2: Version Finalization
  └── Compute final semver: strip RC suffix → v<major>.<minor>.<patch>
  └── Re-tag staging images as v<semver> (immutable)
  └── Push final images to registry

Stage 3: Generate Release Notes
  └── git log --oneline staging..HEAD → collect commit messages
  └── Group by type: feat|fix|refactor|perf|test|docs
  └── Generate RELEASE_NOTES_v<semver>.md:
      - Version, date, deployer
      - Features Added
      - Bug Fixes
      - Breaking Changes
      - Services Deployed (with image digests)
      - Rollback Instructions
  └── Publish as Jenkins artifact
  └── Commit release notes to repo (automated PR)

Stage 4: Git Tagging
  └── git tag -a v<semver> -m "Release v<semver>"
  └── git push origin v<semver>

Stage 5: Deploy to Production (sequential, careful)
  └── kubectl apply -n circleguard-prod (one service at a time)
  └── After each service: kubectl rollout status (timeout 300s)
  └── Health probe polling (120s window)
  └── On ANY failure: immediate rollback of that service + alert

Stage 6: Post-Deploy Validation
  └── Synthetic transaction:
      1. POST /api/v1/auth/login → expect JWT
      2. GET /api/v1/auth/qr/generate → expect qrToken
      3. POST /api/v1/gate/validate → expect {"status":"GREEN"}
  └── Monitor /actuator/health on all services for 5 minutes
  └── FAIL if any service unhealthy

Stage 7: Notify
  └── Full release announcement: version, changes, image digests, rollback command
  └── Archive release notes artifact
```

**Total estimated time:** 20–35 minutes (including approval wait)

---

## 5. Testing Strategy

### 5.1 Overview

```
Test Level      Where Runs          Framework              Trigger
────────────────────────────────────────────────────────────────────
Unit            Jenkins / local     JUnit5 + Mockito       Every push
Integration     K8s Job (dev)       JUnit5 + Testcontainers Merge to develop
E2E             K8s Job (staging)   REST Assured / HTTPie   Deploy to staging
Performance     K8s Job (staging)   Locust                  Deploy to staging
```

### 5.2 Five NEW Unit Tests

#### UT-1: `QrTokenService` — Token Expiration Validation
- **Service:** auth-service
- **Class:** `QrTokenServiceTest` (new)
- **Feature:** QR token expiry enforcement
- **Objective:** Verify that a token generated with a short TTL is rejected after expiry, and that a fresh token within TTL is accepted
- **Why it matters:** The 60-second rotating token is a security-critical feature. A flaw here allows expelled users back on campus.
- **Dependencies:** None (pure unit — JWT library + fixed clock)
- **Method:** Generate a token with TTL=1s, sleep 2s, attempt to parse → expect `ExpiredJwtException`. Also verify valid token parses correctly.
- **Environment:** Unit (no infra)
- **Assertions:** `assertThrows(ExpiredJwtException.class, ...)` and `assertDoesNotThrow(...)`

#### UT-2: `SymptomMapper` — Multi-Choice Symptom Detection
- **Service:** form-service
- **Class:** `SymptomMapperTest` (extend existing)
- **Feature:** `hasSymptoms()` for MULTI_CHOICE question types
- **Objective:** Verify that a MULTI_CHOICE response containing symptom-related answers (["cough", "fatigue"]) is correctly detected as symptomatic, and an empty response `[]` is not.
- **Why it matters:** The existing test only covers YES_NO questions. MULTI_CHOICE is a declared `QuestionType` — an undetected bug here would silently swallow positive cases.
- **Dependencies:** None (pure unit)
- **Assertions:** `assertTrue(mapper.hasSymptoms(...))` with MULTI_CHOICE, `assertFalse(...)` with empty selection

#### UT-3: `KAnonymityFilter` — Below-K Population Masking
- **Service:** (dashboard-service logic — extract to shared util or test within dashboard)
- **Class:** `KAnonymityFilterTest` (new, within dashboard-service)
- **Feature:** Privacy masking when totalUsers < K
- **Objective:** Verify that a department with 3 users (below K=5) returns fully masked response with `"note": "Insufficient data for privacy"` and that a department with 10 users returns real counts (except counts below K which are masked as `"<5"`).
- **Why it matters:** FERPA compliance — a bug here exposes individual health records.
- **Dependencies:** None (pure unit)
- **Assertions:** `assertEquals("<5", result.get("confirmedCount"))` for small groups; full masking for sub-K populations

#### UT-4: `DualChainAuthenticationProvider` — LDAP Fallback
- **Service:** auth-service
- **Class:** `DualChainAuthenticationProviderTest` (new)
- **Feature:** Fallback to local DB when LDAP throws `AuthenticationException`
- **Objective:** Verify that when the LDAP provider throws `BadCredentialsException`, the provider does NOT propagate it but instead tries the DAO provider, which succeeds with the correct local user.
- **Why it matters:** Campus users authenticated via local DB (guests, admin accounts) would be permanently locked out if the fallback is broken.
- **Dependencies:** Mockito mocks for both providers
- **Assertions:** `verify(localProvider, times(1)).authenticate(...)` after LDAP failure; returned auth object is not null

#### UT-5: `HealthStatusService` — Mandatory Fence Window Enforcement
- **Service:** promotion-service
- **Class:** `HealthStatusServiceTest` (extend existing)
- **Feature:** `checkFenceWindow()` — blocking ACTIVE transition within fence period
- **Objective:** Verify that a user in SUSPECT status updated 3 days ago (well within 14-day mandatory fence) throws `FenceException` on `resolveStatus()`, and that the same user updated 20 days ago resolves without exception.
- **Why it matters:** The fence window is a public health enforcement mechanism. Bypassing it prematurely could endanger campus community.
- **Dependencies:** Mockito for `UserNodeRepository`, `SystemSettingsRepository`
- **Assertions:** `assertThrows(FenceException.class, ...)` and `assertDoesNotThrow(...)`

---

### 5.3 Five NEW Integration Tests

#### IT-1: Login Flow — auth-service calls identity-service (HTTP)
- **Services:** auth-service → identity-service
- **Class:** `LoginIntegrationTest` (new, in auth-service test directory)
- **Feature:** Full login flow creating an anonymous ID
- **Objective:** Start both services against real databases (H2 for auth, H2 for identity), POST /api/v1/auth/login → verify the response contains a valid JWT with a UUID `anonymousId` that was actually created in the identity DB
- **Why it matters:** This is the most critical synchronous inter-service call. If identity-service is down or the HTTP contract changes, all logins fail.
- **Required dependencies:** Testcontainers for H2 (in-memory so no container needed), WireMock to mock identity-service for unit isolation, or full SpringBootTest with both app contexts
- **Method:** Use `@SpringBootTest` with two application contexts, or WireMock to stub identity-service response
- **Environment:** Dev CI
- **Assertions:** JWT contains `anonymousId` UUID, status 200, `"type":"Bearer"` in response

#### IT-2: Survey Submission → Kafka → Promotion Status Update
- **Services:** form-service → Kafka → promotion-service
- **Class:** `SurveyToStatusIntegrationTest` (new)
- **Feature:** Async survey-to-status cascade via Kafka
- **Objective:** Submit a health survey with `hasFever=true` to form-service, verify that after Kafka message delivery and promotion-service consumption, the user's status is updated to SUSPECT in Neo4j
- **Why it matters:** This is the core async business flow. If the Kafka consumer doesn't receive or process the event, health fencing doesn't trigger.
- **Required dependencies:** Embedded Kafka (`@EmbeddedKafka`), Neo4j Testcontainer
- **Environment:** Dev CI
- **Assertions:** After `CompletableFuture.waitFor(...)`, query Neo4j for user node → `assertEquals("SUSPECT", user.status)`

#### IT-3: Promotion Service writes Redis → gateway-service reads correct status
- **Services:** promotion-service → Redis → gateway-service
- **Class:** `RedisStatusSharingIntegrationTest` (new)
- **Feature:** Shared Redis state between promotion-service and gateway-service
- **Objective:** Use promotion-service to set a user's status to SUSPECT via `POST /health/confirmed`, then call gateway-service `POST /gate/validate` with a valid QR token for that user → verify RED response
- **Why it matters:** This is the only cross-service shared-state dependency. A Redis key format mismatch between services silently breaks campus entry validation.
- **Required dependencies:** Redis Testcontainer, both services wired to same Redis instance
- **Environment:** Dev CI
- **Assertions:** `assertEquals("RED", result.status)`, `assertFalse(result.valid)`

#### IT-4: Notification dispatch triggered by Kafka status change event
- **Services:** promotion-service (Kafka producer) → notification-service (Kafka consumer)
- **Class:** `StatusChangeNotificationIntegrationTest` (new)
- **Feature:** Kafka-triggered multi-channel notification dispatch
- **Objective:** Publish a `promotion.status.changed` event to Kafka with `status=SUSPECT`, verify notification-service processes it and attempts dispatch to all 3 channels (email, SMS, push) — all in mock mode
- **Why it matters:** If the Kafka consumer doesn't receive the event or the dispatcher fails silently, users are never notified — a public health failure.
- **Required dependencies:** Embedded Kafka, MockBean for EmailService/SmsService/PushService
- **Environment:** Dev CI
- **Assertions:** `verify(emailService, timeout(5000)).sendAsync(any(), any())` and same for SMS/Push

#### IT-5: QR token round-trip — auth-service generates, gateway-service validates
- **Services:** auth-service → gateway-service
- **Class:** `QrTokenRoundTripIntegrationTest` (new)
- **Feature:** End-to-end QR token lifecycle
- **Objective:** Login as a user → extract JWT → call auth-service `GET /api/v1/auth/qr/generate` with JWT → get back a QR token → send QR token to gateway-service `POST /gate/validate` → verify GREEN response (user is healthy)
- **Why it matters:** The QR token is signed with a separate `qr.secret`. If the gateway uses a different secret or the token format changes, all gate scanners stop working.
- **Required dependencies:** Both services running (SpringBootTest or dedicated integration test environment), Redis Testcontainer (gateway reads status), shared JWT/QR secrets via test config
- **Environment:** Dev CI (or staging)
- **Assertions:** Token is parseable by gateway; status is GREEN; `valid=true`

---

### 5.4 Five NEW E2E Tests

E2E tests run against the deployed staging Kubernetes environment. They are REST API calls, not SpringBootTest. They use `REST Assured` (Java) or `requests` (Python) against Ingress endpoints.

#### E2E-1: Happy Path — Healthy Student Enters Campus
- **Flow:** Login → Generate QR → Validate at gate
- **Steps:**
  1. `POST /api/v1/auth/login` → extract JWT + anonymousId
  2. `GET /api/v1/auth/qr/generate` (Bearer JWT) → extract qrToken
  3. `POST /api/v1/gate/validate` {token: qrToken} → assert GREEN
- **Services touched:** auth-service, identity-service (via login), gateway-service
- **Expected:** 200 OK, `{"valid":true,"status":"GREEN"}`
- **Why:** Validates the most common daily transaction. A failure here means the campus entry system is broken.

#### E2E-2: Health Fencing Cascade — Sick Student Blocked
- **Flow:** Report symptoms → System cascades → Gate denies entry
- **Steps:**
  1. Login as `test-user-1` → get JWT + anonymousId
  2. `POST /api/v1/surveys` with hasFever=true → expect 200
  3. Wait 5 seconds (Kafka + promotion processing)
  4. `GET /api/v1/auth/qr/generate` → qrToken
  5. `POST /api/v1/gate/validate` → assert RED
- **Services touched:** auth, identity, form, promotion (Kafka), gateway
- **Expected:** `{"valid":false,"status":"RED"}`
- **Why:** Validates the complete health fencing pipeline — the core product value.

#### E2E-3: Admin Confirms Positive — Contacts Notified
- **Flow:** Admin marks user confirmed → notification dispatched (mock)
- **Steps:**
  1. Login as `health_user` (HEALTH_CENTER role) → JWT
  2. `POST /api/v1/health/confirmed` {anonymousId: "test-user-2"} → expect 200
  3. Wait 3 seconds
  4. Check notification-service logs or mock assertion endpoint → confirm dispatch fired
  5. Verify `test-user-2` QR now returns RED at gateway
- **Services touched:** auth, promotion, notification, gateway
- **Expected:** Status CONFIRMED in promotion-service, RED at gate, notification dispatched
- **Why:** The highest-stakes action in the system. If admin confirmation doesn't cascade, sick people pass freely.

#### E2E-4: Recovery Flow — Resolved User Re-Admitted
- **Flow:** User in SUSPECT resolved by admin → gate turns GREEN
- **Steps:**
  1. Set `test-user-3` to SUSPECT via `POST /health/confirmed`
  2. Verify gate returns RED
  3. `POST /health/resolve` {anonymousId: "test-user-3", adminOverride: true} → 200
  4. Wait 3 seconds
  5. Validate QR → expect GREEN
- **Services touched:** auth, promotion, gateway
- **Expected:** Gate returns GREEN after resolve; Redis key updated
- **Why:** Recovery flow is equally critical — a bug here permanently locks out users who have recovered.

#### E2E-5: Questionnaire Driven Symptom Report
- **Flow:** Fetch active questionnaire → submit dynamic form → status updated
- **Steps:**
  1. Login as test user → JWT + anonymousId
  2. `GET /api/v1/questionnaires/active` → get questionnaire with questions
  3. Build response map with "YES" to fever question
  4. `POST /api/v1/surveys` {anonymousId, responses: {...}} → 200
  5. Wait 5 seconds
  6. Validate QR → expect RED (status promoted to SUSPECT via Kafka)
- **Services touched:** auth, identity, form, promotion, gateway
- **Expected:** Dynamic form properly detects symptoms via SymptomMapper → Kafka → Neo4j → Redis → gate RED
- **Why:** Validates the full questionnaire-to-gate pipeline using the dynamic form system (not the legacy hasFever field).

---

### 5.5 Locust Performance Tests

Locust scripts deployed as Kubernetes Jobs in `circleguard-staging`.

```python
# locust/locustfile.py — conceptual design

class AuthUser(HttpUser):
    # Task 1: Concurrent login load
    @task(3)
    def login(self):
        self.client.post("/api/v1/auth/login",
                         json={"username": "staff_guard", "password": "password"})
    # SLA: p95 < 500ms, error_rate < 1%

class GateValidator(HttpUser):
    # Task 2: Gate validation throughput (most critical — runs at every entrance)
    @task(10)
    def validate_qr(self):
        self.client.post("/api/v1/gate/validate",
                         json={"token": PREGENERATED_VALID_TOKEN})
    # SLA: p95 < 100ms, throughput > 200 RPS per replica, error_rate < 0.1%

class SurveySubmitter(HttpUser):
    # Task 3: Survey submission spike (morning rush when students report symptoms)
    @task(2)
    def submit_survey(self):
        self.client.post("/api/v1/surveys",
                         json={"anonymousId": ANON_IDS[random], "hasFever": False})
    # SLA: p95 < 500ms, error_rate < 1%

class HealthStatsPoller(HttpUser):
    # Task 4: Dashboard stats polling (admin dashboard auto-refresh)
    @task(1)
    def get_stats(self):
        self.client.get("/api/v1/health-status/stats")
    # SLA: p95 < 300ms (Redis-cached after first call), error_rate < 0.5%

class StatusPromoter(HttpUser):
    # Task 5: Status promotion cascade (rare but must meet NFR-1: <1s)
    @task(1)
    def confirm_positive(self):
        self.client.post("/api/v1/health/confirmed",
                         json={"anonymousId": ANON_IDS[random]},
                         headers={"Authorization": "Bearer " + HEALTH_CENTER_JWT})
    # SLA: p95 < 1000ms (NFR-1), error_rate < 1%
```

**Locust Kubernetes Job design:**
```yaml
kind: Job
metadata: name: locust-perf-test
spec:
  completions: 1
  template:
    spec:
      containers:
        - name: locust
          image: locustio/locust:2.x
          command: ["locust", "--headless", "-u", "200", "-r", "20", "--run-time", "10m",
                    "--host", "http://gateway-service:8087",
                    "--html", "/results/report.html",
                    "-f", "/scripts/locustfile.py"]
          volumeMounts:
            - name: results, mountPath: /results
            - name: scripts, mountPath: /scripts
      volumes:
        - name: scripts, configMap: locust-scripts
        - name: results, emptyDir: {}
```

Jenkins collects the HTML report via `kubectl cp` after Job completion.

---

## 6. Release Management Strategy

### 6.1 Semantic Versioning

Format: `MAJOR.MINOR.PATCH`

| Increment | When | Example |
|---|---|---|
| PATCH | Bug fix, no new features | 1.0.1 |
| MINOR | New feature, backward compatible | 1.1.0 |
| MAJOR | Breaking change or major milestone | 2.0.0 |

Version source of truth: Git tags (`v1.2.0`). Jenkins reads the latest tag and increments automatically based on commit message conventions:
- `feat:` → MINOR bump
- `fix:` → PATCH bump
- `BREAKING CHANGE:` in commit body → MAJOR bump
- Everything else → PATCH bump

### 6.2 Release Notes Generation

Format: `RELEASE_NOTES_v<semver>.md`

```markdown
# CircleGuard Release v1.2.0
**Date:** 2026-05-15
**Deployed by:** Jenkins (approved by: lead@circleguard.edu)

## Services Deployed
| Service | Image | Digest |
|---|---|---|
| auth-service | circleguard/auth-service:v1.2.0 | sha256:abc... |
| ... | ... | ... |

## Changes
### New Features
- feat(form): Add MULTI_CHOICE symptom detection (#42)
- feat(gateway): Add token expiry error codes (#38)

### Bug Fixes
- fix(promotion): Fix Redis key format for anonymousId with dashes (#41)

### Breaking Changes
None.

## Rollback Instructions
kubectl set image deployment/auth-service auth-service=circleguard/auth-service:v1.1.3 -n circleguard-prod

## Test Results
- Unit Tests: 47/47 passed
- Integration Tests: 5/5 passed
- E2E Tests: 5/5 passed
- Performance: gateway p95=87ms ✅, auth p95=412ms ✅
```

### 6.3 Git Tagging Strategy

```bash
# Jenkins automation on prod deploy:
git tag -a v${VERSION} -m "Release v${VERSION}: ${COMMIT_COUNT} commits since last release"
git push origin v${VERSION}
```

Tags are annotated (not lightweight) for traceability.

### 6.4 Rollback Process

| Scope | Command | Time |
|---|---|---|
| Single service | `kubectl rollout undo deployment/<name> -n circleguard-prod` | < 60s |
| Full release | Re-run prod pipeline with previous version tag | < 20 min |
| Database migration | Flyway `undo` (if reversible) or restore PG backup | < 30 min |

Rollback is documented in every release note. The previous image tag is always preserved in registry.

### 6.5 Deployment Approvals

```
Developer pushes to develop → no approval needed
Staging promotion         → tech lead approves in Jenkins (Slack notification)
Production deployment     → release manager approves + business hours check
```

---

## 7. Implementation Roadmap

### Critical Path

```
Infrastructure → Docker → Kubernetes → Jenkins → Tests → Release

Week 1: Foundations
Week 2: Dev Pipeline
Week 3: Staging + Testing
Week 4: Production + Release
```

### Detailed Implementation Sequence

| # | Task | Difficulty | Depends On | Critical Path? |
|---|---|---|---|---|
| 1 | Add Spring Actuator to all 6 services | Low | Nothing | ✅ Yes |
| 2 | Externalize secrets to env vars in application.yml | Low | Nothing | ✅ Yes |
| 3 | Create Dockerfile for gateway-service (simplest) | Low | #2 | ✅ Yes |
| 4 | Create Dockerfile for notification-service | Low | #2 | ✅ Yes |
| 5 | Create Dockerfile for identity-service | Low | #2 | ✅ Yes |
| 6 | Create Dockerfile for auth-service | Low | #2 | ✅ Yes |
| 7 | Create Dockerfile for form-service | Low | #2 | No |
| 8 | Create Dockerfile for promotion-service | Medium | #2 | No |
| 9 | Write Kubernetes infra manifests (PG, Redis, Kafka, Neo4j) | Medium | Nothing | ✅ Yes |
| 10 | Write K8s manifests for gateway-service (dev namespace) | Low | #3, #9 | ✅ Yes |
| 11 | Write K8s manifests for identity-service (dev namespace) | Low | #5, #9 | ✅ Yes |
| 12 | Write K8s manifests for auth-service (dev namespace) | Low | #6, #9 | ✅ Yes |
| 13 | Write K8s manifests for form-service (dev namespace) | Low | #7, #9 | No |
| 14 | Write K8s manifests for promotion-service (dev namespace) | Medium | #8, #9 | No |
| 15 | Write K8s manifests for notification-service (dev namespace) | Low | #4, #9 | No |
| 16 | Write 5 new unit tests | Low | #1 | No |
| 17 | Write Jenkins shared library (buildService, dockerBuild, k8sDeploy) | Medium | #3-#8 | ✅ Yes |
| 18 | Write dev Jenkinsfile | Medium | #17 | ✅ Yes |
| 19 | Deploy dev environment end-to-end | Medium | #10-#15, #18 | ✅ Yes |
| 20 | Write 5 integration tests | Medium | #19 | No |
| 21 | Write K8s manifests for staging namespace | Low | #10-#15 | No |
| 22 | Write staging Jenkinsfile | Medium | #17, #21 | No |
| 23 | Write 5 E2E tests | Medium | #22 | No |
| 24 | Write Locust performance scripts + K8s Job | Medium | #22 | No |
| 25 | Write K8s manifests for prod namespace | Low | #21 | No |
| 26 | Write prod Jenkinsfile with approval + release notes | High | #17, #25 | No |
| 27 | Test full release pipeline (mock approval) | Medium | #26 | No |

### Quick Wins (deliver first for morale + grading)
1. Spring Actuator (1 hour, all 6 services) — enables all health probes
2. gateway-service Dockerfile (30 min) — simplest, no middleware
3. gateway-service K8s manifests + deploy (1 hour) — first green Kubernetes deployment

### Risky Tasks
- promotion-service Kubernetes deployment (Neo4j startup time, complex initContainers)
- Embedded Kafka integration tests (race conditions between publish and consume)
- Locust Kubernetes Job (results collection via `kubectl cp`)
- Release notes git commit automation (Jenkins git credentials setup)

---

## 8. Risk Mitigation

| Risk | Mitigation |
|---|---|
| Neo4j Testcontainer slow pull (1GB+) | Pre-pull image in CI: `docker pull neo4j:5.26` before first job |
| Kafka consumer race in integration tests | Use `@EmbeddedKafka` with `awaitAtMost(10, SECONDS)` assertions |
| promotion-service slow startup (90s) | Set `initialDelaySeconds: 90` on probes; increase Job timeout |
| Hardcoded secrets leak | Rotate after workshop; use Jenkins Credentials vault |
| LDAP unreachable in K8s | auth-service falls back to local DB; non-blocking |
| form-service file uploads in K8s | Use `emptyDir` volume (ephemeral); acceptable for workshop |
| `AnalyticsControllerTest` broken in dashboard-service | Not in scope; fix if time allows (add `classes=DashboardApplication.class`) |
| Redis TTL not set on status keys | Add TTL (e.g., 30 days) in promotion-service; prevents stale data |

---

## 9. Operational Guidance

### Useful Commands Reference

```bash
# Deploy to dev
kubectl apply -f k8s/namespaces/dev.yaml
kubectl apply -f k8s/infra/ -n circleguard-dev
kubectl apply -f k8s/services/ -n circleguard-dev

# Watch rollout
kubectl rollout status deployment/auth-service -n circleguard-dev

# Check logs
kubectl logs -f deployment/promotion-service -n circleguard-dev

# Run integration tests manually
kubectl apply -f k8s/jobs/integration-test-job.yaml -n circleguard-dev
kubectl wait --for=condition=complete job/integration-test --timeout=600s -n circleguard-dev

# Rollback a service
kubectl rollout undo deployment/gateway-service -n circleguard-prod

# Scale for performance test
kubectl scale deployment/gateway-service --replicas=3 -n circleguard-staging

# Get all resources in a namespace
kubectl get all -n circleguard-staging
```

### Service Health Dashboard

After deployment, verify all services:
```bash
for svc in auth-service:8180 identity-service:8083 form-service:8086 \
           promotion-service:8088 gateway-service:8087 notification-service:8082; do
  name=$(echo $svc | cut -d: -f1)
  port=$(echo $svc | cut -d: -f2)
  echo -n "$name: "
  kubectl exec -n circleguard-dev deploy/$name -- \
    wget -qO- http://localhost:$port/actuator/health | grep -o '"status":"[^"]*"'
done
```

### Startup Order Enforcement

Always apply in this order to avoid connection refused errors:
1. PostgreSQL StatefulSet → wait for `pg_isready`
2. Neo4j StatefulSet → wait for Bolt port 7687
3. Redis StatefulSet → wait for PONG on port 6379
4. Zookeeper → Kafka StatefulSets → wait for topic creation
5. OpenLDAP → wait for port 389
6. identity-service → auth-service → form-service → promotion-service → gateway-service → notification-service

---

*Document generated: May 2026. CircleGuard DevOps Workshop — Phase 2 Architecture Blueprint.*
*Next phase: Implementation (Dockerfiles, K8s manifests, Jenkinsfiles, tests).*
