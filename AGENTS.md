# AGENTS.md — CircleGuard Architectural Memory

> **Purpose:** Permanent architectural reference for DevOps/Testing workshop implementation phases.  
> **Generated from:** Full static analysis of the CircleGuard monorepo source code, configuration files, test files, and infrastructure assets.  
> **Workshop scope:** Jenkins CI/CD pipelines, Docker, Kubernetes, unit/integration/E2E/performance testing, multi-environment delivery.

---

## 1. Repository Architecture Overview

CircleGuard is a **university contact-tracing and health-fencing platform** built as a microservice monolith (monorepo). Its purpose: identify interconnected student/staff contact groups ("Circles"), apply rapid health status cascades (ACTIVE → SUSPECT → PROBABLE → CONFIRMED), and enable campus entry validation — all while preserving anonymity via a cryptographic identity vault.

### Technology Foundation

| Layer | Technology |
|---|---|
| Backend language | Java 21 (Spring Boot 3.2.4) |
| Build system | Gradle 8.14 (Kotlin DSL), multi-project |
| Graph database | Neo4j 5.26 |
| Relational database | PostgreSQL 16 |
| Message broker | Apache Kafka 7.6 (Confluent) |
| Cache | Redis 7.2 |
| Mobile/Web frontend | Expo (React Native), TypeScript |
| Auth | LDAP + Local DB dual-chain, JWT (HMAC-SHA256) |
| Container infra | Docker Compose (dev), Kubernetes (implied, not yet configured) |
| Test frameworks | JUnit 5, Mockito, Testcontainers, Spring Boot Test |

### Monorepo Layout

```
circleguard/
├── build.gradle.kts               # Root Gradle config (Java 21, Lombok, Kotlin)
├── settings.gradle.kts            # 8 service subprojects declared
├── docker-compose.dev.yml         # All middleware: PG, Neo4j, Kafka, Redis, LDAP
├── init-db.sql                    # Creates all 5 PostgreSQL databases
├── mobile/                        # Expo React Native app (iOS/Android/Web)
│   ├── app/                       # Expo Router screens
│   ├── components/                # Shared UI components
│   ├── hooks/                     # API hooks per service
│   ├── constants/Config.ts        # All service base URLs
│   └── utils/                     # Storage, proximity scanner, background task
└── services/
    ├── circleguard-auth-service/          # Port 8180
    ├── circleguard-identity-service/      # Port 8083
    ├── circleguard-promotion-service/     # Port 8088
    ├── circleguard-notification-service/  # Port 8082
    ├── circleguard-form-service/          # Port 8086
    ├── circleguard-file-service/          # Port 8085
    ├── circleguard-gateway-service/       # Port 8087
    └── circleguard-dashboard-service/     # Port 8084
```

---

## 2. Detected Microservices — Full Inventory

### 2.1 `circleguard-auth-service` (Port 8180)

**Purpose:** Authentication gateway and JWT token issuer.

**Responsibilities:**
- Dual-chain authentication: LDAP (university) → fallback to Local DB (guests/staff)
- Issues signed JWT tokens embedding `anonymousId` and `permissions` claims
- Generates short-lived QR tokens for campus entry (60s TTL)
- Visitor handoff token generation (`HANDOFF_TOKEN:<id>:<jwt>` format)
- RBAC management: roles → permissions mapping
- Exposes endpoint for permission-based user lookup (used by notification-service)

**Exposed endpoints:**
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/auth/login` | none | Dual-chain login |
| GET | `/api/v1/auth/qr/generate` | Bearer JWT | Generate rotating QR token |
| POST | `/api/v1/auth/visitor/handoff` | none | Visitor handoff token |
| GET | `/api/v1/users/permissions/{permissionName}` | none (internal) | Users by permission |

**Technologies:** Spring Boot, Spring Security, Spring LDAP, JPA, Flyway, JWT (jjwt), PostgreSQL (H2 in tests)

**Persistence:** PostgreSQL (`circleguard_auth`) — tables: `permissions`, `roles`, `role_permissions`, `local_users`, `user_roles`. Seeded with 5 Flyway migrations.

**Dependencies on other services:**
- Calls **identity-service** (`POST /api/v1/identities/map`) synchronously via `RestTemplate` during login to fetch/create `anonymousId`

**Who calls it:**
- Mobile app (login, QR generate)
- notification-service (GET users by permission — via `RestTemplate`)
- gateway-service (QR secret shared via config)

**Communication pattern:** Synchronous HTTP (REST)

**Existing tests:**
- `LoginControllerTest` — WebMvcTest, mocks AuthManager + IdentityClient ✅
- `application.yml` test profile (H2 in-memory) ✅
- Flyway migrations (V1–V5) well-structured ✅

---

### 2.2 `circleguard-identity-service` (Port 8083)

**Purpose:** Cryptographic identity vault — maps real identities to anonymous UUIDs.

**Responsibilities:**
- Deterministic anonymization: SHA-256 hash (blind index) + AES encryption of real identity
- Maps university email/LDAP username → `anonymousId` (UUID)
- Visitor registration with PII isolation
- Privileged de-anonymization (HEALTH_CENTER only), with Kafka audit event emission
- FERPA compliance layer

**Exposed endpoints:**
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/identities/map` | none | Map real → anonymous ID |
| POST | `/api/v1/identities/visitor` | none | Register visitor |
| GET | `/api/v1/identities/lookup/{id}` | `identity:lookup` perm | De-anonymize (audited) |

**Technologies:** Spring Boot, Spring Security (method security), JPA, Flyway, Kafka producer, JWT filter, PostgreSQL

**Persistence:** PostgreSQL (`circleguard_identity`) — `identity_mappings` table with encrypted `real_identity_encrypted` column (custom JPA `AttributeConverter`)

**Dependencies on other services:** None (called by others)

**Who calls it:**
- auth-service (during login)
- notification-service (implied via LMS)
- Mobile app (visitor flow)

**Communication pattern:** Synchronous HTTP; Kafka producer for audit events

**Existing tests:**
- `IdentityVaultControllerTest` — WebMvcTest with `@WithMockUser`, tests auth/403/404/audit ✅
- `IdentityMappingRepositoryTest` — `@DataJpaTest` with H2, tests encryption round-trip ✅
- `IdentityEncryptionConverterTest` — Unit test for AES converter ✅
- H2 test profile with PostgreSQL compatibility mode ✅

---

### 2.3 `circleguard-promotion-service` (Port 8088)

**Purpose:** Core health status engine — the most complex service. Manages status propagation, graph traversals, spatial data, and contact circle detection.

**Responsibilities:**
- Health status lifecycle: ACTIVE → SUSPECT → PROBABLE → CONFIRMED → RECOVERED
- Recursive two-hop Neo4j Cypher propagation (L1 → SUSPECT, L2 → PROBABLE)
- Pulse Recovery algorithm (resolveStatus with mandatory fence window enforcement)
- Redis L2 cache for entry-gate rapid validation
- Circle management (create/join/auto-detect from WiFi proximity)
- WiFi AP triangulation → encounter recording → graph edges
- MAC address session registry (Redis)
- Spatial hierarchy: Buildings → Floors → Access Points (PostgreSQL)
- System settings (singleton pattern)
- Kafka producer: `promotion.status.changed`, `circle.fenced`, `alert.priority`
- Kafka consumer: `survey.submitted`, `certificate.validated`
- Automated graph cleanup (14-day TTL cron)
- Status lifecycle automation (hourly fence window check)
- K-Anonymity filtered department statistics

**Exposed endpoints (partial):**
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/health/confirmed` | HEALTH_CENTER | Mark confirmed positive |
| POST | `/api/v1/health/resolve` | HEALTH_CENTER | Release from fence |
| POST | `/api/v1/health/recovery/{id}` | HEALTH_CENTER | Mark as recovered |
| GET | `/api/v1/health-status/stats` | none | Campus-wide stats |
| GET | `/api/v1/health-status/stats/department/{dept}` | none | Department stats |
| POST | `/api/v1/location/signal` | none | WiFi AP signal ingest |
| POST | `/api/v1/sessions/handshake` | none | MAC→anonymousId mapping |
| GET | `/api/v1/mesh/stats/{anonymousId}` | none | Mesh visualization stats |
| POST | `/api/v1/circles` | none | Create circle |
| POST | `/api/v1/circles/join/{code}/user/{id}` | none | Join circle |
| GET | `/api/v1/circles/user/{anonymousId}` | none | User's circles |
| POST | `/api/v1/encounters/report` | none | Report BLE encounter |
| GET/POST | `/api/v1/buildings/**` | ADMIN | Building CRUD |
| GET/POST | `/api/v1/floors/**` | ADMIN | Floor/AP CRUD |
| GET | `/api/v1/admin/settings` | none | System settings |
| POST | `/api/v1/admin/settings` | none | Update settings |

**Technologies:** Spring Boot, Spring Data JPA + Neo4j (hybrid), Spring Data Redis, Kafka, Spring Security, JWT, Flyway, Caffeine cache, Testcontainers

**Persistence:**
- PostgreSQL (`circleguard_promotion`): buildings, floors, access_points, system_settings (Flyway V1–V3)
- Neo4j: User nodes, Circle nodes, ENCOUNTERED/MEMBER_OF relationships

**Dependencies on other services:** None at runtime (Kafka-based)

**Who calls it:**
- form-service (Kafka consumer: survey.submitted)
- notification-service (Kafka consumer: promotion.status.changed, circle.fenced, alert.priority)
- dashboard-service (HTTP GET health stats)
- Mobile app (all user-facing flows)

**Communication pattern:** Synchronous HTTP + Kafka producer + Kafka consumer

**Existing tests:**
- `HealthStatusControllerTest` — WebMvcTest + security ✅
- `HealthStatusServiceTest` — SpringBootTest with deep-stub Mockito ✅
- `HealthStatusReevaluationTest` — Full integration with Neo4j Testcontainer ✅
- `AdministrativeCorrectionTest` — Neo4j + Redis Testcontainers ✅
- `StatusLifecycleTest` — SpringBootTest with mocked Neo4j ✅
- `SurveyListenerTest` — MockitoExtension unit test ✅
- `FloorServiceTest` — Pure unit test ✅
- `PromotionPerformanceTest` — Testcontainers performance benchmark ✅

---

### 2.4 `circleguard-notification-service` (Port 8082)

**Purpose:** Multi-channel notification dispatcher triggered by health status events.

**Responsibilities:**
- Kafka consumer: `promotion.status.changed` → dispatches email/SMS/push
- Kafka consumer: `circle.fenced` → cancels room reservations
- Kafka consumer: `alert.priority` → notifies HEALTH_CENTER admins
- Multi-channel: Email (JavaMailSender), SMS (Twilio), Push (Gotify)
- Freemarker email templates (health_alert.ftl)
- Spring Retry (3 attempts, 2s backoff) on all channels
- Async delivery with CompletableFuture
- Audit log emission to Kafka (`notification.audit`)
- LMS integration for remote attendance sync (async)
- Mock mode (MOCK_TOKEN / MOCK_SID config flags)

**Exposed endpoints:** None (pure event-driven consumer)

**Technologies:** Spring Boot, Kafka consumer, Spring Mail, Twilio SDK, WebFlux (Gotify push), Freemarker, Spring Retry, Spring Async

**Persistence:** None (stateless)

**Dependencies on other services:**
- auth-service: HTTP GET `/api/v1/users/permissions/alert:receive_priority` (via RestTemplate) for admin email dispatch

**Who calls it:** Nobody via HTTP; triggered by Kafka events from promotion-service

**Communication pattern:** Purely asynchronous Kafka consumer

**Existing tests:**
- `NotificationDispatcherTest` — SpringBootTest, concurrent dispatch verification ✅
- `NotificationRetryTest` — SpringBootTest, retry count verification ✅
- `ExposureNotificationListenerTest` — SpringBootTest ✅
- `PriorityAlertListenerTest` — Mockito, RestTemplate mock ✅
- `TemplateServiceTest` — SpringBootTest, Freemarker output ✅
- `LmsServiceTest` — SpringBootTest ✅
- `RoomReservationServiceTest` — SpringBootTest ✅

---

### 2.5 `circleguard-form-service` (Port 8086)

**Purpose:** Dynamic health survey engine with certificate management.

**Responsibilities:**
- Dynamic questionnaire management (versioned, activatable)
- Health survey submission with symptom detection (SymptomMapper)
- Kafka producer: `survey.submitted` (triggers promotion-service)
- Kafka producer: `certificate.validated` (triggers status restoration)
- Medical certificate upload (local storage at `/tmp/circleguard-uploads`)
- Validation workflow: PENDING → APPROVED/REJECTED (admin)
- K-Anonymity NOT applied here (raw data stored)

**Exposed endpoints:**
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/questionnaires/active` | none | Get active questionnaire |
| GET | `/api/v1/questionnaires` | none | All questionnaires |
| POST | `/api/v1/questionnaires` | none | Create questionnaire |
| POST | `/api/v1/questionnaires/{id}/activate` | none | Activate questionnaire |
| POST | `/api/v1/surveys` | none | Submit survey |
| GET | `/api/v1/certificates/pending` | none | Pending cert reviews |
| POST | `/api/v1/certificates/{id}/validate` | none | Validate certificate |
| POST | `/api/v1/attachments` | none | Upload file |

**Technologies:** Spring Boot, JPA, Flyway, Kafka producer, PostgreSQL, Hibernate JSON (JSONB)

**Persistence:** PostgreSQL (`circleguard_form`) — questionnaires, questions, health_surveys with JSONB responses column (Flyway V1–V5)

**Dependencies on other services:** None at runtime

**Who calls it:**
- Mobile app (survey submission, questionnaire fetch)
- promotion-service (Kafka consumer: `survey.submitted`)

**Communication pattern:** Synchronous HTTP + Kafka producer

**Existing tests:**
- `QuestionnaireControllerTest` — WebMvcTest ✅
- `HealthSurveyControllerTest` — WebMvcTest ✅
- `AttachmentControllerTest` — SpringBootTest + MockMvc ✅
- `SymptomMapperTest` — Pure unit test ✅

---

### 2.6 `circleguard-gateway-service` (Port 8087)

**Purpose:** Campus entry gate QR token validator.

**Responsibilities:**
- Validates short-lived JWT QR tokens (signed with separate `qr.secret`)
- Checks Redis for user's current health status (`user:status:{anonymousId}`)
- Returns GREEN (allow entry) or RED (deny) based on status
- Stateless — no database, no Kafka

**Exposed endpoints:**
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/gate/validate` | none | Validate QR token → GREEN/RED |

**Technologies:** Spring Boot, Spring Data Redis, JWT (jjwt)

**Persistence:** Redis read-only (status written by promotion-service)

**Dependencies on other services:**
- Redis (shared with promotion-service — reads `user:status:*` keys set by promotion-service)

**Who calls it:**
- Mobile app scan screen (`useGateValidation` hook)
- Gate hardware terminals (implied)

**Communication pattern:** Synchronous HTTP; reads Redis populated by promotion-service

**Existing tests:**
- `QrValidationServiceTest` — Unit test with Mockito Redis mock ✅
- `GateControllerTest` — WebMvcTest ✅
- `QrValidationServiceTest` — Tests GREEN/RED logic ✅

---

### 2.7 `circleguard-dashboard-service` (Port 8084)

**Purpose:** Analytics and reporting layer with K-Anonymity privacy protection.

**Responsibilities:**
- Proxies health stats from promotion-service
- K-Anonymity filter (K=5 default) on department-level data
- Entry trend queries from local PostgreSQL `entry_logs` table
- Time-series mock data generation (table may not exist yet)
- Global health board stats

**Exposed endpoints:**
| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/analytics/summary` | none | Campus-wide summary |
| GET | `/api/v1/analytics/health-board` | none | Global health stats |
| GET | `/api/v1/analytics/department/{dept}` | none | Dept stats (K-anon) |
| GET | `/api/v1/analytics/trends/{locationId}` | none | Entry trends |
| GET | `/api/v1/analytics/time-series` | none | Time-series data |

**Technologies:** Spring Boot, JPA (JdbcTemplate), Flyway, RestTemplate (to promotion-service)

**Persistence:** PostgreSQL (`circleguard_dashboard`) — `entry_logs` table

**Dependencies on other services:**
- promotion-service: HTTP GET `/api/v1/health-status/stats` and `/stats/department/{dept}` via RestTemplate

**Who calls it:**
- Mobile admin dashboard (`http://localhost:8084/api/v1/analytics/summary`)

**Communication pattern:** Synchronous HTTP (client to promotion-service)

**Existing tests:**
- `AnalyticsControllerTest` — WebMvcTest ✅ (but **currently FAILING** — missing `@SpringBootConfiguration`, confirmed in `dashboard_test_output.txt`)

---

### 2.8 `circleguard-file-service` (Port 8085)

**Purpose:** Secure file storage for medical certificates and floor plans.

**Responsibilities:**
- File upload and local disk storage (under `./uploads/`)
- UUID-prefixed filename generation
- Download endpoint (stub — returns null Resource)

**Exposed endpoints:**
| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/files/upload` | none | Upload file |

**Technologies:** Spring Boot, Spring Web (multipart), minimal

**Persistence:** Local filesystem only (no database)

**Dependencies:** None

**Existing tests:**
- `FileUploadControllerTest` — WebMvcTest ✅

---

## 3. Service Responsibilities Map

```
                    ┌─────────────────┐
                    │   Mobile App    │
                    │  (Expo/RN/Web)  │
                    └────────┬────────┘
                             │ HTTP (all services)
          ┌──────────────────┼──────────────────────┐
          │                  │                      │
    ┌─────▼──────┐    ┌──────▼──────┐    ┌─────────▼───────┐
    │auth-service│    │form-service │    │gateway-service   │
    │   :8180    │    │   :8086     │    │   :8087          │
    └──────┬─────┘    └──────┬──────┘    └─────────┬────────┘
           │ HTTP             │ Kafka                │ Redis
           │ (login)          │ survey.submitted     │ (reads status)
           ▼                  ▼                      │
    ┌─────────────────────────────────────────────────────┐
    │              promotion-service :8088                 │
    │  (Neo4j + PostgreSQL + Redis + Kafka producer)       │
    └──────────────────────┬──────────────────────────────┘
                           │ Kafka
           ┌───────────────┼───────────────┐
           │               │               │
    ┌──────▼──────┐  ┌─────▼──────┐  ┌────▼────────────┐
    │notification │  │ dashboard  │  │ identity-service │
    │-service:8082│  │-service:8084│  │   :8083          │
    └─────────────┘  └─────┬──────┘  └──────────────────┘
                           │ HTTP
                    ┌──────▼──────┐
                    │ promotion-  │
                    │ service     │ (dashboard proxies stats)
                    └─────────────┘
```

---

## 4. Communication Patterns

### Synchronous HTTP Flows
| Caller | Callee | Endpoint | Trigger |
|---|---|---|---|
| auth-service | identity-service | POST `/api/v1/identities/map` | Every login |
| notification-service | auth-service | GET `/api/v1/users/permissions/alert:receive_priority` | Priority alert |
| dashboard-service | promotion-service | GET `/api/v1/health-status/stats` | Analytics request |
| Mobile app | auth-service | POST `/api/v1/auth/login` | User login |
| Mobile app | auth-service | GET `/api/v1/auth/qr/generate` | Every 60s |
| Mobile app | gateway-service | POST `/api/v1/gate/validate` | QR scan |
| Mobile app | form-service | POST `/api/v1/surveys` | Symptom report |
| Mobile app | promotion-service | GET `/api/v1/mesh/stats/{id}` | Dashboard poll |
| Mobile app | promotion-service | POST/GET `/api/v1/circles/**` | Circle management |

### Asynchronous Kafka Flows
| Producer | Topic | Consumer | Trigger |
|---|---|---|---|
| form-service | `survey.submitted` | promotion-service | Survey with symptoms |
| promotion-service | `promotion.status.changed` | notification-service | Status change |
| promotion-service | `circle.fenced` | notification-service | Full-fence detected |
| promotion-service | `alert.priority` | notification-service | Confirmed/large outbreak |
| promotion-service | `promotion.status.changed` | (dashboard future) | Status analytics |
| identity-service | `audit.identity.accessed` | (audit future) | De-anonymization |
| notification-service | `notification.audit` | (audit future) | Delivery tracking |

### Shared Infrastructure
| Resource | Services | Usage |
|---|---|---|
| Redis | promotion-service (write), gateway-service (read) | `user:status:{id}` keys |
| PostgreSQL | auth, identity, promotion, form, dashboard | Separate databases |
| Neo4j | promotion-service only | Graph traversals |
| Kafka | form, promotion, notification, identity | All async events |

---

## 5. Deployment Architecture

### Current State (docker-compose.dev.yml)
```yaml
Middleware containers:
- postgres:16           → port 5432
- neo4j:5.26           → ports 7474, 7687
- zookeeper (Confluent) → port 2181
- kafka (Confluent)     → port 9092
- redis:7.2            → port 6379
- openldap:1.5.0       → ports 389, 636
```

### Service Ports
```
8082 - notification-service
8083 - identity-service
8084 - dashboard-service
8085 - file-service
8086 - form-service
8087 - gateway-service
8088 - promotion-service
8180 - auth-service
```

### Missing Infrastructure (to be created)
- `Dockerfile` per service (none exist)
- Kubernetes manifests (none exist)
- Jenkins pipeline files (none exist)
- `.env` files (all secrets hardcoded in application.yml — **security risk**)
- Health check endpoints (`/actuator/health` — not yet added)
- Docker network definitions beyond compose

### Secrets / Configuration Issues Found
```yaml
# Hardcoded in application.yml (dev only — acceptable for workshop):
jwt.secret: "my-super-secret-dev-key-32-chars-long-12345678"
qr.secret: "my-qr-secret-key-for-dev-1234567890"
vault.secret: "746573742d7365637265742d33322d63686172732d6c6f6e672d313233343536"
DB password: "password"
Neo4j password: "password"
```

---

## 6. Existing CI/CD Assets

**Current state: NONE.** No CI/CD configuration files exist:
- No `Jenkinsfile`
- No `.github/workflows/`
- No `gitlab-ci.yml`
- No `Makefile`
- No deployment scripts

Only `gradlew` / `gradlew.bat` Gradle wrapper scripts exist.

---

## 7. Existing Testing Assets

### Backend (Java/Spring)

| Service | Test Class | Type | Framework | Status |
|---|---|---|---|---|
| auth | `LoginControllerTest` | WebMvcTest | JUnit5+Mockito | ✅ Pass |
| identity | `IdentityVaultControllerTest` | WebMvcTest | JUnit5+Mockito | ✅ Pass |
| identity | `IdentityMappingRepositoryTest` | DataJpaTest | H2 | ✅ Pass |
| identity | `IdentityEncryptionConverterTest` | Unit | JUnit5 | ✅ Pass |
| promotion | `HealthStatusControllerTest` | WebMvcTest | Mockito+Security | ✅ Pass |
| promotion | `HealthStatusServiceTest` | SpringBootTest | Deep-stub Mockito | ✅ Pass |
| promotion | `HealthStatusReevaluationTest` | Integration | Neo4j Testcontainer | ✅ Pass |
| promotion | `AdministrativeCorrectionTest` | Integration | Neo4j+Redis TC | ✅ Pass |
| promotion | `StatusLifecycleTest` | SpringBootTest | Mockito | ✅ Pass |
| promotion | `SurveyListenerTest` | Unit | MockitoExtension | ✅ Pass |
| promotion | `FloorServiceTest` | Unit | MockitoExtension | ✅ Pass |
| promotion | `PromotionPerformanceTest` | Performance | Neo4j Testcontainer | ✅ Pass |
| notification | `NotificationDispatcherTest` | SpringBootTest | Mockito | ✅ Pass |
| notification | `NotificationRetryTest` | SpringBootTest | Mockito | ✅ Pass |
| notification | `ExposureNotificationListenerTest` | SpringBootTest | Mockito | ✅ Pass |
| notification | `PriorityAlertListenerTest` | Unit | Mockito | ✅ Pass |
| notification | `TemplateServiceTest` | SpringBootTest | Freemarker | ✅ Pass |
| notification | `LmsServiceTest` | SpringBootTest | — | ✅ Pass |
| notification | `RoomReservationServiceTest` | SpringBootTest | — | ✅ Pass |
| form | `QuestionnaireControllerTest` | WebMvcTest | Mockito | ✅ Pass |
| form | `HealthSurveyControllerTest` | WebMvcTest | Mockito | ✅ Pass |
| form | `AttachmentControllerTest` | SpringBootTest | MockMvc | ✅ Pass |
| form | `SymptomMapperTest` | Unit | JUnit5 | ✅ Pass |
| gateway | `QrValidationServiceTest` | Unit | Mockito+Redis | ✅ Pass |
| gateway | `GateControllerTest` | WebMvcTest | Mockito | ✅ Pass |
| dashboard | `AnalyticsControllerTest` | WebMvcTest | Mockito | ❌ **FAILING** |
| file | `FileUploadControllerTest` | WebMvcTest | Mockito | ✅ Pass |

**Known failing test:** `AnalyticsControllerTest` fails with `IllegalStateException: Unable to find a @SpringBootConfiguration`. Root cause: the test uses `@WebMvcTest` but `circleguard-dashboard-service` has no Spring Boot main class discoverable in the test classpath context. Fix: add `@SpringBootApplication` discovery path or add `classes=DashboardApplication.class` to `@WebMvcTest`.

### Frontend (TypeScript/Jest)

| Test File | Status | Issue |
|---|---|---|
| `useQrToken.test.ts` | ❌ **FAILING** | Babel plugin config error (`react-native-env.js` `.plugins` invalid). Expo 55 + Jest version mismatch. |
| `DynamicForm.test.tsx` | Unknown | Listed in code, likely same Babel issue |

---

## 8. Docker/Kubernetes Analysis

### Docker Readiness Per Service

| Service | Dockerizable? | Complexity | Notes |
|---|---|---|---|
| auth-service | ✅ High | Low-Medium | Needs LDAP + PG at runtime; test profile uses H2 |
| identity-service | ✅ High | Low | Clean JPA, well-tested, H2 for tests |
| promotion-service | ✅ Medium | High | Requires Neo4j + PG + Redis + Kafka; complex wiring |
| notification-service | ✅ High | Low | Stateless consumer; mock mode for SMTP/Twilio |
| form-service | ✅ High | Low | Standard JPA + Kafka; clean |
| gateway-service | ✅ High | Very Low | Minimal deps (Redis + JWT only) |
| dashboard-service | ✅ Medium | Medium | Depends on promotion-service at runtime |
| file-service | ✅ High | Very Low | No external deps; local filesystem |

### Kubernetes Readiness
All services lack:
- `Dockerfile` (must be created)
- Kubernetes `Deployment`, `Service`, `ConfigMap`, `Secret` manifests
- Health probes (`/actuator/health` — Spring Actuator not yet declared in dependencies)
- Horizontal Pod Autoscaler configs
- Persistent Volume Claims (for Neo4j, PostgreSQL)

**Suggested Dockerfile pattern (standard for all services):**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
COPY build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

---

## 9. Testability & DevOps Feasibility Matrix

| Service | Build | Runtime | Mock Ease | Unit | Integration | E2E | Locust | Pipeline | K8s | Risk | Value |
|---|---|---|---|---|---|---|---|---|---|---|---|
| auth-service | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ | ✅ | ✅(H2) | ✅ | ✅ | ✅ | ✅ | Medium | **HIGH** |
| identity-service | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ✅ | ✅(H2) | ✅ | ✅ | ✅ | ✅ | Low | **HIGH** |
| promotion-service | ⭐ | ⭐ | ⭐⭐ | ✅ | ✅(TC) | ✅ | ✅ | ⭐⭐ | ⭐ | High | **HIGH** |
| notification-service | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Low | **HIGH** |
| form-service | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ✅ | ✅(H2) | ✅ | ✅ | ✅ | ✅ | Low | **HIGH** |
| gateway-service | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Very Low | **HIGH** |
| dashboard-service | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ❌(broken) | ⭐⭐ | ⭐⭐ | ✅ | ⭐⭐ | ⭐⭐ | Medium | **MEDIUM** |
| file-service | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ✅ | ✅ | ⭐ | ⭐ | ✅ | ✅ | Very Low | **LOW** |

---

## 10. Recommended 6 Microservices

### ✅ Selected Services

```
1. circleguard-auth-service          (Port 8180)
2. circleguard-identity-service      (Port 8083)
3. circleguard-form-service          (Port 8086)
4. circleguard-promotion-service     (Port 8088)
5. circleguard-gateway-service       (Port 8087)
6. circleguard-notification-service  (Port 8082)
```

---

## 11. Justification — Why These 6

### Service 1: `auth-service` — The Entry Point
**Why selected:**
- Every user journey begins with login → auth-service issues JWTs
- Synchronously calls identity-service (direct inter-service integration)
- Provides QR tokens consumed by gateway-service (indirect coupling via shared secret + Redis)
- Has clean, existing tests (H2 profile, no external infra needed for unit tests)
- Well-defined RBAC via Flyway migrations — great for demonstrating environment-specific seeding
- The `DualChainAuthenticationProvider` makes it interesting for failure/fallback integration tests
- **Pipeline value:** Fastest build, simplest Docker image, excellent smoke test candidate

### Service 2: `identity-service` — The Privacy Core
**Why selected:**
- Called synchronously by auth-service on every login — creates a direct HTTP integration test opportunity
- Emits Kafka audit events — demonstrates event-driven observability
- Custom JPA `AttributeConverter` (AES encryption) makes unit tests technically interesting
- H2 test profile with PostgreSQL compatibility mode — easy CI without infra
- Well-isolated with no downstream dependencies of its own
- FERPA compliance angle makes it great for workshop narrative (privacy in distributed systems)
- **Pipeline value:** Fast build, existing passing tests, clean Dockerfile candidate

### Service 3: `form-service` — The User Interaction Layer
**Why selected:**
- Represents the user-facing health reporting flow (Submit Survey → Kafka → Promotion)
- Kafka producer to promotion-service → enables full async integration test
- Questionnaire CRUD makes for excellent REST API testing scenarios
- Clean JPA + Flyway migrations, testable with H2
- File upload endpoint tests multipart handling
- 4 passing test classes already exist
- **Pipeline value:** Standard Spring MVC pattern, easy to containerize, great for Locust performance tests on survey submission endpoint

### Service 4: `promotion-service` — The Business Core
**Why selected:**
- Central to ALL business flows — cannot have meaningful E2E without it
- Kafka consumer (form-service events) + Kafka producer (notification events) → hub of the event chain
- Neo4j graph traversals demonstrate distributed data processing
- Redis cache shared with gateway-service → real shared-state integration test
- Existing Neo4j Testcontainer tests demonstrate Kubernetes-style ephemeral infra
- Health status endpoints are ideal Locust performance test targets
- `PromotionPerformanceTest` already implements a 10,000-node benchmark — extend this with Locust externally
- **Pipeline value:** Most complex but highest workshop value; needs Neo4j/Redis sidecars in K8s

### Service 5: `gateway-service` — The Validator
**Why selected:**
- The simplest service architecturally — ideal for demonstrating fast pipeline builds
- Creates a critical integration path: promotion-service writes Redis → gateway-service reads Redis
- QR token validation is a natural Locust scenario (simulate gate scanners under load)
- No database, no Kafka — pure Redis reader + JWT verifier
- Demonstrates how stateless microservices share state through external stores
- Existing tests for GREEN/RED logic, easy to expand
- **Pipeline value:** 30-second build, 1-command Docker run, excellent blue/green deployment demo

### Service 6: `notification-service` — The Event Consumer
**Why selected:**
- Closes the full E2E loop: login → report → promote → notify
- Demonstrates async event-driven architecture (Kafka consumer)
- Spring Retry + mock mode makes it testable without real SMTP/Twilio
- The retry test (`NotificationRetryTest`) is already a performance/reliability test
- `alert.priority` consumer shows cross-service permission lookup (calls auth-service)
- All 7 existing tests pass — best test coverage ratio in the system
- **Pipeline value:** Stateless consumer = easiest K8s deployment (no PVC needed)

---

## 12. Business Flow Implemented Together

### Primary E2E Flow: "Health Fencing Cascade"

```
Step 1: Student logs in
  Mobile App → POST /api/v1/auth/login → auth-service
                                       ↓ (sync HTTP)
                                       identity-service (create/fetch anonymousId)
                                       ↓
                                       JWT token returned

Step 2: Student reports symptoms
  Mobile App → POST /api/v1/surveys → form-service
                                    ↓ (SymptomMapper detects fever/cough)
                                    Kafka: "survey.submitted" {anonymousId, hasSymptoms: true}

Step 3: Promotion engine cascades
  promotion-service (Kafka consumer)
    → SET user status = SUSPECT in Neo4j
    → Propagate PROBABLE to L2 contacts
    → SET user:status:{id} = SUSPECT in Redis
    → Kafka: "promotion.status.changed"

Step 4: Campus entry denied
  Student scans QR code → POST /api/v1/gate/validate → gateway-service
                                                       ↓ (reads Redis)
                                                       → Status = SUSPECT → return RED

Step 5: Notifications dispatched
  notification-service (Kafka consumer: promotion.status.changed)
    → Email via JavaMailSender (mocked)
    → SMS via Twilio (mocked)
    → Push via Gotify (mocked)
    → Kafka: "notification.audit"
```

This is a **complete, real, testable business transaction** spanning all 6 selected services.

---

## 13. Integration Tests Enabled

| Test Scenario | Services Involved | Method |
|---|---|---|
| Login creates anonymous ID | auth-service → identity-service | HTTP mock or real (H2) |
| Survey submission triggers status change | form-service → Kafka → promotion-service | Embedded Kafka + Testcontainers |
| Status change denies gate entry | promotion-service (Redis write) → gateway-service (Redis read) | Redis Testcontainer |
| Priority alert fetches admin list | notification-service → auth-service | WireMock or MockServer |
| QR token validated after login | auth-service (generate) → gateway-service (validate) | Shared JWT secret + Redis |
| Health status propagation | promotion-service Neo4j | Neo4j Testcontainer (already exists) |

---

## 14. E2E Scenarios

| Scenario | Steps | Expected Outcome |
|---|---|---|
| **Happy path: healthy student enters campus** | Login → Generate QR → Validate QR | GREEN access granted |
| **Sick student blocked at gate** | Login → Report fever symptoms → System cascades → Validate QR | RED — access denied |
| **Recovery flow** | Admin resolves status → QR validated again | GREEN restored |
| **Circle fencing** | Multiple users in same location → Circle formed → One confirmed → All fenced | Notification cascade |
| **Visitor registration** | Fill form → QR generated → Scanned by mobile → Entry granted | Visitor onboarded |

---

## 15. Performance Tests (Locust)

| Scenario | Target Service | Locust Task | Success Metric |
|---|---|---|---|
| Concurrent login load | auth-service | `POST /login` with valid creds | < 200ms p95 |
| Gate validation throughput | gateway-service | `POST /gate/validate` with valid QR | > 500 RPS |
| Survey submission spike | form-service | `POST /surveys` | < 500ms p95 |
| Health stats dashboard | promotion-service | `GET /health-status/stats` | < 300ms, cached |
| Status promotion cascade | promotion-service | `POST /health/confirmed` | < 1000ms (NFR-1) |
| Concurrent QR scans | auth-service + gateway | Generate + Validate | < 200ms end-to-end |

---

## 16. Services NOT Selected and Why

### ❌ `dashboard-service`
- **Reason 1:** Existing `AnalyticsControllerTest` is **broken** (confirmed failure in test output) — adds debt
- **Reason 2:** It purely proxies promotion-service data — no unique business logic to test
- **Reason 3:** JdbcTemplate time-series queries reference a table (`status_events`) that may not exist — brittle
- **Reason 4:** Replaced by promotion-service's own `/health-status/stats` endpoints for workshop purposes

### ❌ `file-service`
- **Reason 1:** Trivial implementation — `FileStorageService` writes to local disk, no integration points
- **Reason 2:** No meaningful Kafka/HTTP inter-service communication
- **Reason 3:** The download endpoint returns `null` — incomplete implementation
- **Reason 4:** Low workshop value — a basic file upload offers little for testing or DevOps demos
- **Reason 5:** Functionality is partially duplicated in form-service's `StorageService`

---

## 17. Risks

| Risk | Severity | Service | Mitigation |
|---|---|---|---|
| Hardcoded secrets in application.yml | High | All | Use Kubernetes Secrets + ConfigMap overrides |
| No Spring Actuator in dependencies | Medium | All | Add `spring-boot-starter-actuator` to each build.gradle.kts |
| Neo4j Testcontainer pulls 1GB+ image | Medium | promotion-service | Pre-pull in CI or use docker layer caching |
| dashboard-service test is broken | Low | dashboard | Fix before adding to pipeline; not in scope |
| Mobile Babel/Jest config broken | Low | mobile | Separate concern; fix babel-preset-expo version |
| promotion-service Cypher complexity | High | promotion | NFR-1 (<1s) tested; monitor via metrics |
| LDAP dependency at startup | Medium | auth-service | DualChainProvider falls back to local DB if LDAP fails |
| File storage uses local filesystem | Medium | file-service, form-service | Replace with S3/MinIO for K8s (stateful concern) |
| No health endpoints | Medium | All | Add Actuator for Kubernetes liveness/readiness probes |
| Redis TTL eviction races | Low | gateway-service | Status keys have no explicit TTL — could serve stale data |

---

## 18. Suggested Next Steps (Implementation Phases)

### Phase 2: Docker Containerization
1. Create `Dockerfile` for each of the 6 selected services
2. Create multi-stage Gradle build Dockerfile (builder + runtime JRE image)
3. Create `docker-compose.yml` for all 6 services + middleware
4. Add Spring Actuator to all services; expose `/actuator/health`
5. Move secrets to Docker environment variables

### Phase 3: Kubernetes Manifests
1. Create `k8s/` directory with namespaces: `dev`, `staging`, `prod`
2. Create `Deployment`, `Service`, `ConfigMap`, `Secret` per service
3. Create StatefulSet for Neo4j with PVC
4. Create Kafka + Zookeeper StatefulSets
5. Add liveness/readiness probes using `/actuator/health`
6. Create Horizontal Pod Autoscaler for gateway-service and form-service

### Phase 4: Jenkins Pipeline
1. `Jenkinsfile` with stages: Build → Test → Docker Build → Push → Deploy
2. Multi-branch pipeline: `dev` → `staging` → `main`/`prod`
3. Parameterized deployments by service
4. Test reports via JUnit XML publisher
5. Docker registry integration (Docker Hub or local registry)
6. Release notes generation from git log / conventional commits

### Phase 5: Test Suite Expansion
1. Fix `dashboard-service` broken test
2. Add `@SpringBootTest` integration tests for auth → identity HTTP call
3. Add embedded Kafka tests for form → promotion Kafka flow
4. Add Redis Testcontainer for promotion → gateway shared state test
5. Write Locust performance test scripts for all 6 services

### Phase 6: E2E Tests
1. REST Assured or HTTPie-based E2E test script covering the full health fencing cascade
2. Testcontainers-based E2E test that spins up all 6 services
3. CI gate: E2E only runs on `staging` branch

---

## 19. Quick Reference — Service Startup Order

For local development or CI environments, start services in this order to respect dependencies:

```
1. docker-compose up postgres neo4j kafka zookeeper redis openldap
2. ./gradlew :services:circleguard-identity-service:bootRun
3. ./gradlew :services:circleguard-auth-service:bootRun
4. ./gradlew :services:circleguard-form-service:bootRun
5. ./gradlew :services:circleguard-promotion-service:bootRun
6. ./gradlew :services:circleguard-gateway-service:bootRun
7. ./gradlew :services:circleguard-notification-service:bootRun
```

---

## 20. Key Configuration Reference

### Environment Variables (to externalize from application.yml)

| Variable | Default (dev) | Used By |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/circleguard_*` | All JPA services |
| `SPRING_NEO4J_URI` | `bolt://localhost:7687` | promotion-service |
| `SPRING_DATA_REDIS_HOST` | `localhost` | promotion, gateway |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | form, promotion, notification |
| `JWT_SECRET` | `my-super-secret-dev-key-32-chars-long-12345678` | auth, promotion, identity, gateway |
| `QR_SECRET` | `my-qr-secret-key-for-dev-1234567890` | auth, gateway |
| `VAULT_SECRET` | `746573742d...` | identity |
| `AUTH_API_URL` | `http://localhost:8180` | notification, mobile |
| `IDENTITY_SERVICE_URL` | `http://localhost:8083` | auth (IdentityClient) |

---

*Document generated by architectural analysis of the CircleGuard monorepo.*  
*Last analyzed: May 2026. Repository state: pre-DevOps, pre-Kubernetes.*
