# CircleGuard — Teammate Onboarding Guide

This document covers everything you need to do to get your local environment in sync with the project after cloning the repository. Follow the steps in order.

> **Note for Windows users:** Your teammate works on macOS. Most commands in this guide are cross-platform, but wherever Windows requires a different step it is clearly marked with a **🪟 Windows** label. It is strongly recommended to use **Git Bash** (installed with Git for Windows) as your terminal throughout this guide — it supports the same `cd`, `cat`, and path syntax used here. PowerShell and CMD equivalents are provided where needed.

---

## Prerequisites

Make sure you have these tools installed before starting:

| Tool | Version | Install |
|------|---------|---------|
| Git | any | https://git-scm.com — on Windows, choose **Git Bash** during install |
| Docker Desktop | latest | https://www.docker.com/products/docker-desktop — **enable Kubernetes in settings** |
| kubectl | any | https://kubernetes.io/docs/tasks/tools |
| Terraform CLI | >= 1.6.0 | https://developer.hashicorp.com/terraform/install |
| Java JDK | 17 | https://adoptium.net |
| Gradle | (wrapper included) | no install needed |

**🪟 Windows — recommended terminal:** Use **Git Bash** (comes with Git for Windows) for all commands in this guide. Open it by right-clicking any folder → "Git Bash Here" or searching "Git Bash" in the Start menu.

**🪟 Windows — Gradle wrapper:** Instead of `./gradlew`, use `gradlew.bat` from CMD/PowerShell, or `./gradlew` from Git Bash (both work).

---

## Step 1 — Clone the repository

```bash
git clone https://github.com/juancamilocorralesosvath/circle-guard-public.git
cd circle-guard-public
```

Make sure you have been added as a **collaborator** on GitHub by your teammate (Settings → Collaborators → Add people). You'll need write access to push branches.

---

## Step 2 — Enable Kubernetes in Docker Desktop

1. Open Docker Desktop → Settings → Kubernetes
2. Check **Enable Kubernetes**
3. Click **Apply & Restart**
4. Wait until the Kubernetes status indicator is green (bottom left of Docker Desktop)
5. Verify:

```bash
kubectl config get-contexts
# You should see "docker-desktop" in the list

kubectl cluster-info
# Should return the local cluster URL
```

**🪟 Windows:** Docker Desktop on Windows requires **WSL 2** as the backend (not Hyper-V) for Kubernetes to work reliably. If it asks you to install WSL 2, follow the prompt — it will guide you through it. After WSL 2 is installed, re-enable Kubernetes and restart.

To check if WSL 2 is set up: open PowerShell and run:
```powershell
wsl --list --verbose
```
You should see a distro with VERSION 2.

---

## Step 3 — Terraform Cloud access

The project uses Terraform Cloud (HCP Terraform) to store remote state for the three Kubernetes environments (`circleguard-dev`, `circleguard-staging`, `circleguard-prod`).

### 3a. Get added to the organization

Your teammate must add you to the `circleguard` organization on Terraform Cloud:

- They go to: `app.terraform.io` → org `circleguard` → **Settings → Users → Invite a member**
- They invite you by email
- You accept the invitation

You do **not** need admin access — member-level access is enough to run plans and applies locally.

### 3b. Create your own API token

1. Go to `app.terraform.io` → click your avatar (top right) → **User Settings → Tokens**
2. Click **Create an API token**
3. Give it a name (e.g. `local-dev`)
4. Copy the token — you will not see it again

### 3c. Configure the token locally

**macOS / Git Bash on Windows:**
```bash
cat > ~/.terraformrc << 'EOF'
credentials "app.terraform.io" {
  token = "YOUR_TOKEN_HERE"
}
EOF
```

**🪟 Windows (alternative — any text editor):**

The Terraform credentials file on Windows lives at `%APPDATA%\terraform.rc` (not `~/.terraformrc`). Create it with Notepad:

1. Press `Win + R`, type `%APPDATA%`, press Enter
2. Create a new file named `terraform.rc` (make sure it is not `terraform.rc.txt`)
3. Paste this content:
```hcl
credentials "app.terraform.io" {
  token = "YOUR_TOKEN_HERE"
}
```
4. Save and close

Replace `YOUR_TOKEN_HERE` with the token you copied in step 3b.

> **Why different paths?** On macOS/Linux Terraform reads `~/.terraformrc`. On Windows it reads `%APPDATA%\terraform.rc`. Both are equivalent — only the location differs.

---

## Step 4 — Initialize Terraform for each environment

```bash
cd terraform/environments/dev && terraform init
cd ../staging && terraform init
cd ../prod && terraform init
```

Each `init` should print `Terraform has been successfully initialized!` and connect to the remote backend on Terraform Cloud.

### Verify the connection

```bash
cd terraform/environments/dev
terraform plan
```

You should see a plan output listing resources to create (namespace, configmap, service accounts). No errors means the setup is correct.

> **Note:** The workspaces are set to **Local execution mode**, meaning Terraform runs on your machine and only stores state in the cloud. This is intentional — it also means the local module paths (`../../modules`) resolve correctly on your machine.

---

## Step 5 — Apply the infrastructure (optional, do once per environment)

If the environments haven't been applied yet on your cluster, or you want to create them:

```bash
cd terraform/environments/dev && terraform apply
cd terraform/environments/staging && terraform apply
cd terraform/environments/prod && terraform apply
```

Type `yes` when prompted. To verify what was created:

```bash
kubectl get namespaces | grep circleguard
kubectl get all -n circleguard-dev
kubectl get configmap circleguard-config -n circleguard-dev -o yaml
```

---

## Step 6 — Jenkins setup (if you are running Jenkins locally)

The project has a full CI/CD pipeline in `Jenkinsfile`. To run it locally you need Jenkins with the required credentials. Ask your teammate for the values of each secret — **never commit secrets to the repo**.

| Credential ID | Kind | What it is |
|---------------|------|------------|
| `dockerhub-creds` | Username + Password | Docker Hub username + access token |
| `kubeconfig-juanc0410` | Secret file | The kubeconfig file for the cluster |
| `github` | Username + Password | GitHub username + personal access token |

To add a credential in Jenkins:
1. **Manage Jenkins → Credentials → System → Global credentials → Add Credentials**
2. Select the Kind, fill in the values, set the ID exactly as shown in the table above

**🪟 Windows — Jenkins:** If you run Jenkins on Windows, make sure the Jenkins agent has access to `kubectl` and `terraform` by adding their install directories to the system `PATH` (System Properties → Environment Variables → Path). Otherwise pipeline stages that call these tools will fail with "command not found".

---

## Step 6b — SonarQube, Trivy, and Slack setup

Each developer runs their own local Jenkins + SonarQube stack so that either person can run and deliver the full pipeline independently.

After completing Step 6, follow **`docs/PHASE4_MANUAL_SETUP.md`** in full. It covers:

1. Starting SonarQube locally (`docker run ... sonarqube:lts-community`)
2. Generating a SonarQube token and connecting it to Jenkins
3. Rebuilding the Jenkins agent Docker image (which now includes Trivy)
4. Setting up the Slack app and configuring the Jenkins Slack plugin

Everything in that guide is self-contained — you do not need any credentials or tokens from your teammate, you generate your own for your own local stack.

**🪟 Windows — SonarQube:** The `docker run` command works the same on Windows as long as Docker Desktop is running. Open PowerShell or Git Bash and run it as written in the guide.

---

## Step 7 — Branching workflow

The project follows **GitHub Flow**:

- `main` is always stable — never push directly to it
- Create a branch for your work: `feature/<what-youre-doing>` or `fix/<what-youre-fixing>`
- Open a Pull Request to merge into `main`
- Your teammate reviews and merges

```bash
# Start new work
git checkout main && git pull
git checkout -b feature/my-feature

# Push your branch
git push -u origin feature/my-feature

# Open a PR on GitHub when ready
```

Commit messages follow Conventional Commits: `feat:`, `fix:`, `docs:`, `test:`, `chore:`.

---

## Quick reference — Terraform commands

```bash
# Preview changes (safe, never modifies anything)
terraform plan

# Apply changes to the cluster
terraform apply

# Destroy infrastructure (careful with prod)
terraform destroy

# Check current state
terraform show
```

Always `cd` into the environment folder before running any command:
- `terraform/environments/dev`
- `terraform/environments/staging`
- `terraform/environments/prod`

---

## Troubleshooting

**`terraform init` fails with authentication error**
→ Check that the credentials file exists and has the correct token.
- macOS: `~/.terraformrc`
- Windows: `%APPDATA%\terraform.rc`

**`terraform plan` says "no such context: docker-desktop"**
→ Docker Desktop Kubernetes is not running. Open Docker Desktop, go to Settings → Kubernetes, enable it and wait for the green indicator.

**🪟 Windows: `terraform plan` says "no such context" even though Docker Desktop is running**
→ Kubernetes may not have written the context to your kubeconfig yet. Run `kubectl config get-contexts` — if `docker-desktop` is missing, go to Docker Desktop → Settings → Kubernetes → Reset Kubernetes Cluster, then re-enable it.

**`kubectl` commands return "connection refused"**
→ The local cluster is not running. Start Docker Desktop and wait for Kubernetes to be ready.

**`terraform plan` shows resources already exist (conflict)**
→ The infrastructure was already applied by your teammate on their cluster. That's fine — each developer applies to their own local Docker Desktop cluster independently. Run `terraform apply` on yours too.

**🪟 Windows: `./gradlew` is not recognized**
→ Use `gradlew.bat` instead in CMD/PowerShell, or switch to Git Bash where `./gradlew` works normally.

**🪟 Windows: line ending issues (CRLF warnings in git)**
→ Run this once after cloning:
```bash
git config core.autocrlf input
```
This prevents Windows from converting line endings in shell scripts, which would break them on the CI server.

**Jenkins pipeline fails with missing credential**
→ The credential IDs in Jenkins must match exactly what is in the `Jenkinsfile`. Double-check the ID field when creating the credential.
