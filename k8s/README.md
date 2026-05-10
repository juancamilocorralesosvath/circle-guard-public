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
