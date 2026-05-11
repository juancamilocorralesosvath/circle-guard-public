# Kubernetes Foundation (Kustomize)

This directory contains the Kubernetes foundation infrastructure for the CircleGuard platform.
It uses **Kustomize** to manage environment overlays seamlessly.

## Quick Start (Workshop Dev Environment)

To deploy the entire middleware foundation and namespaces for local development:
```bash
kubectl apply -k k8s/overlays/dev
```

## Validation Commands

Check if everything is running:
```bash
kubectl get all -n circleguard-dev
```

Check Persistent Volume Claims:
```bash
kubectl get pvc -n circleguard-dev
```

## Rollout & Maintenance

**Rollout Status:**
```bash
kubectl rollout status deployment/circleguard-auth-service -n circleguard-dev
```

**Restart a Service (to pick up config/secret changes):**
```bash
kubectl rollout restart deployment/circleguard-auth-service -n circleguard-dev
```

**Scale a Service:**
```bash
kubectl scale deployment/circleguard-auth-service --replicas=2 -n circleguard-dev
```

## Detailed Validation

**Test Inter-Service Connectivity:**
```bash
kubectl exec deploy/circleguard-auth-service -n circleguard-dev -- curl -s http://circleguard-identity-service:8083/actuator/health/readiness
```

**Sweep All Microservices Readiness:**
```bash
for svc in circleguard-identity-service:8083 circleguard-auth-service:8180 circleguard-form-service:8086 circleguard-promotion-service:8088 circleguard-gateway-service:8087 circleguard-notification-service:8082; do
  echo -n "$svc: "
  kubectl exec deploy/circleguard-auth-service -n circleguard-dev -- curl -s http://$svc/actuator/health/readiness
  echo
done
```

## Cleanup

To tear down the environment (including all data):
```bash
kubectl delete -k k8s/overlays/dev
```

## Structure Overview
- `base/`: Contains raw StatefulSets, Deployments, Services, ConfigMaps, and Secrets for all middleware components.
- `overlays/dev/`: Injects the `circleguard-dev` namespace.
- `overlays/staging/`: Injects the `circleguard-staging` namespace.
- `overlays/prod/`: Injects the `circleguard-prod` namespace.

> [!NOTE]
> Do not edit the `overlays` unless you want to override specific base values (like removing dev secrets in production). Modify `base` if you are changing fundamental topology.

## Jenkins CI/CD (added)

This repository includes Kubernetes manifests to run a workshop Jenkins instance inside the cluster.

To install Jenkins (workshop) alongside existing resources:

```bash
kubectl apply -k k8s/overlays/dev
# Jenkins manifests are included under k8s/base/services/jenkins
```

Access Jenkins UI using the `jenkins` Service NodePort:

```bash
kubectl -n jenkins get svc jenkins
# visit http://<node-ip>:32080
```

Notes:
- Jenkins is installed into the `jenkins` namespace and includes a StatefulSet with a PVC for `/var/jenkins_home`.
- Agents must be scheduled to nodes labeled `docker-builder=true` to enable Docker-outside-of-Docker builds (DooD). Label a node with:
  ```bash
  kubectl label node <node-name> docker-builder=true
  ```

