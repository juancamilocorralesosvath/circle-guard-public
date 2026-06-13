# Bonus Results: Chaos Engineering and FinOps

Date: 2026-06-12
Namespace tested: `circleguard-dev`

## Chaos Engineering Result

Implemented:

- Chaos Mesh install script: `chaos/scripts/install-chaos-mesh.ps1`
- Gateway pod kill experiment: `chaos/manifests/gateway-pod-kill.yaml`
- Promotion network latency experiment: `chaos/manifests/promotion-network-delay.yaml`
- Repeatable runner and evidence generator: `chaos/scripts/run-chaos-experiment.ps1`
- Documentation: `docs/CHAOS_ENGINEERING.md`

Executed test:

```powershell
.\chaos\scripts\run-chaos-experiment.ps1 -Experiment gateway-pod-kill -AllowKubernetesFallback
```

Evidence file:

```text
chaos/results/chaos-gateway-pod-kill-20260612-221825.md
```

Before:

- `circleguard-gateway-service` deployment was `1/1`.
- Gateway pod was Running: `circleguard-gateway-service-79d95fd587-g24nc`.

Injection:

- Chaos Mesh CRDs were not installed in the local cluster.
- The runner used the Kubernetes-native fallback and deleted the gateway pod.

After:

- Kubernetes created replacement pod `circleguard-gateway-service-79d95fd587-7752p`.
- Gateway deployment returned to `1/1`.
- Final rollout status: `deployment "circleguard-gateway-service" successfully rolled out`.

Status: implemented and tested with local fallback. Chaos Mesh manifests are ready for full CRD-based execution after installing Chaos Mesh.

## FinOps Result

Implemented:

- Cost model: `finops/cost-model.json`
- Baseline collector: `finops/scripts/collect-finops.ps1`
- Resource budget policy: `finops/manifests/dev-resource-policy.yaml`
- Scale-to-zero policy: `finops/manifests/dev-scale-to-zero-cronjobs.yaml`
- Grafana dashboard: `observability/grafana/provisioning/dashboards/finops-cost-dashboard.json`
- Documentation: `docs/FINOPS.md`

Executed test:

```powershell
.\finops\scripts\collect-finops.ps1
```

Evidence file:

```text
finops/results/finops-baseline-20260612-221736.md
```

Before:

- Live cluster had 12 containers missing CPU/memory requests.
- Live cluster had 12 containers missing CPU/memory limits.
- Metrics API was unavailable, so usage-based live metrics could not be collected.
- Requested cost was reported as `USD 0.00` because the live deployment specs currently omit requests.

After:

- Resource policy dry-run passed:
  - `limitrange/circleguard-default-container-limits created (dry run)`
  - `resourcequota/circleguard-dev-compute-budget created (dry run)`
- Scale-to-zero policy dry-run passed:
  - scaler ServiceAccount, Role and RoleBinding validated.
  - down/up CronJobs validated.
- Grafana dashboard JSON validated successfully.

Status: implemented and validated with dry-runs. Policies were not applied to the live cluster because applying a ResourceQuota while live deployments omit resource requests can block future pod recreation.

## Final Cluster State

- All deployments in `circleguard-dev` are `1/1`.
- Gateway recovered after the chaos test and is Running with 0 restarts on the replacement pod.
- No FinOps policy was applied destructively to the cluster.
