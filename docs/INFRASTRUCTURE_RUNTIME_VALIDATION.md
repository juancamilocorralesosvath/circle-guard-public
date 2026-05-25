# Infrastructure Runtime Validation (Phase 2A.5)

This document summarizes the real-world validation of the Kubernetes middleware infrastructure foundation in the `circleguard-dev` environment.

## Cluster Validation Results
The local cluster (Docker Desktop) successfully applied all generated Kustomize objects.
- **Namespace Validation:** `circleguard-dev` isolates all infrastructure effectively.

## Pod Startup Results
| Component | Status | Restarts | Startup Timing | Notes |
|---|---|---|---|---|
| Zookeeper | Running | 0 | < 1m | Boots instantly. |
| Redis | Running | 0 | < 1m | Boots instantly. |
| OpenLDAP | Running | 0 | < 1m | Boots instantly. |
| Kafka | Running | 0 | ~2m | Depends on Zookeeper. Standard container pull delay. |
| PostgreSQL | Running | 0 | ~2m | Bound to PVC, executes `init-db.sql` successfully. |
| Neo4j | Running | 3 | ~4m | Initially crashed due to `CrashLoopBackOff`. |

### Neo4j Startup Findings
Neo4j initially entered `CrashLoopBackOff` because it interprets all environment variables starting with `NEO4J_` as config keys. Because Kubernetes automatically injects service discovery variables (like `NEO4J_SERVICE_PORT_7687_TCP_PROTO=tcp`), Neo4j's strict configuration validator threw an error (`Unrecognized setting`). 
**Resolution:** I added `NEO4J_server_config_strict__validation_enabled=false` to the Neo4j `StatefulSet` definition, which successfully stabilized the pod.

## PVC Validation
All `StatefulSets` dynamically generated PersistentVolumeClaims bound correctly using the host cluster's default StorageClass (`hostpath` in Docker Desktop).
- `pgdata-postgres-0` (1Gi)
- `neo4jdata-neo4j-0` (1Gi)
- `kafkadata-kafka-0` (1Gi)

## DNS and Service Networking Validation
Using an ephemeral `busybox` pod, I validated CoreDNS routing.
- `postgres-service` correctly resolved to its internal ClusterIP.
- `kafka-service` correctly resolved to its internal ClusterIP.
- `nc -vz kafka-service 9092` succeeded, proving the internal TCP socket is open and listening.

## Secret and ConfigMap Injection Validation
- **ConfigMap Validation:** The `postgres-init-config` successfully mounted into PostgreSQL as a volume, initializing all 5 required databases at startup.
- **Secret Injection Validation:** Both Neo4j and PostgreSQL correctly extracted `DB_USERNAME`, `DB_PASSWORD` from the injected Kubelet `valueFrom.secretKeyRef` mappings. Earlier typo errors with integer parsing (`VAULT_HASH_SALT`) were identified and resolved during Kustomize deployment.

## Kafka Validation
Kafka's advertised listener successfully maps to `PLAINTEXT://kafka-service:9092`. Because it is resolving internally via DNS and port 9092 is exposed on the cluster IP, Spring Boot clients residing in the same namespace will seamlessly auto-connect using this default URL.

## Readiness/Liveness Findings
Middleware startup latency (pulling large images like `neo4j:5.26` and `confluentinc/cp-kafka`) combined with JVM spin-up time can take 2-5 minutes in local clusters. 
**Recommendation:** When we deploy the Spring Boot microservices, we must configure their `readinessProbe` with an `initialDelaySeconds` of at least `30-45` seconds, and they must gracefully tolerate DB connection loss to trigger Kubernetes `CrashLoopBackOff` while middleware warms up.

## Operational kubectl Commands
**View everything in the namespace:**
```bash
kubectl get all -n circleguard-dev
```
**Access Kafka logs:**
```bash
kubectl logs kafka-0 -n circleguard-dev
```
**View Neo4j Config Variables:**
```bash
kubectl exec -it neo4j-0 -n circleguard-dev -- env | grep NEO4J
```

## Known Runtime Risks & Constraints
1. **Memory Pressure:** Docker Desktop must be allocated at least 6GB to 8GB of RAM. If memory pressure is high, Kafka or Neo4j JVMs may trigger OOMKills.
2. **Cold Start Latency:** On the very first `kubectl apply -k`, PVC provisioning and multi-gigabyte image pulls cause pod scheduling to block. If deploying microservices concurrently, they will restart several times while waiting.

## Recommended Apply Order
For a pristine environment, apply the infrastructure first, wait for `Running` status, and then apply microservices:
1. `kubectl apply -k k8s/overlays/dev`
2. `kubectl wait --for=condition=ready pod -l app=postgres -n circleguard-dev --timeout=300s`
3. Deploy Spring Boot microservices.
