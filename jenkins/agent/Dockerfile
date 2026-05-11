FROM jenkins/inbound-agent:latest
USER root
RUN apt-get update \
  && apt-get install -y --no-install-recommends docker.io curl git ca-certificates gnupg2 \
  && apt-get clean \
  && rm -rf /var/lib/apt/lists/*
# Install kubectl
RUN curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \
  && install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl \
  && rm kubectl
# Install kustomize (simple installer)
RUN KUSTOMIZE_VER=v5.1.0 && \
  curl -sL "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2F${KUSTOMIZE_VER}/kustomize_${KUSTOMIZE_VER}_linux_arm64.tar.gz" \
  | tar xz && \
  install -o root -g root -m 0755 kustomize /usr/local/bin/kustomize && \
  rm kustomize
USER jenkins
