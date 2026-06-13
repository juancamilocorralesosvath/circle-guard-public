# FinOps

CircleGuard now includes a FinOps package for Kubernetes cost visibility and
cost-control policies in the `circleguard-dev` environment.

## Implemented Controls

| Control | File | Purpose |
| --- | --- | --- |
| Cost model | `finops/cost-model.json` | Converts requested CPU and memory into a monthly estimate |
| Baseline collector | `finops/scripts/collect-finops.ps1` | Captures live requests, limits, missing policies and dry-run validation |
| Resource policy | `finops/manifests/dev-resource-policy.yaml` | Adds default limits and a namespace compute budget |
| Scale-to-zero jobs | `finops/manifests/dev-scale-to-zero-cronjobs.yaml` | Scales non-critical dashboard and file services down after hours |
| Grafana dashboard | `observability/grafana/provisioning/dashboards/finops-cost-dashboard.json` | Visualizes requested capacity, usage and estimated cost |

## How to Collect the Baseline

```powershell
.\finops\scripts\collect-finops.ps1
```

Reports are written to `finops/results/`.

## How to Apply Cost Policies

```powershell
.\finops\scripts\collect-finops.ps1 -ApplyPolicies
```

The default run validates the manifests with `kubectl apply --dry-run=client`.
Apply the policies only after confirming the live deployments have resource
requests and limits, because a ResourceQuota can block future pods that omit
requests.

## Cost Method

Estimated monthly requested cost is:

```text
(requested_cpu_cores * 730 * cpu_core_hour) +
(requested_memory_gib * 730 * memory_gib_hour)
```

The default model is intentionally conservative and classroom-friendly. Docker
Desktop itself does not generate a cloud bill, but this model makes the
capacity footprint visible and comparable.

## Before and After

Before:

- No FinOps-specific audit script existed.
- No namespace ResourceQuota or LimitRange existed.
- No dashboard tracked requested capacity or cost.
- Live cluster may drift from the repository manifests.

After:

- Baseline reports capture live resources and missing requests/limits.
- ResourceQuota and LimitRange manifests define the dev budget.
- Scale-to-zero cronjobs document an explicit saving policy for non-critical
  workloads.
- Grafana has a dedicated CircleGuard FinOps dashboard.
