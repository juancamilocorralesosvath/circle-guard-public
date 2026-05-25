# Plan Pre-Proyecto Final — Observabilidad CircleGuard

## Objetivo

Construir un entorno completo de observabilidad (métricas, logs, alertas, dashboards) para el sistema CircleGuard. La estrategia es incremental: primero validar la infraestructura de monitoreo de forma aislada, luego con una app demo, y finalmente integrar los servicios reales.

---

## Arquitectura General

```
┌─────────────────────────────────────────────────────────────────┐
│                    observability/ (Docker Compose)               │
│                                                                   │
│  ┌──────────────┐   scrape   ┌──────────────┐                   │
│  │  Prometheus  │◄──────────►│  Node Exporter│                   │
│  │   :9090      │            │   :9100       │                   │
│  └──────┬───────┘            └──────────────┘                   │
│         │ rules                                                   │
│         ▼                                                         │
│  ┌──────────────┐            ┌──────────────┐                   │
│  │ Alertmanager │            │  Grafana     │◄── dashboards      │
│  │   :9093      │            │  :3000       │                   │
│  └──────────────┘            └──────┬───────┘                   │
│                                     │ query                       │
│  ┌──────────────┐   push    ┌──────▼───────┐                   │
│  │  Alloy       │──────────►│  Loki        │                   │
│  │   :12345     │           │  :3100       │                   │
│  └──────┬───────┘           └──────────────┘                   │
│         │ reads Docker logs                                       │
└─────────┼───────────────────────────────────────────────────────┘
          │
    ┌─────▼──────────────────────────────────┐
    │         Servicios Spring Boot           │
    │  demo-app:8080  /actuator/prometheus    │
    │  auth-service:8180  /actuator/prometheus│
    │  gateway-service:8087 /actuator/prometheus│
    └────────────────────────────────────────┘
```

---

## FASE 1 — Infraestructura de Monitoreo

### Ubicación
`observability/` en la raíz del proyecto.

### Componentes desplegados

| Servicio | Imagen | Puerto | Función |
|---|---|---|---|
| Prometheus | prom/prometheus | 9090 | Recolección de métricas |
| Alertmanager | prom/alertmanager | 9093 | Gestión de alertas |
| Grafana | grafana/grafana | 3000 | Dashboards y visualización |
| Loki | grafana/loki | 3100 | Almacenamiento de logs |
| Alloy | grafana/alloy | 12345 | Recolección de logs Docker |
| Node Exporter | prom/node-exporter | 9100 | Métricas del host |

### Cómo levantar la infraestructura

```bash
cd observability/
docker compose up -d
```

### Validación Fase 1

```bash
# Prometheus está corriendo y scrapeando
curl http://localhost:9090/api/v1/targets

# Grafana accesible (admin/admin)
open http://localhost:3000

# Loki recibiendo logs
curl http://localhost:3100/ready
```

---

## FASE 2 — Aplicación Demo Spring Boot

### Ubicación
`demo-observability/` en la raíz del proyecto.

### Tecnologías

- Spring Boot 3.2.4 + Java 17
- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`
- `@Scheduled` para generar actividad continua

### Métricas expuestas

| Métrica | Tipo | Descripción |
|---|---|---|
| `demo.operations.success` | Counter | Operaciones exitosas simuladas |
| `demo.operations.failed` | Counter | Operaciones fallidas simuladas |
| `demo.operation.duration` | Timer | Duración de operaciones |
| `demo.active.users` | Gauge | Usuarios activos simulados |
| `demo.http.requests` | Counter | Requests HTTP manuales |

### Endpoint de métricas

```
GET http://localhost:8080/actuator/prometheus
```

### Actividad automática (schedulers)

- Cada **5 segundos**: operación exitosa + log INFO
- Cada **15 segundos**: falla aleatoria (40% probabilidad) + log ERROR
- Cada **30 segundos**: resumen de métricas + log INFO

### Cómo correr la demo localmente

```bash
cd demo-observability/
./gradlew bootRun
# O con Docker (incluido en observability/docker-compose.yml):
cd observability/ && docker compose up demo-app -d
```

---

## FASE 3 — Integración con CircleGuard Real

### Servicios integrados

Se integraron **auth-service** y **gateway-service** (los más simples operacionalmente, sin dependencias de Kafka o Neo4j).

### Cambios realizados

#### `circleguard-auth-service`

1. **`build.gradle.kts`**: Se agregó `micrometer-registry-prometheus`
2. **`application.yml`**: Se expusieron endpoints `health,info,prometheus,metrics` y se agregaron tags de métricas
3. **`AuthMetrics.java`**: Nuevo componente con contadores:
   - `auth.login.success` — logins exitosos
   - `auth.login.failure` — logins fallidos
   - `auth.token.issued` — tokens JWT emitidos
4. **`LoginController.java`**: Se inyectó `AuthMetrics` y se llamaron los contadores en cada flujo

#### `circleguard-gateway-service`

1. **`build.gradle.kts`**: Se agregó `micrometer-registry-prometheus`
2. **`application.yml`**: Se expusieron endpoints y se agregaron tags de métricas
3. **`GatewayMetrics.java`**: Nuevo componente con contadores:
   - `gateway.qr.validated` — QRs válidos (GREEN)
   - `gateway.qr.rejected` — QRs rechazados (RED/inválidos)
   - `gateway.request.total` — total de requests
4. **`GateController.java`**: Se inyectó `GatewayMetrics` y se registra cada validación

### Scrape targets en Prometheus

Los servicios reales se scrapearon via `host.docker.internal` (acceso desde Docker al host):

```yaml
- job_name: auth-service
  metrics_path: /actuator/prometheus
  static_configs:
    - targets: ['host.docker.internal:8180']

- job_name: gateway-service
  metrics_path: /actuator/prometheus
  static_configs:
    - targets: ['host.docker.internal:8087']
```

### Cómo correr los servicios reales con observabilidad

```bash
# 1. Levantar infraestructura de monitoreo
cd observability/ && docker compose up -d

# 2. Levantar dependencias (Postgres, Redis, LDAP)
docker compose -f docker-compose.dev.yml up -d

# 3. Correr auth-service
cd services/circleguard-auth-service && ./gradlew bootRun

# 4. Correr gateway-service
cd services/circleguard-gateway-service && ./gradlew bootRun

# 5. Verificar métricas
curl http://localhost:8180/actuator/prometheus | grep auth_login
curl http://localhost:8087/actuator/prometheus | grep gateway_qr
```

---

## Dashboards en Grafana

Tres dashboards pre-configurados en `observability/grafana/provisioning/dashboards/`:

| Dashboard | UID | Contenido |
|---|---|---|
| Demo App — Overview | `demo-app-overview` | Operations rate, active users, JVM heap, HTTP rate, logs |
| Auth Service — Overview | `auth-service-overview` | Login rate, tokens, JVM, latencia, errors, logs |
| Gateway Service — Overview | `gateway-service-overview` | QR rate, latencia, errors, JVM, logs |

---

## Alertas configuradas

Archivo: `observability/prometheus/rules/alerts.yml`

| Alerta | Severidad | Condición |
|---|---|---|
| `ServiceDown` | Critical | `up == 0` por 1 min |
| `HighErrorRate` | Critical | Tasa de 5xx > 0.1 req/s |
| `JvmHighHeapUsage` | Warning | Heap JVM > 80% por 3 min |
| `JvmGcHighPause` | Warning | Pausa GC promedio > 0.5s |
| `HighCpuUsage` | Warning | CPU > 85% por 5 min |
| `HighMemoryUsage` | Warning | Memoria > 90% por 5 min |
| `AuthHighFailureRate` | Critical | Login failures > 0.5/s |
| `GatewayHighLatency` | Warning | p95 > 2s por 3 min |

---

## ELK Stack vs Loki — Análisis Comparativo

### ¿Por qué Loki en lugar de ELK?

| Criterio | ELK Stack | Loki + Grafana |
|---|---|---|
| **Arquitectura** | Elasticsearch (índice full-text) + Logstash (pipeline) + Kibana (UI) | Loki (índice por labels) + Alloy (colector) + Grafana (UI) |
| **Complejidad operativa** | Alta — tres componentes separados con configuración compleja | Baja — dos componentes, configuración declarativa |
| **Uso de memoria** | Muy alto — Elasticsearch es intensivo en RAM (mín. 2-4 GB solo) | Bajo — Loki comprime y almacena chunks, ~200-500 MB |
| **Indexación** | Full-text search en todos los campos | Solo por labels (metadata), el contenido no se indexa |
| **Consultas** | Elasticsearch Query DSL (potente pero complejo) | LogQL (similar a PromQL, simple) |
| **Integración con Grafana** | Plugin oficial, funcional pero no nativo | Nativo — datasource de primera clase |
| **Latencia de ingesta** | Mayor debido a la indexación | Menor — solo procesa labels |
| **Escalabilidad** | Excelente para logs estructurados a gran escala | Excelente para microservicios con bajo volumen |

### Decisión: Loki

Dado que:
1. Ya usamos Grafana para métricas (Prometheus) → centralizar en un solo UI
2. La infraestructura ya es pesada (Kafka, Neo4j, Redis, PostgreSQL, LDAP)
3. El volumen de logs es moderado (8 microservicios en dev)
4. LogQL es más accesible que EL Query DSL

**Loki + Alloy + Grafana** es la solución óptima para este proyecto.

---

## Criterios de Éxito

- [ ] `docker compose up -d` levanta toda la infraestructura sin errores
- [ ] Prometheus `/targets` muestra todos los targets en estado UP
- [ ] `curl localhost:8080/actuator/prometheus` retorna métricas de la demo app
- [ ] Grafana en `:3000` muestra ambas datasources en verde
- [ ] Los dashboards se cargan con datos en tiempo real
- [ ] Loki recibe logs de los contenedores Docker
- [ ] Al menos una alerta dispara al detener un servicio
- [ ] auth-service expone `/actuator/prometheus` con métricas personalizadas
- [ ] gateway-service expone `/actuator/prometheus` con métricas de validación QR

---

## Estructura de Archivos Creados

```
observability/
├── docker-compose.yml                    ← Stack completo de monitoreo
├── prometheus/
│   ├── prometheus.yml                    ← Scrape configs
│   └── rules/
│       └── alerts.yml                    ← Reglas de alertas
├── loki/
│   └── config.yml                        ← Configuración Loki single-binary
├── alloy/
│   └── config.alloy                      ← Recolección de logs Docker
├── alertmanager/
│   └── alertmanager.yml                  ← Routing de alertas
└── grafana/
    └── provisioning/
        ├── datasources/
        │   └── datasources.yml           ← Prometheus + Loki auto-provisioned
        └── dashboards/
            ├── dashboards.yml            ← Provider config
            ├── demo-app-dashboard.json
            ├── auth-service-dashboard.json
            └── gateway-service-dashboard.json

demo-observability/
├── Dockerfile
├── build.gradle.kts
├── settings.gradle.kts
└── src/main/
    ├── java/com/circleguard/demo/
    │   ├── DemoApplication.java
    │   ├── MetricsSimulator.java         ← @Scheduled + Micrometer counters/timers
    │   └── DemoController.java           ← REST endpoints
    └── resources/
        └── application.yml

services/circleguard-auth-service/
├── build.gradle.kts                      ← + micrometer-registry-prometheus
├── src/main/resources/application.yml   ← + prometheus endpoint expuesto
└── src/main/java/.../
    ├── metrics/AuthMetrics.java          ← NUEVO
    └── controller/LoginController.java   ← + AuthMetrics inyectado

services/circleguard-gateway-service/
├── build.gradle.kts                      ← + micrometer-registry-prometheus
├── src/main/resources/application.yml   ← + prometheus endpoint expuesto
└── src/main/java/.../
    ├── metrics/GatewayMetrics.java       ← NUEVO
    └── controller/GateController.java    ← + GatewayMetrics inyectado
```
