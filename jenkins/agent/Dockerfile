FROM jenkins/inbound-agent:latest
USER root
# Install Docker CLI via Docker's official repository (docker-ce-cli)
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
# Install kubectl (choose arch at build-time)
RUN ARCH="$(uname -m)" \
  && if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then KUBECTL_ARCH=arm64; else KUBECTL_ARCH=amd64; fi \
  && curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/${KUBECTL_ARCH}/kubectl" \
  && install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl \
  && rm kubectl

# Install kustomize (choose arch at build-time)
RUN KUSTOMIZE_VER=v5.1.0 \
  && ARCH="$(uname -m)" \
  && if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then KUSTOMIZE_ARCH=arm64; else KUSTOMIZE_ARCH=amd64; fi \
  && curl -sL "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2F${KUSTOMIZE_VER}/kustomize_${KUSTOMIZE_VER}_linux_${KUSTOMIZE_ARCH}.tar.gz" | tar xz \
  && install -o root -g root -m 0755 kustomize /usr/local/bin/kustomize \
  && rm kustomize
USER jenkins
