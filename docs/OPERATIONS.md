# CircleGuard — Operations Manual

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Docker Desktop | ≥ 4.x (Kubernetes enabled) | docker.com/products/docker-desktop |
| kubectl | ≥ 1.28 | included with Docker Desktop |
| kustomize | ≥ 5.1 | `brew install kustomize` |
| Java | 21 | `brew install openjdk@21` |
| Gradle | via wrapper | `./gradlew` (no install needed) |

---

## 1. Start the Observability Stack

The observability stack runs outside Kubernetes via Docker Compose:

```bash
docker compose -f observability/docker-compose.yml up -d
```

| Service | URL |
|---------|-----|
| Grafana | http://localhost:3000 (admin/admin) |
| Prometheus | http://localhost:9090 |
| Kibana | http://localhost:5601 |
| Jaeger | http://localhost:16686 |
| Elasticsearch | http://localhost:9200 |

Stop it with:
```bash
docker compose -f observability/docker-compose.yml down
```

---

## 2. Install cert-manager (one-time per cluster)

Required for TLS certificate provisioning on the gateway service:

```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.5/cert-manager.yaml
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=cert-manager \
  -n cert-manager --timeout=120s
```

---

## 3. Deploy to Dev

```bash
kubectl apply -k k8s/overlays/dev
```

Verify all pods are running:
```bash
kubectl get pods -n circleguard-dev
```

Expected: all pods in `Running` state within ~2 minutes.

---

## 4. Verify Service Health

Each service exposes Spring Boot Actuator health endpoints:

| Service | Health URL |
|---------|-----------|
| gateway-service | http://localhost:30087/actuator/health |
| auth-service | http://localhost:30081/actuator/health |
| identity-service | http://localhost:30082/actuator/health |
| promotion-service | http://localhost:30083/actuator/health |
| form-service | http://localhost:30084/actuator/health |
| notification-service | http://localhost:30085/actuator/health |

Circuit breaker state:
```bash
curl http://localhost:30087/actuator/circuitbreakers
```

---

## 5. Run the Full CI/CD Pipeline

1. Open Jenkins at http://localhost:32080
2. Navigate to the CircleGuard pipeline job
3. Click **Build Now**

The pipeline runs: Build → SonarQube → Docker Build → Trivy Scan → Deploy Dev → Tests → ZAP → Staging → Approval → Prod.

Email notifications are sent to `correoalternativopersonal492@gmail.com` on success and failure.

---

## 6. Deploy to Staging / Prod

Staging is deployed automatically by the pipeline after integration tests pass.

Production requires manual approval in Jenkins (`Approval: Prod` stage). The approval gate appears in the pipeline UI — click **Proceed** to continue or **Abort** to cancel.

Manual deploy (if needed):
```bash
kubectl apply -k k8s/overlays/staging
kubectl apply -k k8s/overlays/prod
```

---

## 7. Rollback

**Quick rollback via kubectl:**
```bash
kubectl rollout undo deployment/circleguard-gateway-service -n circleguard-dev
```

Replace `circleguard-gateway-service` with the affected service name and `-dev` with the target namespace.

**Full pipeline rollback procedure:** see `docs/CI_CD_RUNBOOK.md`.

---

## 8. Terraform Infrastructure

Terraform manages namespaces, ResourceQuotas, ConfigMaps, and RBAC for each environment.

```bash
cd terraform/environments/dev
terraform init
terraform plan
terraform apply
```

Repeat for `staging` and `prod`. Requires a Terraform Cloud token in `~/.terraformrc`.

---

## 9. Infrastructure Cost Estimate

| Environment | Local (Docker Desktop) | Cloud Equivalent (AWS) |
|-------------|----------------------|------------------------|
| Kubernetes cluster | $0 | EKS ~$150/month |
| Databases (PostgreSQL, Neo4j) | $0 | RDS + Neo4j Aura ~$80/month |
| Message bus (Kafka) | $0 | MSK ~$60/month |
| Observability stack | $0 | CloudWatch + OpenSearch ~$50/month |
| Container registry | $0 (Docker Hub free) | ECR ~$5/month |
| **Total** | **$0** | **~$345/month** |

The local setup uses Docker Desktop's built-in Kubernetes cluster. No cloud costs are incurred during development.

---

## 10. Common Troubleshooting

**Pod stuck in `CrashLoopBackOff`:**
```bash
kubectl logs -n circleguard-dev deployment/<service-name> --previous
```

**SonarQube not reachable from Jenkins:**
```bash
docker start sonarqube   # restart the SonarQube container
```

**Jenkins agent not connecting:**
```bash
kubectl get pods -n jenkins
kubectl logs -n jenkins <agent-pod-name>
```

**cert-manager certificate not ready:**
```bash
kubectl describe certificate circleguard-gateway-tls -n circleguard-dev
```
