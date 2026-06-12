# CircleGuard — Informe Final del Proyecto

**Curso:** Ingeniería de Software V  
**Repositorio:** `juancamilocorralesosvath/circle-guard-public`  
**Fecha:** Junio 2026

---

## Tabla de Contenidos

1. [Resumen Ejecutivo](#1-resumen-ejecutivo)
2. [Metodología Ágil y Branching (10%)](#2-metodología-ágil-y-branching-10)
3. [Infrastructure as Code con Terraform (20%)](#3-infrastructure-as-code-con-terraform-20)
4. [Patrones de Diseño (10%)](#4-patrones-de-diseño-10)
5. [CI/CD Avanzado (15%)](#5-cicd-avanzado-15)
6. [Testing Integral (15%)](#6-testing-integral-15)
7. [Gestión de Cambios y Release Notes (5%)](#7-gestión-de-cambios-y-release-notes-5)
8. [Observabilidad y Monitoreo (10%)](#8-observabilidad-y-monitoreo-10)
9. [Seguridad (5%)](#9-seguridad-5)
10. [Documentación y Presentación (10%)](#10-documentación-y-presentación-10)
11. [Catálogo de Servicios](#11-catálogo-de-servicios)
12. [Conclusiones](#12-conclusiones)

---

## 1. Resumen Ejecutivo

CircleGuard es un sistema universitario de rastreo de contactos diseñado para identificar grupos de contacto ("círculos") y aplicar cercas sanitarias con rapidez, preservando la anonimidad individual. El sistema sigue una arquitectura de microservicios desplegada sobre Kubernetes, con un pipeline CI/CD completo de 16 etapas.

### Stack tecnológico

| Capa | Tecnología |
|------|------------|
| Backend | Spring Boot 3 / Java 21 |
| Bases de datos | PostgreSQL 16, Neo4j 5, Redis 7, Apache Kafka |
| Orquestación | Kubernetes (Docker Desktop) + Kustomize |
| CI/CD | Jenkins StatefulSet en Kubernetes |
| IaC | Terraform (módulos reutilizables, Terraform Cloud backend) |
| Observabilidad | Prometheus, Grafana, Loki, Alloy, ELK Stack, Jaeger |
| Frontend | Expo (React Native) — iOS / Android / Web |

### Métricas de éxito

| Métrica | Objetivo | Estado |
|---------|----------|--------|
| Velocidad de contención | < 60 segundos | Cascade vía Kafka validado en E2E (< 8 s end-to-end) |
| Privacidad | 100% anonimato | Filtro K-Anonymity implementado en identity-service y dashboard-service |
| Uptime académico | 99.5% (7:00–22:00) | Health checks en todos los pods; rolling update sin downtime |
| Pipeline completo | Automatizado | 16 etapas cubriendo dev → staging → prod |
| Cobertura de pruebas | ≥ 85% | JaCoCo configurado con umbral por servicio |

### Estado de entrega

- **8 microservicios** integrados en pipeline y Kubernetes (auth, identity, form, promotion, gateway, notification, dashboard, file)
- **3 ambientes** de Kubernetes: `circleguard-dev`, `circleguard-staging`, `circleguard-prod`
- **Terraform** gestionando namespaces, RBAC y ConfigMaps para los 3 ambientes
- **Observabilidad completa**: Prometheus + Grafana + Loki + ELK + Jaeger
- **Seguridad**: RBAC, TLS con cert-manager, Trivy en pipeline, gestión de secretos

---

## 2. Metodología Ágil y Branching (10%)

> Documentación completa: [`docs/AGILE.md`](docs/AGILE.md)

### Herramienta y tablero

Se utilizó **GitHub Projects** con un tablero Kanban de cuatro columnas:

```
Backlog → In Progress → In Review → Done
```

Cada tarjeta referencia la User Story correspondiente y tiene criterios de aceptación verificables antes de moverse a "Done".

### Estrategia de branching — GitHub Flow

```
main (producción estable)
 └── feature/<descripción>   ← desarrollo
 └── fix/<descripción>        ← correcciones
```

- No se realizan commits directos a `main`.
- Todo cambio entra por Pull Request con referencia a la User Story.
- Commits siguen **Conventional Commits**: `feat:`, `fix:`, `docs:`, `test:`, `chore:`.

### Sprints y User Stories

**Sprint 1 (Semanas 1–2) — Fundación**

| ID | Historia | Criterio de aceptación |
|----|----------|----------------------|
| US-01 | Documentar metodología ágil | GitHub Projects board activo + `AGILE.md` completo |
| US-02 | IaC con Terraform | `terraform plan` sin errores en los 3 ambientes |
| US-03 | Circuit Breaker visible | `/actuator/circuitbreakers` retorna estado en gateway |
| US-04 | External Configuration documentada | ConfigMap en Terraform + `DESIGN_PATTERNS.md` |

**Sprint 2 (Semanas 3–4) — Calidad y Producción**

| ID | Historia | Criterio de aceptación |
|----|----------|----------------------|
| US-05 | SonarQube quality gate | Pipeline falla si quality gate no pasa |
| US-06 | Trivy scanning | Reporte de vulnerabilidades HIGH/CRITICAL publicado |
| US-07 | Notificaciones de pipeline | Email enviado en éxito y falla |
| US-08 | Approval gate producción | Deploy a prod requiere confirmación manual en Jenkins |
| US-09 | OWASP ZAP scan | Reporte HTML publicado en Jenkins |
| US-10 | ELK Stack activo | Logs buscables en Kibana `:5601` |
| US-11 | Jaeger activo | Trazas visibles en `:16686` |
| US-12 | Grafana dashboards | Dashboard por servicio con métricas de actuator |
| US-13 | RBAC verificado | `kubectl auth can-i` confirma permisos mínimos |
| US-14 | Documentación completa + video | Todos los docs presentes + demo grabado |

### Definición de Done

Un ítem se considera terminado cuando:
- PR mergeado a `main` con aprobación
- Tests del pipeline pasan
- Documentación actualizada si el cambio la requiere
- Criterios de aceptación de la User Story verificados

---

## 3. Infrastructure as Code con Terraform (20%)

> Documentación completa: [`docs/TERRAFORM.md`](docs/TERRAFORM.md)

### Estructura modular

```
terraform/
├── modules/
│   ├── namespace/          ← Namespace + ResourceQuota
│   ├── service-account/    ← ServiceAccount + Role + RoleBinding
│   └── configmap/          ← ConfigMap con variables de ambiente
└── environments/
    ├── dev/main.tf
    ├── staging/main.tf
    └── prod/main.tf
```

Cada módulo es reutilizable entre ambientes. Solo cambian los valores de las variables.

### Backend remoto

Terraform Cloud (HCP Terraform) con tres workspaces independientes:

| Workspace | Ambiente |
|-----------|----------|
| `circleguard-dev` | Desarrollo |
| `circleguard-staging` | Pre-producción |
| `circleguard-prod` | Producción |

El estado remoto garantiza que múltiples desarrolladores no pisan el estado de los demás.

### Recursos gestionados por Terraform

| Recurso | Módulo | Descripción |
|---------|--------|-------------|
| `Namespace` | namespace | Aislamiento de red y RBAC por ambiente |
| `ResourceQuota` | namespace | Límites de CPU, memoria y pods |
| `ConfigMap` | configmap | 22 variables de entorno inyectadas en los pods |
| `ServiceAccount` | service-account | Una cuenta por servicio con permisos mínimos |
| `Role` + `RoleBinding` | service-account | RBAC namespace-scoped |

### Configuración por ambiente

| Parámetro | dev | staging | prod |
|-----------|-----|---------|------|
| CPU request / limit | 2 / 4 | 4 / 8 | 8 / 16 |
| Memoria request / limit | 2 / 4 Gi | 4 / 8 Gi | 8 / 16 Gi |
| Pods máximos | 30 | 40 | 60 |
| Pool de conexiones BD | 10 | 25 | 50 |

### Uso

```bash
cd terraform/environments/dev
terraform init
terraform plan
terraform apply
```

### Estimación de costos

| Componente | Local (Docker Desktop) | Cloud equivalente (AWS) |
|------------|----------------------|------------------------|
| Kubernetes (EKS) | $0 | ~$150/mes |
| Bases de datos (RDS + Neo4j Aura) | $0 | ~$80/mes |
| Kafka (MSK) | $0 | ~$60/mes |
| Observabilidad (CloudWatch + OpenSearch) | $0 | ~$50/mes |
| Registro de contenedores | $0 | ~$5/mes |
| **Total** | **$0** | **~$345/mes** |

---

## 4. Patrones de Diseño (10%)

> Documentación completa: [`docs/DESIGN_PATTERNS.md`](docs/DESIGN_PATTERNS.md)

Se identificaron e implementaron seis patrones en el sistema:

| Patrón | Dónde | Problema que resuelve | Verificación |
|--------|-------|----------------------|--------------|
| **API Gateway** | `gateway-service` | Punto de entrada único; oculta la topología interna; centraliza autenticación JWT | Todos los clientes acceden solo a `:8087` |
| **Circuit Breaker** | `gateway-service` (Resilience4j) | Evita cascada de fallos cuando Redis no responde; fail-open para no bloquear acceso | `GET /actuator/circuitbreakers` |
| **External Configuration** | Kubernetes ConfigMaps (vía Terraform) | Misma imagen Docker corre en dev/staging/prod con valores distintos sin rebuild | `kubectl get configmap circleguard-config -o yaml` |
| **Service Registry** | Kubernetes DNS | Descubrimiento de servicios por nombre estable (`postgres-service`, `redis-service`) sin IPs hardcodeadas | `kubectl get svc -n circleguard-dev` |
| **Sidecar** | Grafana Alloy | Recolección de logs sin modificar código de los servicios | Logs visibles en Grafana sin cambios en servicios |
| **Strangler Fig** | Arquitectura general | Extracción incremental de microservicios (auth → identity → … → dashboard/file) en lugar de big-bang | Servicios añadidos uno a uno manteniendo el sistema en producción |

### Circuit Breaker — estados

```
CLOSED (normal) → [50% fallas en ventana]
    ↓
OPEN (rechaza requests) → [espera 10s]
    ↓
HALF-OPEN (prueba 1 request) → [éxito]
    ↓
CLOSED (normal)
```

La política es **fail-open**: si el breaker está abierto, el gateway permite el acceso para no bloquear estudiantes en la entrada del campus.

---

## 5. CI/CD Avanzado (15%)

> Documentación completa: [`docs/CI_CD_RUNBOOK.md`](docs/CI_CD_RUNBOOK.md)

### Arquitectura del pipeline

El `Jenkinsfile` implementa **16 etapas** con ejecución condicional por rama:

```
Todas las ramas:
  Checkout
    → Build & Test (Gradle + JaCoCo)
    → SonarQube Analysis
    → Quality Gate
    → Docker Build & Push (juanc0410/* en Docker Hub)
    → Container Scan (Trivy HIGH/CRITICAL)

Rama dev:
    → Deploy & Smoke: Dev

Rama staging:
    → Deploy & Smoke: Staging
    → E2E Tests (Playwright K8s Job)
    → Performance Tests (Locust peak)
    → Stress Tests (Locust stress)
    → Security Scan (OWASP ZAP)

Rama main:
    → Deploy to Staging
    → Validate Staging Promotion
    → [Approval Gate — confirmación manual]
    → Generate Release Notes
    → Deploy to Production
    → Production Smoke Tests
    → Tag Release
    → Archive Release Metadata
```

### SonarQube

- Análisis estático de código en cada build.
- Quality gate configurado con token de autenticación en credencial Jenkins `sonarqube-token`.
- El pipeline falla si el quality gate no pasa (`waitForQualityGate()`).
- SonarQube corre en Docker local (`sonarqube:lts-community`, puerto 9000).

### Trivy — escaneo de vulnerabilidades

- Escanea cada imagen Docker construida por el pipeline.
- Detecta vulnerabilidades `HIGH` y `CRITICAL`.
- Actualmente no-bloqueante (`--exit-code 0`) mientras se establece el baseline.
- Instalado en el agente Jenkins con binario ARM64/x64 auto-detectado.

### Semver y tagging

El pipeline genera versiones con el patrón `v<AÑO>.<MES>.<DÍA>-<BUILD_NUMBER>` y crea un tag Git en cada deploy exitoso a producción. Los release notes se generan automáticamente desde los mensajes de commit entre el tag anterior y el actual.

### Notificaciones

Configuradas via Gmail SMTP (App Password). El pipeline envía email a `correoalternativopersonal492@gmail.com` en:
- Fallo de cualquier etapa
- Éxito del pipeline completo

### Approval gate

En la rama `main`, antes de desplegar a producción aparece una pausa en Jenkins que requiere confirmación manual del operador (**Proceed** / **Abort**).

### Rollback automático

Si `deployToEnv()` detecta que el rollout no converge en 300 segundos, ejecuta:
```groovy
sh "kubectl rollout undo deployment/circleguard-${svc} -n ${namespace}"
```
por cada servicio que falló, y reporta el resultado individual.

---

## 6. Testing Integral (15%)

> Documentación completa: [`docs/WORKSHOP2_INFORME.md`](docs/WORKSHOP2_INFORME.md)

### Resumen de niveles

| Nivel | Herramienta | Cantidad | Umbral |
|-------|-------------|----------|--------|
| Unitario | JUnit 5 + JaCoCo | 32 tests / 5 clases | ≥ 85% cobertura por servicio |
| Integración | Testcontainers + EmbeddedKafka + MockWebServer | 27 tests / 5 clases | — |
| E2E | Playwright (TypeScript, K8s Job) | 5 escenarios | 60% pass rate (UNSTABLE si < 100%) |
| Performance | Locust (3 tiers) | warmup / peak / stress | NFR comparado por endpoint |
| Seguridad | OWASP ZAP | Scan completo | Reporte HTML publicado |

### Tests unitarios — clases cubiertas

| Clase | Servicio | Tests | Qué valida |
|-------|---------|-------|-----------|
| `QrTokenServiceExpiryTest` | auth-service | 5 | Generación, validación y expiración de tokens QR |
| `SymptomMapperExtendedTest` | form-service | 7 | Clasificación de síntomas |
| `KAnonymityFilterTest` | identity-service | 7 | Filtro de privacidad k-anonimato |
| `DualChainAuthenticationProviderTest` | auth-service | 7 | LDAP + autenticación local |
| `HealthStatusServiceFenceWindowTest` | promotion-service | 6 | Ventana de aislamiento sanitario |

### Tests de integración — contratos entre servicios

| Clase | Servicios involucrados | Tests |
|-------|----------------------|-------|
| `LoginFlowIntegrationTest` | auth-service + identity-service | 5 |
| `SurveyKafkaCascadeIntegrationTest` | form-service + Kafka | 5 |
| `RedisStatusSharingIntegrationTest` | promotion-service + gateway-service vía Redis | 6 |
| `NotificationKafkaDispatchIntegrationTest` | notification-service + Kafka | 5 |
| `QrTokenRoundTripIntegrationTest` | auth-service + gateway-service | 6 |

### E2E — escenarios Playwright

| Escenario | Flujo |
|-----------|-------|
| e2e-1: Happy path | Login → Generación QR → Validación en puerta (GREEN) — RRT < 3s |
| e2e-2: Cascada sanitaria | Encuesta con síntomas → Kafka → Status → RED (< 8s end-to-end) |
| e2e-3: Recuperación | CONFIRMED → adminOverride → GREEN |
| e2e-4: Confirmación admin | Admin confirma caso → contactos notificados → query de círculo |
| e2e-5: Cuestionario dinámico | Cuestionario activo → respuesta síntomas → cascade |

Los E2E corren como Kubernetes Job en `circleguard-staging`, los resultados se extraen via `kubectl cp` y se parsean como JUnit XML en Jenkins.

### Performance — resultados clave (Locust)

| Endpoint | Warmup (p95) | Peak (p95) | Stress (p95) | NFR (p95) | Cumple |
|----------|-------------|-----------|-------------|-----------|--------|
| GET /gate/validate | 8 ms | 11 ms | 12 ms | ≤ 100 ms | ✅ |
| GET /status/{id} | 5 ms | 9 ms | 10 ms | ≤ 200 ms | ✅ |
| POST /surveys | 1260 ms | 16 ms | 22 ms | ≤ 300 ms | ⚠️ cold-start Kafka |
| POST /login | 89 ms | 420 ms | 7600 ms | ≤ 500 ms | ❌ LDAP pool contention |

**Hallazgo crítico:** POST /login falla el NFR bajo stress debido a contención en el pool de conexiones LDAP. El warmup de POST /surveys es alto por cold-start de Kafka pero se normaliza rápidamente.

### OWASP ZAP

El script `performance/zap-scan.sh` ejecuta un escaneo de seguridad contra el gateway-service y publica el reporte HTML en Jenkins como artefacto. Se ejecuta en la etapa "Security Scan" de la rama staging.

---

## 7. Gestión de Cambios y Release Notes (5%)

> Documentación: [`docs/AGILE.md`](docs/AGILE.md), [`docs/CI_CD_RUNBOOK.md`](docs/CI_CD_RUNBOOK.md)

### Proceso de cambio

```
1. Crear rama feature/<nombre> desde main
2. Desarrollar con commits convencionales (feat:, fix:, docs:, ...)
3. Abrir Pull Request con referencia a User Story
4. PR aprobado → merge a main
5. Pipeline genera release notes automáticamente
6. Pipeline crea tag Git con versión semver
```

### Versionado semántico

| Tipo de commit | Impacto | Ejemplo |
|---------------|---------|---------|
| `fix:` | PATCH (v1.0.**1**) | `fix: corregir expiración de QR token` |
| `feat:` | MINOR (v1.**1**.0) | `feat: agregar dashboard-service al pipeline` |
| `BREAKING CHANGE:` | MAJOR (**2**.0.0) | Cambio de API incompatible |

Versión de release en pipeline: `v<AÑO>.<MES>.<DÍA>-<BUILD>` (ej: `v2026.06.12-42`)

### Rollback

**Rápido (kubectl):**
```bash
kubectl rollout undo deployment/circleguard-gateway-service -n circleguard-dev
```

**Pipeline:** Si el rollout falla, el pipeline ejecuta rollback automático por servicio y reporta el estado individual antes de fallar el build.

**Por tag:** Se puede redeployar cualquier versión anterior usando el tag Git correspondiente y disparando el pipeline con ese SHA.

---

## 8. Observabilidad y Monitoreo (10%)

> Documentación completa: [`docs/OPERATIONS.md`](docs/OPERATIONS.md)

### Stack de observabilidad

El stack corre fuera de Kubernetes vía Docker Compose (`observability/docker-compose.yml`):

```bash
docker compose -f observability/docker-compose.yml up -d
```

| Herramienta | URL | Función |
|-------------|-----|---------|
| Grafana | http://localhost:3000 | Dashboards de métricas y logs |
| Prometheus | http://localhost:9090 | Scrape de métricas de actuator |
| Kibana | http://localhost:5601 | Búsqueda de logs (ELK) |
| Jaeger | http://localhost:16686 | Trazas distribuidas |
| Elasticsearch | http://localhost:9200 | Motor de logs (ELK) |
| AlertManager | http://localhost:9093 | Alertas y silenciadores |

### Flujos de datos

```
Logs (Loki):    Servicio → stdout → Grafana Alloy → Loki → Grafana
Logs (ELK):     Servicio → stdout → Logstash → Elasticsearch → Kibana
Métricas:       Servicio /actuator/prometheus → Prometheus → Grafana
Trazas (OTLP):  Servicio → OTLP :4317 → Jaeger → UI :16686
Alertas:        Prometheus → AlertManager → (email / webhook)
```

### Health checks

Todos los pods tienen tres tipos de probe configurados vía Spring Boot Actuator:

| Probe | Path | Comportamiento |
|-------|------|----------------|
| `startupProbe` | `/actuator/health/readiness` | Reintenta 30 veces (150s) antes de matar el pod |
| `readinessProbe` | `/actuator/health/readiness` | Retira el pod del balanceador si falla |
| `livenessProbe` | `/actuator/health/liveness` | Reinicia el pod si falla 5 veces consecutivas |

### Circuit breaker observable

```bash
curl http://localhost:30087/actuator/circuitbreakers
```

Retorna el estado de todos los circuit breakers de gateway-service (CLOSED / OPEN / HALF-OPEN) con métricas de llamadas exitosas y fallidas.

---

## 9. Seguridad (5%)

> Documentación completa: [`docs/SECURITY.md`](docs/SECURITY.md)

### RBAC

Todos los microservicios corren bajo el `ServiceAccount` `circleguard-service`, definido en `k8s/base/rbac/services-rbac.yaml`. El `Role` asociado otorga únicamente los permisos mínimos necesarios:

| Recurso | Verbos permitidos |
|---------|-------------------|
| ConfigMaps | get, list, watch |
| Secrets | get |

Ningún servicio puede crear, modificar ni eliminar recursos de Kubernetes.

### Gestión de secretos

Los secretos se definen como objetos `Secret` de Kubernetes en `k8s/base/secrets/circleguard-secrets.yaml` e inyectados en los pods como variables de entorno vía `secretKeyRef`. Nunca se almacenan en texto plano en archivos de configuración ni en el repositorio.

| Secret | Contenido |
|--------|-----------|
| `circleguard-db-credentials` | Usuario y contraseña PostgreSQL |
| `circleguard-neo4j-credentials` | Usuario y contraseña Neo4j |
| `circleguard-ldap-credentials` | Bind DN y contraseña OpenLDAP |
| `circleguard-jwt-secret` | Clave de firma JWT |
| `circleguard-qr-secret` | Clave de firma QR |
| `circleguard-vault-secret` | Claves de cifrado del vault |

### TLS

El servicio `gateway-service` (punto de entrada externo) tiene TLS gestionado por **cert-manager** con un `ClusterIssuer` self-signed:

```bash
# Instalar cert-manager (una vez por cluster)
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.5/cert-manager.yaml

# Verificar certificado
kubectl get certificate circleguard-gateway-tls -n circleguard-dev
# READY = True
```

El certificado se almacena en el secret `circleguard-gateway-tls` y se rota automáticamente por cert-manager.

### Escaneo de vulnerabilidades en contenedores

Trivy escanea cada imagen Docker construida en el pipeline:

```groovy
sh "docker run --rm --entrypoint trivy ${agentImage} image --severity HIGH,CRITICAL ${imageName}"
```

El agente Jenkins incluye el binario Trivy con detección automática de arquitectura (ARM64 / x86-64).

---

## 10. Documentación y Presentación (10%)

### Índice de documentación

| Documento | Descripción |
|-----------|-------------|
| [README.md](README.md) | Overview del proyecto, quick start, índice de docs |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Diagramas ASCII de arquitectura, catálogo de servicios, dependencias |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Guía operativa: inicio, despliegue, rollback, troubleshooting |
| [docs/TERRAFORM.md](docs/TERRAFORM.md) | IaC: módulos, ambientes, backend remoto, estimación de costos |
| [docs/DESIGN_PATTERNS.md](docs/DESIGN_PATTERNS.md) | 6 patrones documentados con verificación |
| [docs/AGILE.md](docs/AGILE.md) | Kanban, branching, user stories, sprints, definición de Done |
| [docs/SECURITY.md](docs/SECURITY.md) | RBAC, secretos, TLS, Trivy |
| [docs/CI_CD_RUNBOOK.md](docs/CI_CD_RUNBOOK.md) | Rollback, recovery, comandos Jenkins |
| [docs/TEAMMATE_SETUP.md](docs/TEAMMATE_SETUP.md) | Onboarding para nuevos colaboradores |
| [docs/WORKSHOP2_INFORME.md](docs/WORKSHOP2_INFORME.md) | Informe técnico del Taller 2: CI/CD, testing, performance |
| [docs/PHASE4_MANUAL_SETUP.md](docs/PHASE4_MANUAL_SETUP.md) | Setup manual de SonarQube, email y Jenkins |

### Quick Start

```bash
# 1. Levantar stack de observabilidad
docker compose -f observability/docker-compose.yml up -d

# 2. Desplegar en dev
kubectl apply -k k8s/overlays/dev

# 3. Verificar pods
kubectl get pods -n circleguard-dev

# 4. Abrir Jenkins y ejecutar pipeline
open http://localhost:32080
```

### Guía del video de demo

El video de demo debe cubrir (20–30 minutos):

1. **Arquitectura** — diagrama de servicios y flujos (3 min)
2. **Terraform** — `terraform plan` en dev y staging, mostrar workspaces en Terraform Cloud (3 min)
3. **Pipeline en Jenkins** — ejecutar build completo, mostrar etapas, SonarQube quality gate, Trivy output (5 min)
4. **Observabilidad** — Grafana dashboards, Kibana logs, Jaeger trazas, AlertManager (5 min)
5. **Testing** — reportes JaCoCo, resultados E2E, análisis de performance Locust, reporte ZAP (5 min)
6. **Deploy a producción** — approval gate, smoke test prod, tag de release (3 min)
7. **Seguridad** — `kubectl auth can-i` para verificar RBAC, certificate ready en cert-manager (3 min)

---

## 11. Catálogo de Servicios

| Servicio | Puerto | NodePort (dev) | Base de datos | Dockerfile |
|----------|--------|----------------|---------------|------------|
| gateway-service | 8087 | 30087 | Redis | `services/circleguard-gateway-service/Dockerfile` |
| auth-service | 8081 | 30180 | PostgreSQL, OpenLDAP | `services/circleguard-auth-service/Dockerfile` |
| identity-service | 8082 | 30083 | PostgreSQL | `services/circleguard-identity-service/Dockerfile` |
| promotion-service | 8083 | 30088 | Neo4j, Kafka | `services/circleguard-promotion-service/Dockerfile` |
| form-service | 8086 | 30086 | PostgreSQL | `services/circleguard-form-service/Dockerfile` |
| notification-service | 8085 | 30082 | Kafka | `services/circleguard-notification-service/Dockerfile` |
| dashboard-service | 8084 | 30084 | PostgreSQL | `services/circleguard-dashboard-service/Dockerfile` |
| file-service | 8085 | 30085 | — (filesystem) | `services/circleguard-file-service/Dockerfile` |

Todos los servicios comparten:
- `serviceAccountName: circleguard-service` (RBAC mínimo)
- Variables de entorno desde `circleguard-config` ConfigMap
- Secretos desde `circleguard-db-credentials` y otros (cuando aplica)
- Health probes: startupProbe + readinessProbe + livenessProbe en `/actuator/health`
- Rolling update (maxUnavailable: 0, maxSurge: 1) para cero downtime

---

## 12. Conclusiones

### Logros técnicos

1. **Pipeline completo end-to-end**: 16 etapas cubriendo compilación, análisis estático, escaneo de vulnerabilidades, despliegue, testing funcional, de rendimiento y de seguridad, y deploy a producción con approval gate.

2. **IaC modular y multi-ambiente**: Terraform gestiona los 3 ambientes con módulos reutilizables y estado remoto en Terraform Cloud, eliminando configuración manual y garantizando reproducibilidad.

3. **Observabilidad full-stack**: Métricas (Prometheus/Grafana), logs (Loki + ELK), y trazas distribuidas (Jaeger) con zero-instrumentation en el código de negocio gracias al patrón Sidecar.

4. **8 microservicios integrados**: Desde los 6 entregados en el Taller 2 hasta los 8 completos del proyecto final, cada uno con su Dockerfile, manifest Kubernetes, tests, y cobertura ≥ 85%.

5. **Seguridad por capas**: RBAC mínimo en Kubernetes, secretos gestionados como objetos K8s, TLS en el punto de entrada con cert-manager, y escaneo de vulnerabilidades en cada imagen del pipeline.

### Lecciones aprendidas

- **Spring Boot probes**: Separar `/readiness` y `/liveness` es crítico para el comportamiento correcto de Kubernetes. El `startupProbe` debe dar suficiente tiempo para JVM + Flyway migrations.
- **Kafka cold-start**: El primer request a un topic Kafka tiene alta latencia. Las métricas de warmup no son representativas del comportamiento estacionario.
- **LDAP bajo estrés**: El pool de conexiones LDAP es el cuello de botella de autenticación. Bajo 200 usuarios concurrentes la latencia de POST /login supera 7 veces el NFR. Solución futura: caché de sesión en Redis.
- **ARM64 en CI**: Los binarios de herramientas como Trivy tienen variantes por arquitectura (`Linux-ARM64` vs `Linux-64bit`). La auto-detección con `uname -m` en el Dockerfile es necesaria para equipos con Apple Silicon.
- **DooD (Docker-out-of-Docker)**: El agente Jenkins necesita acceso al socket Docker del host para construir imágenes. La configuración del socket es crítica y distinta en cada sistema operativo del host.

### Trabajo futuro

- **Caché de autenticación LDAP**: Implementar Redis como caching layer para sesiones LDAP y reducir latencia bajo carga.
- **mTLS entre servicios**: Extender TLS a la comunicación interna entre microservicios usando cert-manager + Istio o SPIFFE/SPIRE.
- **Autoscaling**: Configurar HPA (Horizontal Pod Autoscaler) basado en métricas de CPU y requests por segundo.
- **Ambientes cloud**: Migrar de Docker Desktop a EKS/GKE para validar el comportamiento del sistema en infraestructura real multi-nodo.
- **Triangulación WiFi**: Integrar la API de APs del campus para check-in automático por presencia WiFi (Roadmap Phase 2).
