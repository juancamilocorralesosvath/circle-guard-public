# CircleGuard — Final Project Plan

## Context

Workshop 2 already delivered: Jenkins CI/CD pipeline, Docker+Kubernetes (dev/staging/prod overlays with Kustomize), 6 microservices deployed, unit/integration/E2E/Locust tests, Prometheus + Grafana + Loki + Alloy + AlertManager running, Grafana dashboards for auth-service and gateway-service, automatic changelog generation.

The final project builds on that foundation and requires: Terraform IaC, Design Patterns, CI/CD enhancements (SonarQube, Trivy, notifications, approval gates), OWASP ZAP security testing, ELK Stack, distributed tracing, more Grafana dashboards, RBAC/security hardening, and documentation.

---

## What already exists (do not redo)

- `k8s/` — Kustomize base + dev/staging/prod overlays for all 6 services
- `Jenkinsfile` — full build/test/docker/deploy/changelog pipeline
- `performance/locustfile.py` — Locust performance tests
- `observability/` — Prometheus, Grafana (with datasources + 3 dashboards), Loki, Alloy, AlertManager
- `services/*/src/test/` — unit, integration, E2E tests
- `CHANGELOG.md` — auto-generated per pipeline run
- Liveness/readiness probes on most services

---

## Phase 1 — Agile Methodology Setup (10%)

**Goal:** Document the agile process so it's demonstrable.

**Steps:**
1. Create a GitHub Projects board (Kanban) in the repo with columns: Backlog, In Progress, Done.
2. Create issues for each phase of this plan as user stories with acceptance criteria.
3. Write `docs/AGILE.md` documenting:
   - Tool chosen: GitHub Projects (Kanban)
   - Branching strategy: GitHub Flow (feature branches → main, with PR reviews)
   - Sprint structure: 2 sprints, each covering a set of phases below
   - User stories and acceptance criteria for each sprint

**Files to create:**
- `docs/AGILE.md`

**Done when:** Board exists with populated cards, doc is written.

---

## Phase 2 — Terraform IaC (20%)

**Goal:** Represent the Kubernetes infrastructure as Terraform code, modular, multi-environment.

**Approach:** Use the `kubernetes` and `helm` Terraform providers targeting the local Docker Desktop cluster. Remote backend via Terraform Cloud (free tier) or a local S3-compatible backend.

**Steps:**
1. Create `terraform/` with this structure:
```
terraform/
  modules/
    namespace/        # creates k8s namespace + resource quotas
    service-account/  # RBAC service accounts
    configmap/        # wraps k8s ConfigMaps
  environments/
    dev/
      main.tf         # calls modules, points to dev namespace
      variables.tf
      terraform.tfvars
    staging/
      main.tf
      variables.tf
      terraform.tfvars
    prod/
      main.tf
      variables.tf
      terraform.tfvars
  backend.tf          # remote backend config (Terraform Cloud or HTTP)
```
2. Each environment's `main.tf` creates: namespace, resource quotas, RBAC roles/bindings for the services.
3. Remote backend: use Terraform Cloud workspace (free). Add `TF_TOKEN` to Jenkins credentials.
4. Write `docs/TERRAFORM.md` with architecture diagram (text-based ASCII) and cost estimate.

**Files to create:** `terraform/` tree above, `docs/TERRAFORM.md`

**Done when:** `terraform init && terraform plan` runs cleanly for each environment.

---

## Phase 3 — Design Patterns (10%)

**Goal:** Document existing patterns + implement Circuit Breaker and External Configuration.

**Steps:**
1. Write `docs/DESIGN_PATTERNS.md` identifying patterns already in use:
   - API Gateway (gateway-service)
   - Service Registry (Kubernetes DNS)
   - Sidecar/Ambassador (Alloy for log collection)
   - Strangler Fig (incremental microservice migration)

2. Implement **Circuit Breaker** using Resilience4j in `circleguard-gateway-service`:
   - Add `resilience4j-spring-boot3` dependency to `services/circleguard-gateway-service/build.gradle.kts`
   - Annotate existing proxy calls with `@CircuitBreaker(name = "downstream", fallbackMethod = "fallback")`
   - Add config to `application.yml`: thresholds, wait duration, fallback response
   - Expose circuit breaker state via actuator (`/actuator/circuitbreakers`)

3. Implement **External Configuration** pattern — already partially done via ConfigMaps. Formalize it:
   - Ensure every env var in all 6 services maps to a k8s ConfigMap key in `k8s/base/configmaps/`
   - Document in `docs/DESIGN_PATTERNS.md` how ConfigMaps provide environment-specific config without rebuilding images

4. Document both patterns in `docs/DESIGN_PATTERNS.md`: purpose, diagram, benefits, how they're used here.

**Files to modify:** `services/circleguard-gateway-service/build.gradle.kts`, `services/circleguard-gateway-service/src/main/resources/application.yml`
**Files to create:** `docs/DESIGN_PATTERNS.md`

---

## Phase 4 — CI/CD Enhancements (15%)

**Goal:** Add SonarQube, Trivy, Slack notifications, production approval gate, semantic versioning.

**Steps:**

### 4a. SonarQube
- Add SonarQube stage to `Jenkinsfile` after the Build & Test stage:
  ```groovy
  stage('SonarQube Analysis') {
    steps {
      withSonarQubeEnv('sonarqube') {
        sh './gradlew sonarqube --no-daemon'
      }
    }
  }
  stage('Quality Gate') {
    steps { waitForQualityGate abortPipeline: true }
  }
  ```
- Add `org.sonarqube` plugin to root `build.gradle.kts`
- SonarQube already runs in Docker (`docker run -d -p 9000:9000 sonarqube:lts-community`)
- Add `sonarqube` credential in Jenkins pointing to the local SonarQube instance

### 4b. Trivy
- Add Trivy scan stage after Docker Build in `Jenkinsfile`:
  ```groovy
  stage('Container Scan') {
    steps {
      script {
        SERVICES.split().each { svc ->
          sh "trivy image --exit-code 0 --severity HIGH,CRITICAL ${DOCKER_USER}/circleguard-${svc}:${GIT_COMMIT_SHORT}"
        }
      }
    }
  }
  ```
- `--exit-code 0` means it reports but doesn't fail the build (adjust to `1` once baseline is clean)

### 4c. Slack notifications
- Add Jenkins Slack plugin credential (`slack-token`)
- Add to `Jenkinsfile` post block:
  ```groovy
  post {
    failure { slackSend channel: '#ci-alerts', message: "FAILED: ${env.JOB_NAME} #${env.BUILD_NUMBER}" }
    success { slackSend channel: '#ci-alerts', message: "SUCCESS: ${env.JOB_NAME} #${env.BUILD_NUMBER}" }
  }
  ```

### 4d. Production approval gate
- Add `input` step in Jenkinsfile before production deploy:
  ```groovy
  stage('Approval: Prod') {
    when { branch 'main' }
    steps { input message: 'Deploy to production?', ok: 'Deploy' }
  }
  ```

### 4e. Semantic versioning
- Replace current date-based tag with `git describe --tags --abbrev=0` + bump logic
- On `main` branch: tag is auto-incremented patch version (e.g. `v1.0.0` → `v1.0.1`)
- Add a `scripts/bump-version.sh` that reads latest tag and creates the next one

**Files to modify:** `Jenkinsfile`, root `build.gradle.kts`
**Files to create:** `scripts/bump-version.sh`

---

## Phase 5 — Testing Enhancements (15%)

**Goal:** Add OWASP ZAP security tests and JaCoCo coverage reports.

**Steps:**

### 5a. OWASP ZAP
- Add `performance/zap-scan.sh` script that:
  ```bash
  docker run --rm -v $(pwd)/zap-reports:/zap/wrk owasp/zap2docker-stable \
    zap-baseline.py -t http://host.docker.internal:30087 -r zap-report.html
  ```
- Add ZAP stage to `Jenkinsfile` in the E2E/security test section:
  ```groovy
  stage('Security Scan (ZAP)') {
    steps { sh 'bash performance/zap-scan.sh' }
    post { always { publishHTML([reportDir: 'zap-reports', reportFiles: 'zap-report.html', reportName: 'ZAP Security Report']) } }
  }
  ```

### 5b. JaCoCo coverage
- Add `jacoco` plugin to `build.gradle.kts` (root)
- Configure minimum coverage threshold (e.g. 60%)
- Publish coverage report in Jenkins via `publishHTML` after build

**Files to create:** `performance/zap-scan.sh`
**Files to modify:** root `build.gradle.kts`, `Jenkinsfile`

---

## Phase 6 — Observability Completion (10%)

**Goal:** Add ELK Stack, distributed tracing (Jaeger), and dashboards for remaining services.

### 6a. ELK Stack
- Add to `observability/docker-compose.yml`:
  ```yaml
  elasticsearch:
    image: elasticsearch:8.13.0
    environment: { discovery.type: single-node, ES_JAVA_OPTS: "-Xms512m -Xmx512m", xpack.security.enabled: "false" }
    ports: ["9200:9200"]

  logstash:
    image: logstash:8.13.0
    volumes: ["./logstash/pipeline:/usr/share/logstash/pipeline"]
    ports: ["5044:5044"]
    depends_on: [elasticsearch]

  kibana:
    image: kibana:8.13.0
    ports: ["5601:5601"]
    depends_on: [elasticsearch]
  ```
- Create `observability/logstash/pipeline/logstash.conf` to ingest from Loki or Alloy → Elasticsearch
- Configure Kibana index pattern for `circleguard-*`

### 6b. Distributed Tracing — Jaeger
- Add Jaeger to `observability/docker-compose.yml`:
  ```yaml
  jaeger:
    image: jaegertracing/all-in-one:1.57
    ports: ["16686:16686", "4317:4317"]
  ```
- Add OpenTelemetry dependency to auth-service and gateway-service `build.gradle.kts`:
  `implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.3.0")`
- Add to `application.yml` of both services:
  ```yaml
  otel:
    exporter.otlp.endpoint: http://jaeger:4317
    service.name: ${spring.application.name}
  ```

### 6c. Grafana dashboards for remaining services
- Create dashboard JSON files in `observability/grafana/provisioning/dashboards/` for:
  - `identity-service-dashboard.json`
  - `form-service-dashboard.json`
  - `notification-service-dashboard.json`
  - `promotion-service-dashboard.json`
- Each dashboard: request rate, error rate, JVM heap, active threads
- Add Jaeger as datasource in `observability/grafana/provisioning/datasources/datasources.yml`

### 6d. Business metrics
- Add to `circleguard-auth-service`: counter for login success/failure, counter for QR validations
- Add to `circleguard-form-service`: counter for form submissions
- These are already exposed via `/actuator/prometheus` once added via `MeterRegistry`

**Files to modify:** `observability/docker-compose.yml`, auth/gateway/form service `application.yml` and source
**Files to create:** `observability/logstash/pipeline/logstash.conf`, 4 dashboard JSON files, Jaeger datasource entry

---

## Phase 7 — Security (5%)

**Goal:** RBAC, secret management documentation, TLS basics.

**Steps:**

### 7a. RBAC
- `k8s/base/rbac/` already exists. Verify each service has a `ServiceAccount` and a `Role` limiting it to its own namespace resources.
- Add missing `RoleBinding` manifests for services that don't have them yet.

### 7b. Secret management
- Document in `docs/SECURITY.md` the current approach: Kubernetes `Secret` objects managed via `k8s/base/secrets/`, never committed in plaintext (base64 encoded, injected as env vars).
- Add note on how to rotate secrets (kubectl apply new secret → rollout restart).

### 7c. TLS (simple)
- Use `cert-manager` with a self-signed `ClusterIssuer` for the dev/staging environments:
  ```bash
  kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.5/cert-manager.yaml
  ```
- Add a `Certificate` resource for the gateway-service Ingress in `k8s/base/services/gateway-service.yaml`

**Files to create:** `docs/SECURITY.md`, cert-manager manifest in `k8s/base/`
**Files to modify:** `k8s/base/rbac/` (fill missing bindings), `k8s/base/services/gateway-service.yaml`

---

## Phase 8 — Documentation & Final Polish (10%)

**Goal:** Complete all required docs for submission.

**Steps:**
1. `docs/ARCHITECTURE.md` — ASCII diagram showing all 8 services, their dependencies, the gateway, databases, and observability stack
2. `docs/TERRAFORM.md` — infrastructure architecture + cost estimate (Docker Desktop = $0, note cloud equivalent costs)
3. `docs/OPERATIONS.md` — basic ops manual: how to start everything locally, how to deploy, how to rollback, how to check health
4. `docs/DESIGN_PATTERNS.md` — (created in Phase 3, finalize)
5. `docs/AGILE.md` — (created in Phase 1, finalize)
6. `docs/SECURITY.md` — (created in Phase 7, finalize)
7. Update root `README.md` with links to all docs and quick-start guide
8. Record demo video showing: app running, Grafana dashboards, Jaeger traces, Kibana logs, Jenkins pipeline execution, Locust test results

---

## Execution Order & Priority

| Phase | Weight | Effort | Do first? |
|-------|--------|--------|-----------|
| 1 — Agile docs | 10% | Low | Yes — quick win |
| 2 — Terraform | 20% | High | Yes — biggest grade |
| 3 — Design Patterns | 10% | Medium | Yes |
| 4 — CI/CD enhancements | 15% | Medium | Yes |
| 5 — Testing (ZAP + coverage) | 15% | Low | Yes |
| 6 — Observability (ELK + tracing) | 10% | Medium | Yes |
| 7 — Security | 5% | Low | After 1-6 |
| 8 — Documentation | 10% | Low | Last |

**Sprint 1:** Phases 1, 2, 3
**Sprint 2:** Phases 4, 5, 6, 7, 8

---

## Verification Checklist

- [ ] `terraform plan` runs for dev/staging/prod without errors
- [ ] Jenkins pipeline passes with SonarQube quality gate green
- [ ] Jenkins pipeline shows Trivy scan output per image
- [ ] ZAP report generated and published in Jenkins
- [ ] Prometheus shows all 6 services `up` as targets
- [ ] Grafana shows dashboards for all 6 services
- [ ] Jaeger UI (`localhost:16686`) shows traces from auth and gateway services
- [ ] Kibana (`localhost:5601`) shows logs from CircleGuard services
- [ ] Circuit breaker state visible at `localhost:30087/actuator/circuitbreakers`
- [ ] GitHub Projects board has all user stories with status
- [ ] All docs in `docs/` are complete and linked from README
