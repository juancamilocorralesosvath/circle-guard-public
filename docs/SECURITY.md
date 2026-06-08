# CircleGuard — Security

## 1. RBAC

All six microservices run under a shared `circleguard-service` ServiceAccount defined in `k8s/base/rbac/services-rbac.yaml`. A namespace-scoped `Role` grants the minimum permissions required:

| Resource | Verbs |
|----------|-------|
| ConfigMaps | get, list, watch |
| Secrets | get |

No service has permissions to create, update, or delete Kubernetes resources. Jenkins uses a separate `ClusterRole` (`jenkins-deployer-role`) with deploy-level permissions, scoped to its own ServiceAccount in the `jenkins` namespace.

## 2. Secret Management

Secrets are defined in `k8s/base/secrets/circleguard-secrets.yaml` as Kubernetes `Secret` objects and injected into pods as environment variables via `secretKeyRef`. They are never stored in plaintext in environment files or application configuration.

**Current secrets:**

| Secret name | Contents |
|-------------|----------|
| `circleguard-db-credentials` | PostgreSQL username/password |
| `circleguard-neo4j-credentials` | Neo4j username/password |
| `circleguard-ldap-credentials` | OpenLDAP bind DN and password |
| `circleguard-jwt-secret` | JWT signing key |
| `circleguard-qr-secret` | QR code signing key |
| `circleguard-vault-secret` | Vault encryption keys |

> **Note:** The values in the dev secrets file are development defaults. In staging/prod, these must be replaced with strong, randomly generated values before deployment.

**Rotating a secret:**

```bash
# Edit the secret value
kubectl edit secret circleguard-jwt-secret -n circleguard-dev

# Restart affected services to pick up the new value
kubectl rollout restart deployment/circleguard-auth-service -n circleguard-dev
kubectl rollout restart deployment/circleguard-gateway-service -n circleguard-dev
```

**What is NOT committed to git:**
- Terraform Cloud API token (`~/.terraformrc`)
- Jenkins credentials (stored in Jenkins credential store)
- Any production secret values

## 3. TLS

TLS for the gateway service is implemented using [cert-manager](https://cert-manager.io/) with a self-signed `ClusterIssuer`.

**Manifests:** `k8s/base/cert-manager/`

| File | Purpose |
|------|---------|
| `cluster-issuer.yaml` | Self-signed ClusterIssuer (`circleguard-selfsigned`) |
| `gateway-certificate.yaml` | Certificate for `circleguard-gateway-service`, stored in secret `circleguard-gateway-tls` |

**Installing cert-manager (one-time, per cluster):**

```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.5/cert-manager.yaml
```

Wait ~60 seconds for the cert-manager pods to be ready:

```bash
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=cert-manager -n cert-manager --timeout=120s
```

Then apply the base manifests normally — cert-manager will provision the certificate and store the TLS keypair in the `circleguard-gateway-tls` secret automatically.

**Verifying the certificate:**

```bash
kubectl get certificate circleguard-gateway-tls -n circleguard-dev
# Expected: READY = True
```

## 4. Container Vulnerability Scanning

Trivy scans every built image for HIGH and CRITICAL CVEs as part of the Jenkins pipeline (`Container Scan` stage). Results are visible in the build console log. The scan is currently non-blocking (`--exit-code 0`) to allow the pipeline to continue while the CVE baseline is being established.
