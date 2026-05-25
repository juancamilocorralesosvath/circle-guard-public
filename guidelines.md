````markdown
# Observability & Monitoring Implementation Strategy — CircleGuard

## Objective

Implement a complete observability stack for the CircleGuard project in a progressive, low-risk manner.

The goal is to successfully demonstrate:

- Centralized logging
- Metrics collection
- Dashboards
- Alerts
- Runtime monitoring

while avoiding unnecessary operational complexity during the initial implementation phase.

---

# IMPORTANT IMPLEMENTATION STRATEGY

DO NOT begin by integrating observability into all 8 microservices simultaneously.

Instead, follow an incremental rollout strategy:

1. Validate the observability infrastructure in isolation
2. Validate with a minimal Spring Boot demo application
3. Integrate progressively into only 1–2 real microservices
4. Expand later only if time permits

This approach minimizes:
- Kubernetes debugging complexity
- Kafka/Neo4j startup issues
- Memory pressure
- Networking problems
- Docker Desktop instability
- Distributed system troubleshooting overhead

---

# PHASE 1 — Standalone Observability Stack

## Goal

Deploy and validate the monitoring/logging infrastructure independently before touching the real microservices.

## Required Components

Deploy the following stack using Docker Compose first:

### Metrics
- Prometheus
- Alertmanager

### Dashboards
- Grafana

### Logs
- Grafana Loki
- Grafana Alloy (preferred over deprecated Grafana Agent)

### System Metrics
- Node Exporter

---

# PHASE 1 TASKS

## 1. Create observability directory

Suggested structure:

```text
observability/
├── docker-compose.yml
├── prometheus/
│   └── prometheus.yml
├── loki/
│   └── config.yml
├── alloy/
│   └── config.alloy
├── grafana/
│   └── provisioning/
├── alertmanager/
│   └── alertmanager.yml
└── dashboards/
````

---

## 2. Configure Prometheus

Requirements:

* Scrape itself
* Scrape Node Exporter
* Scrape the demo Spring Boot app
* Store metrics locally

Validate:

* `/targets`
* `/graph`
* metrics availability

---

## 3. Configure Loki

Requirements:

* Receive logs from Alloy
* Store logs locally
* Expose query endpoint

Validate:

* log ingestion works
* logs appear in Grafana

---

## 4. Configure Alloy

Requirements:

* Read Docker container logs
* Send logs to Loki
* Attach labels:

  * service
  * container
  * level
  * environment

---

## 5. Configure Grafana

Requirements:

Add data sources:

* Prometheus
* Loki

Create dashboards for:

* JVM metrics
* CPU
* Memory
* HTTP requests
* Error rates
* Logs

---

## 6. Configure Alertmanager

Create alerts for:

### Critical

* Service down
* High error rate

### Warning

* High memory usage
* High CPU usage

---

# PHASE 2 — Demo Spring Boot Application

## Goal

Validate the full observability flow using a simple controlled application before integrating the real system.

---

# PHASE 2 TASKS

## 1. Create a minimal Spring Boot app

Requirements:

Dependencies:

* spring-boot-starter-web
* spring-boot-starter-actuator
* micrometer-registry-prometheus

---

## 2. Enable Actuator Endpoints

Expose:

* health
* info
* prometheus
* metrics

Example:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

---

## 3. Implement Custom Micrometer Metrics

Create counters such as:

* successfulOperations
* failedOperations
* processedRequests

Also include:

* Timer metrics
* Gauge metrics

---

## 4. Add Scheduled Background Activity

Create scheduled jobs that:

* generate logs
* generate metrics
* simulate workload
* occasionally simulate failures

This is required so Grafana and Loki always have data to display.

---

## 5. Validate Metrics

Confirm:

* Prometheus scrapes correctly
* Metrics appear in Grafana
* Dashboards populate correctly

---

## 6. Validate Logs

Confirm:

* Logs are collected by Alloy
* Logs are stored in Loki
* Logs are searchable in Grafana

---

# PHASE 3 — Integrate Real CircleGuard Services

## IMPORTANT

DO NOT integrate all services initially.

Only integrate:

* auth-service
* gateway-service

These services are intentionally chosen because they are operationally simpler and have fewer infrastructure dependencies.

Avoid initially integrating:

* promotion-service
* Kafka-heavy services
* Neo4j-dependent services

---

# PHASE 3 TASKS

## 1. Add Actuator + Micrometer

To:

* auth-service
* gateway-service

Required dependencies:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("io.micrometer:micrometer-registry-prometheus")
```

---

## 2. Expose Metrics Endpoints

Expose:

* `/actuator/prometheus`
* `/actuator/health`
* `/actuator/info`

---

## 3. Configure Prometheus Scraping

Add both services to Prometheus scrape targets.

---

## 4. Configure Log Collection

Ensure Alloy collects logs from:

* auth-service
* gateway-service

---

## 5. Create Service Dashboards

Dashboards should include:

### Auth Service

* login requests
* authentication failures
* response times
* JVM metrics

### Gateway Service

* QR validations
* request latency
* RED/GREEN validation counts
* error rates

---

## 6. Configure Alerts

Examples:

### Auth Service

* excessive login failures
* service unavailable

### Gateway Service

* latency too high
* many 500 responses

---

# PHASE 4 — Kubernetes Integration (Optional / Final)

Only proceed if previous phases are fully stable.

## Tasks

* Deploy observability stack into Kubernetes
* Create ConfigMaps
* Create Persistent Volumes
* Configure ServiceMonitors (optional)
* Integrate with existing namespaces

---

# ELK STACK REQUIREMENT

The workshop only requires understanding ELK.

DO NOT implement ELK unless explicitly necessary.

Instead:

* Study Elasticsearch
* Study Logstash
* Study Kibana
* Compare ELK vs Loki

Document:

* architecture differences
* operational complexity
* resource usage
* advantages/disadvantages

Preferred implementation:

* Loki + Grafana

Reason:

* lighter
* simpler
* better suited for this workshop
* integrates naturally with Grafana

---

# RESOURCE CONSTRAINTS

The current infrastructure already includes:

* PostgreSQL
* Kafka
* Neo4j
* Redis
* LDAP
* Kubernetes

This environment is memory-intensive.

Avoid:

* unnecessary replicas
* excessive ingestion
* monitoring all services simultaneously
* high-cardinality metrics

Recommended Docker Desktop allocation:

* 8GB RAM minimum

---

# SUCCESS CRITERIA

The implementation is considered successful when:

## Metrics

* Prometheus scrapes Spring Boot metrics
* Metrics appear in Grafana

## Logs

* Loki receives logs
* Logs are searchable in Grafana

## Alerts

* Alertmanager triggers alerts successfully

## Dashboards

* JVM metrics visible
* Request metrics visible
* Error metrics visible

## Real Services

* auth-service monitored
* gateway-service monitored

---

# FINAL DELIVERABLE EXPECTATIONS

Prepare screenshots and explanations for:

* Grafana dashboards
* Loki log queries
* Prometheus targets
* Alert rules
* Metrics graphs
* Service health
* Kubernetes deployments (if applicable)

Also document:

* architectural decisions
* incremental rollout strategy
* operational tradeoffs
* why Loki was preferred over ELK
* lessons learned during implementation

```
```
