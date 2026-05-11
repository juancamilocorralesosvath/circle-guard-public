# Jenkins CI/CD Implementation

This document describes the Jenkins CI/CD architecture, pipeline, and operational guidance for CircleGuard.

Overview
- Jenkins runs in-cluster in the `jenkins` namespace as a StatefulSet with persistent storage for `/var/jenkins_home`.
- Build strategy: Docker-outside-of-Docker (DooD) using dedicated builder nodes labeled `docker-builder=true` and agent pods that mount the host Docker socket.
- Registry: Docker Hub (`docker.io`) under the `juanc0410` organization. Image names: `juanc0410/circleguard-<service>`.
- Tagging: immutable `<git-sha>` (short 7 chars) plus `latest` for convenience.

Jenkins Architecture
- Master: StatefulSet, single replica (workshop-friendly). UI exposed via NodePort (`32080`) in the provided manifests.
- Agents: Ephemeral pods via Kubernetes plugin scheduled to nodes labeled `docker-builder=true` and mounting `/var/run/docker.sock`.
- Storage: `jenkins-home` PVC (20Gi recommended for workshop).

Pipeline Architecture
- Single root `Jenkinsfile` (multi-branch) in repo root.
- Branch behavior:
  - `dev`: build → push → deploy to `dev` overlay automatically.
  - `staging`: build/push → integration/E2E/perf tests → manual approval.
  - `main`/`prod`: manual approval → tagged release → production deployment.

Stages (summary)
1. Checkout
2. Static validation (`./gradlew check`)
3. Unit tests (`./gradlew test`) and JUnit result archive
4. Build (`./gradlew clean build`) and archive JAR artifacts
5. Docker image build for 6 services and push to Docker Hub (`juanc0410/*`)
6. Kustomize-based deploy to environment overlay
7. Rollout validation (`kubectl rollout status`, `kubectl wait`)
8. Smoke tests (in-cluster health checks)

Kustomize Integration
- Pipeline uses `kustomize edit set image` to set image tags in the overlay then runs `kustomize build | kubectl apply -f -` to apply the changes.

Rollout Validation
- `kubectl rollout status deployment/<name> -n <ns> --timeout=180s`
- `kubectl wait --for=condition=ready pod -l app=<label> -n <ns> --timeout=120s`

Failure Handling
- Pipeline fails on any stage error.
- On rollout failure: run `kubectl rollout undo deployment/<name> -n <namespace>` or use `kubectl set image` to revert to the previous tag.

Notes & Constraints
- DooD requires nodes with Docker Engine and hostPath access; for clusters that restrict hostPath, use Kaniko instead.
- For workshop simplicity we use a single Jenkins replica. For production, scale and HA are required.
