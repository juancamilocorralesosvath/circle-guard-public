# Kubernetes Foundation Documentation

This document outlines the Kubernetes infrastructure implemented to support the CircleGuard microservices.

## Directory Structure
```
k8s/
├── base/
│   ├── configmaps/
│   ├── secrets/
│   ├── postgres/
│   ├── redis/
│   ├── neo4j/
│   ├── kafka/
│   ├── openldap/
│   └── kustomization.yaml
├── overlays/
│   ├── dev/
│   ├── staging/
│   └── prod/
└── README.md
```

## Namespace Strategy
Namespaces are used to provide environment isolation. The `dev`, `staging`, and `prod` overlays automatically inject the `circleguard-dev`, `circleguard-staging`, and `circleguard-prod` namespaces into all resources using Kustomize.

## ConfigMap Strategy
All non-sensitive application configurations (e.g., connection strings, hostnames) are centralized in `configmaps/circleguard-config.yaml`.
- The `postgres-init-config` contains the initialization SQL required by PostgreSQL to create the 5 distinct microservice databases.

## Secret Strategy
Credentials and sensitive keys are segregated into 6 logical domain boundaries:
1. `circleguard-db-credentials`
2. `circleguard-neo4j-credentials`
3. `circleguard-ldap-credentials`
4. `circleguard-jwt-secret`
5. `circleguard-qr-secret`
6. `circleguard-vault-secret`

In the `base` definition, these contain hardcoded defaults ideal for local workshops and development. For production, these will be omitted or replaced dynamically via Jenkins CI/CD injection.

## Middleware Architectures
All middleware dependencies are configured for simplicity, portability, and cluster-native operation:
- **PostgreSQL**: StatefulSet with 1 replica, `1Gi` PVC. Automatically initializes databases via mounted ConfigMap.
- **Neo4j**: StatefulSet with 1 replica, `1Gi` PVC.
- **Redis**: In-memory Deployment with 1 replica (sufficient for workshop ephemeral caching).
- **Kafka / Zookeeper**: Zookeeper Deployment + Kafka StatefulSet with `1Gi` PVC. Configured as `PLAINTEXT://kafka-service:9092`.
- **OpenLDAP**: Deployment with 1 replica for Auth Service dependencies.

## Storage Strategy
We use generic `PersistentVolumeClaims` (PVCs) without explicitly specifying a `storageClassName`. This allows local Kubernetes clusters (like Minikube, Kind, or Docker Desktop) to automatically provision storage using their default StorageClass (usually `standard` or `hostpath`).

## DNS Naming Strategy
All services are mapped via Kubernetes DNS inside the namespace:
- `postgres-service`
- `neo4j-service`
- `redis-service`
- `kafka-service`
- `openldap-service`

## Operational Commands
**To deploy the development environment:**
```bash
kubectl apply -k k8s/overlays/dev
```

**To verify deployment:**
```bash
kubectl get pods -n circleguard-dev
kubectl get svc -n circleguard-dev
kubectl get pvc -n circleguard-dev
```

## Troubleshooting Commands
If a pod fails to start (e.g., `CrashLoopBackOff`), inspect logs:
```bash
kubectl logs <pod-name> -n circleguard-dev
```
Or describe the pod for event history:
```bash
kubectl describe pod <pod-name> -n circleguard-dev
```

## Known Local Cluster Constraints
- Initial deployment might take several minutes to download large middleware images (e.g. `neo4j:5.26`, `confluentinc/cp-kafka`).
- Memory pressure on Docker Desktop might cause OOMKills if all middleware boots simultaneously. Allocating at least 6GB to Docker Desktop is recommended.

## Future Microservice Integration Notes
Microservices should be implemented as `Deployment` objects in Phase 2B. They must:
- Inject the `circleguard-config` ConfigMap via `envFrom`.
- Inject the appropriate Secrets via `envFrom` or specific `valueFrom` mappings.
- Implement readiness probes with `initialDelaySeconds: 15-30` to wait for middleware to stabilize.
