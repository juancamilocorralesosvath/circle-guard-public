# CircleGuard — Terraform Infrastructure as Code

## Architecture Overview

```
terraform/
├── modules/
│   ├── namespace/        # Kubernetes Namespace + ResourceQuota
│   ├── service-account/  # ServiceAccount + Role + RoleBinding per service
│   └── configmap/        # Environment-specific ConfigMap wrapper
└── environments/
    ├── dev/              # circleguard-dev namespace
    ├── staging/          # circleguard-staging namespace
    └── prod/             # circleguard-prod namespace
```

## Infrastructure Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                     Docker Desktop (local k8s)                  │
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐  │
│  │  circleguard-dev │  │circleguard-staging│  │circleguard-  │  │
│  │                  │  │                  │  │   prod       │  │
│  │  ResourceQuota:  │  │  ResourceQuota:  │  │ ResourceQuota│  │
│  │  CPU: 2/4        │  │  CPU: 4/8        │  │ CPU: 8/16    │  │
│  │  Mem: 2/4 Gi     │  │  Mem: 4/8 Gi     │  │ Mem: 8/16 Gi │  │
│  │  Pods: 30        │  │  Pods: 40        │  │ Pods: 60     │  │
│  │                  │  │                  │  │              │  │
│  │  ConfigMap ──────┼──┼── ConfigMap ─────┼──┼── ConfigMap  │  │
│  │  (dev values)    │  │  (staging vals)  │  │ (prod values)│  │
│  │                  │  │                  │  │              │  │
│  │  ServiceAccounts:│  │  ServiceAccounts:│  │ ServiceAccts:│  │
│  │  ├ auth          │  │  ├ auth          │  │ ├ auth       │  │
│  │  ├ gateway       │  │  ├ gateway       │  │ ├ gateway    │  │
│  │  ├ identity      │  │  ├ identity      │  │ ├ identity   │  │
│  │  ├ form          │  │  ├ form          │  │ ├ form       │  │
│  │  ├ notification  │  │  ├ notification  │  │ ├ notification│  │
│  │  └ promotion     │  │  └ promotion     │  │ └ promotion  │  │
│  └──────────────────┘  └──────────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                │
                    ┌───────────▼──────────┐
                    │   Terraform Cloud    │
                    │   Remote Backend     │
                    │                      │
                    │  Workspaces:         │
                    │  ├ circleguard-dev   │
                    │  ├ circleguard-staging│
                    │  └ circleguard-prod  │
                    └──────────────────────┘
```

## Modules

### `namespace`
Creates a Kubernetes `Namespace` and a `ResourceQuota` enforcing CPU, memory, and pod limits. Each environment has different quotas to reflect workload size:

| Environment | CPU req/lim | Memory req/lim | Max pods |
|-------------|-------------|----------------|----------|
| dev         | 2 / 4       | 2 Gi / 4 Gi    | 30       |
| staging     | 4 / 8       | 4 Gi / 8 Gi    | 40       |
| prod        | 8 / 16      | 8 Gi / 16 Gi   | 60       |

### `service-account`
Creates a `ServiceAccount`, a namespaced `Role` (read-only access to pods, services, configmaps, secrets, deployments), and a `RoleBinding` linking them. One instance per microservice per environment.

### `configmap`
Wraps a Kubernetes `ConfigMap` with environment-specific values. Allows changing configuration per environment (e.g., pool sizes differ between dev and prod) without rebuilding Docker images.

## Remote Backend: Terraform Cloud

State is stored remotely in [Terraform Cloud](https://app.terraform.io) (free tier).  
Three workspaces, one per environment: `circleguard-dev`, `circleguard-staging`, `circleguard-prod`.

### Setup (one-time)
1. Create a free account at https://app.terraform.io
2. Create organization named `circleguard`
3. Create three workspaces: `circleguard-dev`, `circleguard-staging`, `circleguard-prod`
4. Generate an API token: User Settings → Tokens → Create an API token
5. Export it: `export TF_TOKEN_app_terraform_io=<your-token>`
6. Or configure `~/.terraformrc`:
   ```hcl
   credentials "app.terraform.io" {
     token = "<your-token>"
   }
   ```
7. Add the token as Jenkins credential `TF_TOKEN` (secret text) for pipeline use.

## Usage

```bash
# Initialize (run once per environment)
cd terraform/environments/dev
terraform init

cd terraform/environments/staging
terraform init

cd terraform/environments/prod
terraform init

# Plan — review what will be created
terraform plan

# Apply — create the resources
terraform apply

# Destroy — tear down (use with caution on prod)
terraform destroy
```

## Cost Estimate

| Component | Local (Docker Desktop) | Cloud equivalent (AWS EKS) |
|-----------|----------------------|---------------------------|
| Kubernetes cluster | $0 (local) | ~$72/month (EKS control plane) |
| Compute (dev) | $0 | ~$50/month (2× t3.medium) |
| Compute (staging) | $0 | ~$100/month (4× t3.medium) |
| Compute (prod) | $0 | ~$400/month (8× t3.large) |
| Terraform Cloud | $0 (free tier) | $0 (free tier ≤ 500 resources) |
| **Total** | **$0** | **~$622/month** |

> All environments currently run on Docker Desktop at zero cost. The cloud equivalents are estimates for reference only.

## Environment Differences

| Setting | dev | staging | prod |
|---------|-----|---------|------|
| DB pool size | 10 | 25 | 50 |
| CPU quota | 2/4 | 4/8 | 8/16 |
| Memory quota | 2/4 Gi | 4/8 Gi | 8/16 Gi |
| Max pods | 30 | 40 | 60 |
