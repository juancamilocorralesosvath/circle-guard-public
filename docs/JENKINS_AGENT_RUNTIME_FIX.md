JENKINS AGENT RUNTIME FIX

Summary
- Problem: Jenkins pipeline failed with `docker: not found` inside Kubernetes agents when performing DooD builds.
- Root cause: the agent image used by the Kubernetes plugin did not contain a Docker CLI binary (either the default `jenkins/inbound-agent` or an earlier custom image without `docker-ce-cli`). Interrupted builds produced and pushed images missing `docker`.

Fix applied
- Install Docker CLI (ARM64-aware) in the custom agent image by adding Docker apt repo and installing `docker-ce-cli`.
- Make `kubectl` and `kustomize` downloads architecture-aware so the agent image works on both AMD64 and ARM64 (Apple Silicon).

Dockerfile excerpts (applied)

```dockerfile
# Install Docker CLI
RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates curl gnupg lsb-release git \
  && mkdir -p /etc/apt/keyrings \
  && curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian $(lsb_release -cs) stable" \
    > /etc/apt/sources.list.d/docker.list \
  && apt-get update \
  && apt-get install -y --no-install-recommends docker-ce-cli \
  && apt-get clean \
  && rm -rf /var/lib/apt/lists/* /etc/apt/keyrings/docker.gpg

# Install kubectl (arch-aware)
RUN ARCH="$(uname -m)" \
  && if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then KUBECTL_ARCH=arm64; else KUBECTL_ARCH=amd64; fi \
  && curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/${KUBECTL_ARCH}/kubectl" \
  && install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl \
  && rm kubectl

# Install kustomize (arch-aware)
RUN KUSTOMIZE_VER=v5.1.0 \
  && ARCH="$(uname -m)" \
  && if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then KUSTOMIZE_ARCH=arm64; else KUSTOMIZE_ARCH=amd64; fi \
  && curl -sL "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2F${KUSTOMIZE_VER}/kustomize_${KUSTOMIZE_VER}_linux_${KUSTOMIZE_ARCH}.tar.gz" | tar xz \
  && install -o root -g root -m 0755 kustomize /usr/local/bin/kustomize \
  && rm kustomize
```

Build & push (example)
- Build: `DOCKER_BUILDKIT=1 docker build --no-cache --progress=plain -t juanc0410/jenkins-agent:latest -f jenkins/agent/Dockerfile jenkins/agent`
- Push: `docker push juanc0410/jenkins-agent:latest`

Local verification
- `docker run --rm --entrypoint sh juanc0410/jenkins-agent:latest -c "docker --version; kubectl version --client; kustomize version"`

Cluster verification (quick test)
- Create a test pod that pulls the agent image and runs the CLIs:
  - `kubectl -n jenkins run --restart=Never jnlp-test --image=juanc0410/jenkins-agent:latest --command -- sh -c "docker --version; kubectl version --client; kustomize version; sleep 30"`
  - `kubectl -n jenkins logs pod/jnlp-test`
  - `kubectl -n jenkins delete pod jnlp-test --wait=true`

Jenkins configuration & agent rollout
- Confirm the Kubernetes PodTemplate uses the custom image and will pull it at pod create time. Example check (from master pod):
  - `kubectl -n jenkins exec jenkins-0 -- cat /var/jenkins_home/config.xml | sed -n '1,240p'` and look for `<image>juanc0410/jenkins-agent:latest</image>` and `<alwaysPullImage>true</alwaysPullImage>`.
- The Kubernetes plugin creates ephemeral agent pods for jobs. To ensure agents use the updated image:
  - Ensure `alwaysPullImage` is `true` (so new agent pods pull the latest tag).
  - Re-run the pipeline job (or trigger any job that uses the `docker-builder` label) — the plugin will create a new agent pod which pulls the updated image.
  - If you want to force immediate re-pull of existing agent pods, delete them (they will be re-created): `kubectl -n jenkins delete pod -l <agent-pod-label>` (replace with the pod label used by your PodTemplate).

Node / DooD prerequisites
- For Docker-in-Docker-over-socket (DooD) builds the agent PodTemplate mounts the host socket at `/var/run/docker.sock` and requires the node to:
  - Have Docker installed and `/var/run/docker.sock` present.
  - Be labeled to receive builder pods, e.g. `kubectl label node <node-name> docker-builder=true --overwrite`.

Validation performed here
- I built and pushed `juanc0410/jenkins-agent:latest` with `docker-ce-cli`, `kubectl`, and `kustomize`.
- I validated by running a test pod in `jenkins` namespace; output showed `Docker version` and `kubectl`/`kustomize` were present.

Next steps (recommended)
- Re-run the multibranch pipeline (dev branch) — confirm `docker --version` appears in the agent log and the Docker build step proceeds.
- If the pipeline still fails, paste the agent pod logs and I will triage further.

--
Recorded by: GitHub Copilot (GPT-5 mini)
