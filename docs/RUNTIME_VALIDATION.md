# Container Runtime Validation

This document summarizes the validation of the containerization foundation implemented in Phase 1 and 1.5, focusing on runtime behavior and Kubernetes readiness.

## Docker Validation Results
- **Images Built Successfully:** All 6 services were built using the multi-stage Dockerfiles.
- **Artifact Copy Paths:** `COPY --from=build /app/services/circleguard-<service>/build/libs/*.jar app.jar` functions correctly.
- **Jar Naming Assumptions:** Gradle cleanly produces executable Spring Boot fat jars matching the wildcard.
- **Port Exposure:** 
  - `auth-service`: 8180
  - `identity-service`: 8083
  - `form-service`: 8086
  - `promotion-service`: 8088
  - `gateway-service`: 8087
  - `notification-service`: 8082
- **Runtime Image:** `eclipse-temurin:21-jre-jammy` successfully runs the JDK 21 compiled artifacts.

## Startup Validation Results
The containers correctly execute `java -XX:MaxRAMPercentage=75.0 -jar app.jar` upon startup.
When middleware (PostgreSQL, Kafka, Redis, Neo4j, LDAP) is not reachable, the applications behave as expected:
- The JVM starts cleanly.
- Spring Boot initializes the application context.
- Beans attempting to connect to data sources fail and the application exits. 
- In Kubernetes, this results in a standard **CrashLoopBackOff**, which resolves automatically once the corresponding middleware services become healthy.

## Environment Variable Matrix

| Variable Name | Owning Service(s) | Purpose | Required | Default Value (K8s Service) | Future K8s Source |
|---|---|---|---|---|---|
| `SERVER_PORT` | All | Port binding | No | Specific to service | None |
| `DB_HOST` | auth, identity, form, promotion | PostgreSQL host | No | `postgres-service` | ConfigMap |
| `DB_PORT` | auth, identity, form, promotion | PostgreSQL port | No | `5432` | ConfigMap |
| `DB_NAME` | auth, identity, form, promotion | PostgreSQL database name | No | Specific to service | ConfigMap |
| `DB_USERNAME` | auth, identity, form, promotion | DB credential | Yes | N/A (Admin in dev) | Secret |
| `DB_PASSWORD` | auth, identity, form, promotion | DB credential | Yes | N/A (Password in dev)| Secret |
| `KAFKA_BOOTSTRAP_SERVERS` | form, promotion, notification | Kafka host | No | `kafka-service:9092` | ConfigMap |
| `REDIS_HOST` | promotion, gateway | Redis host | No | `redis-service` | ConfigMap |
| `REDIS_PORT` | promotion, gateway | Redis port | No | `6379` | ConfigMap |
| `NEO4J_HOST` | promotion | Neo4j host | No | `neo4j-service` | ConfigMap |
| `NEO4J_PORT` | promotion | Neo4j port (Bolt) | No | `7687` | ConfigMap |
| `NEO4J_USERNAME` | promotion | Neo4j credential | Yes | N/A (neo4j in dev) | Secret |
| `NEO4J_PASSWORD` | promotion | Neo4j credential | Yes | N/A (password in dev)| Secret |
| `LDAP_HOST` | auth | OpenLDAP host | No | `openldap-service` | ConfigMap |
| `LDAP_PORT` | auth | OpenLDAP port | No | `389` | ConfigMap |
| `LDAP_BASE` | auth | OpenLDAP base DN | No | `dc=circleguard,dc=edu` | ConfigMap |
| `LDAP_USER` | auth | OpenLDAP bind user | Yes | N/A (admin in dev) | Secret |
| `LDAP_PASSWORD` | auth | OpenLDAP bind password | Yes | N/A (admin in dev) | Secret |
| `MAIL_HOST` | notification | SMTP server | No | `mail-service` | ConfigMap |
| `MAIL_PORT` | notification | SMTP port | No | `25` | ConfigMap |
| `AUTH_API_URL` | notification | Auth API base URL | No | `http://circleguard-auth-service:8180` | ConfigMap |
| `JWT_SECRET` | auth, identity, promotion, gateway, notification | JWT signing key | Yes | N/A | Secret |
| `JWT_EXPIRATION` | auth, promotion, gateway, notification | JWT TTL | No | `3600000` | ConfigMap |
| `QR_SECRET` | auth, gateway, notification | QR signing key | Yes | N/A | Secret |
| `QR_EXPIRATION` | auth, gateway, notification | QR TTL | No | `300` | ConfigMap |
| `VAULT_SECRET` | identity | AES encryption key | Yes | N/A | Secret |
| `VAULT_SALT` | identity | AES encryption salt | Yes | N/A | Secret |
| `VAULT_HASH_SALT` | identity | Hash blind index salt | Yes | N/A | Secret |

## Kubernetes DNS Preparation
All default values in the `application.yml` files have been modified to point to standard Kubernetes service names instead of `localhost`. This eliminates localhost dependencies and ensures the applications are natively cluster-ready.
- Example: `localhost:5432` -> `postgres-service:5432`

## Middleware Dependency Matrix

| Service | Requires Middleware | Failure Tolerance |
|---|---|---|
| `auth-service` | PostgreSQL, OpenLDAP | Fails fast without DB/LDAP |
| `identity-service` | PostgreSQL | Fails fast without DB |
| `form-service` | PostgreSQL, Kafka | Fails fast without DB |
| `promotion-service` | PostgreSQL, Neo4j, Redis, Kafka | Fails fast without DB/Neo4j/Redis |
| `gateway-service` | Redis | Fails fast without Redis |
| `notification-service` | Kafka | Fails fast without Kafka |

## Actuator Validation
- **Readiness Probes**: `/actuator/health` is fully exposed via the web for all 6 services.
- **Liveness Probes**: Can also use `/actuator/health`.
- **Security**: Access is limited to `/actuator/health` (no other endpoints are exposed).

## Known Startup Risks and Recommendations
- **Startup Crash Risk**: Services will crash and enter `CrashLoopBackOff` if their dependent databases (PostgreSQL, Neo4j, Redis) are not up. This is a standard Kubernetes pattern, but it means cluster deployments should ideally sequence middleware before microservices, or rely on prolonged backoff.
- **Recommended Readiness Probe Delays**: Since Spring Boot JVM apps take several seconds to boot, Kubernetes Deployments should set `initialDelaySeconds: 15` to `30` on readiness probes to avoid false negative restarts during JVM warmups.

## Secret Classification Matrix
Sensitive values have been classified strictly. They are no longer allowed to fallback to insecure defaults in the main application context.
- **Sensitive (Fail-Fast):** `DB_USERNAME`, `DB_PASSWORD`, `LDAP_USER`, `LDAP_PASSWORD`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`, `JWT_SECRET`, `QR_SECRET`, `VAULT_SECRET`, `VAULT_SALT`, `VAULT_HASH_SALT`
- **Non-Sensitive (Fallback Allowed):** Hostnames, ports, and configuration URLs.

## Kubernetes Secret Strategy
Kubernetes Secrets will be provisioned per-domain to limit access scope:
1. `circleguard-db-credentials`: Stores `DB_USERNAME`, `DB_PASSWORD` (mounted by auth, identity, form, promotion)
2. `circleguard-neo4j-credentials`: Stores `NEO4J_USERNAME`, `NEO4J_PASSWORD` (mounted by promotion)
3. `circleguard-ldap-credentials`: Stores `LDAP_USER`, `LDAP_PASSWORD` (mounted by auth)
4. `circleguard-jwt-secret`: Stores `JWT_SECRET` (mounted by auth, identity, promotion, gateway, notification)
5. `circleguard-qr-secret`: Stores `QR_SECRET` (mounted by auth, gateway, notification)
6. `circleguard-vault-secret`: Stores `VAULT_SECRET`, `VAULT_SALT`, `VAULT_HASH_SALT` (mounted by identity)

## Fail-Fast Strategy
If a sensitive environment variable is missing during startup, Spring Boot will crash immediately due to placeholder resolution failure (e.g., `IllegalArgumentException: Could not resolve placeholder 'JWT_SECRET'`). This guarantees that the application will never start up using an insecure fallback value in a production or staging environment. Insecure defaults have been completely eradicated from the base `application.yml` profile.
