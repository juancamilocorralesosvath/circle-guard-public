# Chaos Engineering

CircleGuard now includes a repeatable Chaos Engineering package for the Kubernetes
deployment. The goal is to prove that the platform can recover from controlled
failures in critical runtime paths.

## Tooling

- Primary tool: Chaos Mesh.
- Local fallback: Kubernetes-native pod deletion for the gateway pod kill
  experiment when Chaos Mesh CRDs are not installed yet.
- Evidence format: Markdown reports under `chaos/results/`.

## Experiments

| Experiment | Manifest | Target | Failure injected | Expected behavior |
| --- | --- | --- | --- | --- |
| Gateway pod kill | `chaos/manifests/gateway-pod-kill.yaml` | `circleguard-gateway-service` | Deletes one gateway pod | Deployment recreates the pod and reaches Ready again |
| Promotion network delay | `chaos/manifests/promotion-network-delay.yaml` | `circleguard-promotion-service` | Adds 500 ms latency for 60 s | Promotion service stays available and recovers after cleanup |

## How to Install Chaos Mesh

```powershell
.\chaos\scripts\install-chaos-mesh.ps1
```

The script installs the Helm chart into the `chaos-mesh` namespace using the
Docker Desktop friendly `containerd` runtime settings.

## How to Run

Gateway pod kill through Chaos Mesh:

```powershell
.\chaos\scripts\run-chaos-experiment.ps1 -Experiment gateway-pod-kill
```

Gateway pod kill with local Kubernetes fallback:

```powershell
.\chaos\scripts\run-chaos-experiment.ps1 -Experiment gateway-pod-kill -AllowKubernetesFallback
```

Promotion latency test:

```powershell
.\chaos\scripts\run-chaos-experiment.ps1 -Experiment promotion-network-delay
```

## Evidence Checklist

Each run records:

- Deployment, pod, endpoint and event state before the experiment.
- The failure injection command or the reason it was skipped.
- Deployment, pod, endpoint and event state after the experiment.
- Rollout status for the affected service.

## Operational Notes

- Run pod kill during demos first; it is short and visibly proves recovery.
- Run network delay only after Chaos Mesh is installed, because Kubernetes has no
  native equivalent for network latency injection.
- Keep the blast radius limited to `circleguard-dev`.
