# CI/CD Runbook

Quick recovery and operational commands for Jenkins-driven delivery.

Jenkins UI access (NodePort example)
```bash
kubectl -n jenkins get svc jenkins
# visit http://<node-ip>:32080
```

Rollback a deployment (manual)
```bash
kubectl rollout undo deployment/circleguard-auth-service -n circleguard-dev
kubectl rollout status deployment/circleguard-auth-service -n circleguard-dev
```

Force re-deploy previous tag
```bash
kubectl set image deployment/circleguard-auth-service circleguard-auth-service=juanc0410/circleguard-auth-service:<previous-tag> -n circleguard-dev
```

Force apply the kustomize overlay (useful for debugging)
```bash
kustomize build k8s/overlays/dev | kubectl apply -f -
```

Check rollout and pod status
```bash
kubectl rollout status deployment/circleguard-auth-service -n circleguard-dev
kubectl get pods -n circleguard-dev
kubectl describe pod <pod-name> -n circleguard-dev
kubectl logs <pod-name> -n circleguard-dev
```

Retrieve Jenkins logs (if master pod exists)
```bash
kubectl -n jenkins logs statefulset/jenkins
```

Troubleshooting tips
- If Docker builds fail on the agent: verify that the agent pod is scheduled on a node with `docker` installed and that `/var/run/docker.sock` is mounted.
- If `kubectl` RBAC denies operations: inspect the `jenkins` ServiceAccount bindings (see `k8s/base/rbac/jenkins-clusterrolebinding.yaml`).
- If images are not found on Docker Hub: verify `dockerhub-creds` and check image tags in Docker Hub.
