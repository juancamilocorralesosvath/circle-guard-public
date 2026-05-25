# Jenkins Credentials and Secrets

Required Jenkins credentials (IDs suggested):

- `dockerhub-creds` (Username / Password or token) — Docker Hub account or token used to push images. Create an access token in Docker Hub and add as username/secret in Jenkins Credentials.
- `kubeconfig-juanc0410` (Secret file) — Optional kubeconfig file for `kubectl` access if Jenkins is running outside the cluster. If Jenkins runs in-cluster, prefer ServiceAccount-based access and create RBAC bindings for the `jenkins` ServiceAccount.
- `git-ssh-key` (SSH private key) — Optional, if Jenkins checks out private repositories using SSH.
- `slack-webhook` (Secret text) — Optional webhook for notifications.

Creating Docker Hub token (recommended):

1. Login to Docker Hub → Account Settings → Security → New Access Token
2. Name the token (e.g., `jenkins-ci`) and create it. Copy the token.
3. In Jenkins UI: Credentials → System → Global credentials → Add Credentials
   - Kind: Username with password
   - Username: your Docker Hub username (juanc0410)
   - Password: the token
   - ID: `dockerhub-creds`

Kubeconfig usage (if running Jenkins outside the cluster):
1. Create a service account in the target namespace and grant it a Role/ClusterRole (see `k8s/base/rbac/jenkins-clusterrole.yaml`).
2. Create a kubeconfig that uses the service account token.
3. In Jenkins: Add a new "Secret file" credential with ID `kubeconfig-juanc0410` and upload the kubeconfig file.

Security best practices
- Prefer in-cluster `ServiceAccount` with least-privilege RBAC over distributing kubeconfig files.
- Do not print secrets in pipeline logs. Use `withCredentials` to bind secrets and never echo them.
- For production, consider sealed-secrets, external secret managers, or HashiCorp Vault.
