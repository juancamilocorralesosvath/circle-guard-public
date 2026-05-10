# CircleGuard Containerization Strategy

This document outlines the containerization foundation implemented for the CircleGuard microservices. It aligns with the Kubernetes-first architecture defined in Phase 1 of the DevOps implementation roadmap.

## 1. Docker Build Strategy

All services use a standardized **multi-stage Docker build process** designed for optimization and security:

- **Build Stage (`eclipse-temurin:21-jdk-jammy`)**: Uses the full JDK to compile the application and resolve dependencies via the Gradle wrapper (`./gradlew`). This ensures reproducibility and isolation. The build context is the root of the repository.
- **Runtime Stage (`eclipse-temurin:21-jre-jammy`)**: Uses a lightweight JRE image for the final container. It copies only the built `app.jar` artifact from the build stage, significantly reducing the image size and attack surface.

### Build Command
Because the project uses a multi-project Gradle setup, Docker builds must be executed from the **repository root**:
```bash
docker build -t circleguard-<service-name> -f services/circleguard-<service-name>/Dockerfile .
```

## 2. Image Naming Convention

Images should follow the pattern:
```
circleguard-<service-name>:<version>
```
For local testing and CI pipelines, standard tags like `latest` or Git commit SHAs will be used.

## 3. Runtime Configuration Strategy

All configurations have been externalized from hardcoded `application.yml` files. Services now dynamically consume settings from environment variables with sensible defaults. This is critical for Kubernetes `ConfigMap` and `Secret` integration.

## 4. Required Environment Variables

When running the containers, the following common environment variables can be provided:

### Common Defaults
- `SERVER_PORT`: Overrides the default port for the service.
- `DB_HOST`, `DB_PORT`, `DB_NAME`: Database connection parameters.
- `DB_USERNAME`, `DB_PASSWORD`: Database credentials.
- `JWT_SECRET`, `JWT_EXPIRATION`: JWT settings (required for auth, identity, promotion, gateway, notification).

### Service-Specific Variables
- **Promotion & Form & Notification**: `KAFKA_BOOTSTRAP_SERVERS`
- **Promotion**: `NEO4J_HOST`, `NEO4J_PORT`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`
- **Promotion & Gateway**: `REDIS_HOST`, `REDIS_PORT`
- **Notification**: `MAIL_HOST`, `MAIL_PORT`, `AUTH_API_URL`
- **Auth**: `LDAP_HOST`, `LDAP_PORT`, `LDAP_BASE`, `LDAP_USER`, `LDAP_PASSWORD`, `QR_SECRET`, `QR_EXPIRATION`
- **Identity**: `VAULT_SECRET`, `VAULT_SALT`, `VAULT_HASH_SALT`

## 5. Health Endpoint Strategy

To support Kubernetes Liveness and Readiness probes, **Spring Boot Actuator** has been integrated into all services.
- The `/actuator/health` endpoint is exposed over HTTP.
- Dependencies (`spring-boot-starter-actuator`) have been added to each service's `build.gradle.kts`.
- This ensures Kubernetes can safely restart stalled services and route traffic only to healthy instances.

## 6. Startup Validation Results

The containerization configuration ensures that all services successfully compile with Actuator dependencies and properly utilize multi-stage builds. 

## 7. Known Dependency Requirements

To run these containers successfully, the following middleware dependencies must be active (usually managed by Kubernetes in the future, or locally via `docker-compose.dev.yml` for testing):
- **PostgreSQL 16**
- **Neo4j 5.26** (Promotion)
- **Redis 7.2** (Promotion, Gateway)
- **Kafka 7.6 / Zookeeper** (Promotion, Form, Notification)
- **OpenLDAP 1.5.0** (Auth)

## 8. Future Kubernetes Integration Notes

The externalized configuration directly maps to Kubernetes concepts:
1. **ConfigMaps**: Will store non-sensitive configuration like `DB_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, `REDIS_HOST`.
2. **Secrets**: Will store sensitive credentials like `DB_PASSWORD`, `JWT_SECRET`, `VAULT_SECRET`.
3. **Probes**: Kubernetes Deployments will point `readinessProbe` and `livenessProbe` to `httpGet` on path `/actuator/health` at the service's port.

## 9. Environment-Specific Secret Policies
The applications support multiple environments via Spring Profiles.

- **Non-Dev Environments (Staging, Prod, CI):** The base `application.yml` file is used. Insecure secret fallbacks have been completely removed. Therefore, sensitive values (like `JWT_SECRET`, `DB_PASSWORD`) are strictly required. Missing secrets will trigger an immediate JVM crash upon startup (Fail-Fast), guaranteeing no leakage of insecure states.
- **Dev Environments (Local):** Developers can pass the `SPRING_PROFILES_ACTIVE=dev` environment variable to their container or local run. This triggers the `dev` profile using Spring Boot's multi-document YAML syntax, re-injecting the insecure default placeholders specifically for developer ease of use, without harming production security.

## 10. Dev vs Staging vs Production Behavior
- **Local Dev:** Run with `-e SPRING_PROFILES_ACTIVE=dev` to safely utilize fallback placeholders (e.g. `password`).
- **Staging/Prod:** Provide fully populated Kubernetes Secrets. The applications will refuse to start if any secret payload is missing.
